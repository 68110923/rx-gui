package com.reasonix.gui.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*

// ── Diff result sealed class ──

sealed class DiffResult {
    data class Apply(val content: String) : DiffResult()
    object Reject : DiffResult()
    object Dismiss : DiffResult()
}

// ── Diff request data ──

data class InteractiveDiffRequest(
    val filePath: String,
    val originalContent: String,
    val newContent: String,
    val tabName: String,
    val isNewFile: Boolean = false,
    val readOnly: Boolean = false
)

// ── Manager ──

object InteractiveDiffManager {

    private const val CLOSE_REJECT_DELAY_MS = 600L

    /**
     * Show an interactive diff view with Accept/Reject buttons.
     * Returns a CompletableFuture that completes with the user's action.
     */
    fun showInteractiveDiff(
        project: Project,
        request: InteractiveDiffRequest
    ): CompletableFuture<DiffResult> {
        val resultFuture = CompletableFuture<DiffResult>()
        if (project.isDisposed) {
            resultFuture.complete(DiffResult.Reject)
            return resultFuture
        }
        ApplicationManager.getApplication().invokeLater {
            try {
                showDiffInternal(project, request, resultFuture)
            } catch (e: Exception) {
                resultFuture.complete(DiffResult.Reject)
            }
        }
        return resultFuture
    }

    @RequiresEdt
    private fun showDiffInternal(
        project: Project,
        request: InteractiveDiffRequest,
        resultFuture: CompletableFuture<DiffResult>
    ) {
        val originalContent = request.originalContent
        val newContent = request.newContent
        var fileType: FileType? = null

        val actualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(request.filePath.replace('\\', '/'))
        if (actualFile != null && actualFile.exists()) {
            fileType = actualFile.fileType
        }

        val fileName = File(request.filePath).name
        if (fileType == null || fileType === FileTypes.UNKNOWN) {
            fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        }

        val contentFactory = DiffContentFactory.getInstance()
        val originalDiffContent = contentFactory.create(project, originalContent, fileType)
        val proposedDiffContent = contentFactory.create(project, newContent, fileType)

        val leftTitle = if (request.isNewFile) "(new file)" else "Original"
        val diffRequest = SimpleDiffRequest(
            request.tabName,
            originalDiffContent,
            proposedDiffContent,
            leftTitle,
            "Proposed"
        )

        diffRequest.putUserData(
            DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
            booleanArrayOf(true, request.readOnly)
        )
        diffRequest.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)

        val diffRequestChain = SimpleDiffRequestChain(diffRequest)
        val actionApplied = AtomicBoolean(false)
        val rejectFutureRef = AtomicReference<ScheduledFuture<*>>()
        val connection = project.messageBus.connect()

        // ── Bottom panel with buttons ──

        val rejectButton = object : JButton("Reject") {
            init {
                icon = AllIcons.Actions.Cancel
                addActionListener {
                    if (actionApplied.compareAndSet(false, true)) {
                        connection.disconnect()
                        cancelPendingRef(rejectFutureRef)
                        resultFuture.complete(DiffResult.Reject)
                        closeDiffView(project, diffRequestChain)
                    }
                }
            }
        }

        val applyButton = object : JButton("Apply") {
            init {
                icon = AllIcons.Actions.Checked
                addActionListener {
                    if (actionApplied.compareAndSet(false, true)) {
                        connection.disconnect()
                        cancelPendingRef(rejectFutureRef)
                        resultFuture.complete(DiffResult.Apply(newContent))
                        closeDiffView(project, diffRequestChain)
                    }
                }
            }
        }

        val buttonsPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 10, 5)).apply {
            add(rejectButton)
            add(applyButton)
        }

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.border.EmptyBorder(6, 0, 8, 0)
            buttonsPanel.alignmentX = 0.5f
            add(buttonsPanel)
        }

        diffRequestChain.putUserData(DiffUserDataKeysEx.BOTTOM_PANEL, bottomPanel)

        // ── Track diff window open/close ──

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (file is ChainDiffVirtualFile && file.chain === diffRequestChain) {
                        cancelPendingRef(rejectFutureRef)
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                applyButton.rootPane?.defaultButton = applyButton
                                applyButton.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file is ChainDiffVirtualFile && file.chain === diffRequestChain) {
                        val future = AppExecutorUtil.getAppScheduledExecutorService()
                            .schedule({
                                ApplicationManager.getApplication().invokeLater {
                                    if (actionApplied.compareAndSet(false, true)) {
                                        connection.disconnect()
                                        resultFuture.complete(DiffResult.Dismiss)
                                    }
                                }
                            }, CLOSE_REJECT_DELAY_MS, TimeUnit.MILLISECONDS)
                        rejectFutureRef.set(future)
                    }
                }
            }
        )

        DiffManagerEx.getInstance().showDiffBuiltin(project, diffRequestChain, DiffDialogHints.DEFAULT)
    }

    // ── Helpers ──

    private fun closeDiffView(project: Project, chain: SimpleDiffRequestChain) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val manager = FileEditorManager.getInstance(project)
                for (file in manager.openFiles) {
                    if (file is ChainDiffVirtualFile && file.chain === chain) {
                        manager.closeFile(file)
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun cancelPendingRef(ref: AtomicReference<ScheduledFuture<*>>) {
        val pending = ref.getAndSet(null)
        if (pending != null && !pending.isDone) {
            pending.cancel(false)
        }
    }
}
