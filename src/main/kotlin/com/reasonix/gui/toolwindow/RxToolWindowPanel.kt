package com.reasonix.gui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.reasonix.gui.runner.ReasonixRunner
import com.reasonix.gui.runner.SetupRunner
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class RxToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val chatView: ChatView
    private val settingsView: SettingsView

    init {
        settingsView = SettingsView { cardLayout.show(cardPanel, "chat") }
        chatView = ChatView()
        cardPanel.add(chatView, "chat")
        cardPanel.add(settingsView, "settings")
        cardLayout.show(cardPanel, "chat")
        add(cardPanel, BorderLayout.CENTER)
        preferredSize = Dimension(420, 600)
    }

    // ══════════════════════════════════════════════
    //  Chat View
    // ══════════════════════════════════════════════

    private inner class ChatView : JPanel(BorderLayout()) {

        private val messageContainer = JPanel()
        private val messageScroll = JScrollPane(messageContainer)
        private val inputArea = JTextArea(3, 20)
        private val sendButton = JButton("Send")
        private val selectedBanner = JLabel()
        private var thinkingPanel: JPanel? = null
        private var thinking = false
        private val runner = ReasonixRunner()

        init {
            buildUI()
            bindActions()
            addWelcome()
        }

        private fun buildUI() {
            // ── 顶部栏 ──
            val top = JPanel(BorderLayout()).apply {
                border = MatteBorder(0, 0, 1, 0, Color(0xD0D0D0))
            }
            val dot = JLabel("●").apply { foreground = Color(0x4CAF50); font = Font(name, Font.PLAIN, 12) }
            val projLbl = JLabel(project.name).apply { font = Font(name, Font.PLAIN, 11); foreground = Color(0x999) }
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3))
            left.add(dot); left.add(projLbl)

            val settingsBtn = JButton("\u2699").apply {
                font = Font(name, Font.PLAIN, 14)
                toolTipText = "Settings"
                isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(2, 6, 2, 6)
                addActionListener { cardLayout.show(cardPanel, "settings") }
            }
            val clearBtn = JButton("Clear").apply {
                font = Font(name, Font.PLAIN, 11)
                isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(2, 8, 2, 8)
                addActionListener {
                    SwingUtilities.invokeLater {
                        messageContainer.removeAll()
                        addWelcome()
                        messageContainer.revalidate()
                        messageContainer.repaint()
                    }
                }
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2))
            right.add(clearBtn); right.add(settingsBtn)

            top.add(left, BorderLayout.WEST); top.add(right, BorderLayout.EAST)
            add(top, BorderLayout.NORTH)

            // ── 消息区 ──
            messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
            messageContainer.background = Color(0xFAFAFA)
            messageScroll.border = null
            messageScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            messageScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            add(messageScroll, BorderLayout.CENTER)

            // ── 底部 ──
            val bottom = JPanel(BorderLayout())

            selectedBanner.apply {
                isVisible = false; font = Font("Monospaced", Font.PLAIN, 11)
                foreground = Color(0x607D8B)
                border = BorderFactory.createCompoundBorder(MatteBorder(1, 0, 0, 0, Color(0xFFCC80)), EmptyBorder(4, 10, 4, 10))
                background = Color(0xFFF3E0); isOpaque = true
            }
            bottom.add(selectedBanner, BorderLayout.NORTH)

            val inputWrap = JPanel(BorderLayout(6, 0)).apply {
                border = BorderFactory.createCompoundBorder(MatteBorder(1, 0, 0, 0, Color(0xD0D0D0)), EmptyBorder(8, 8, 8, 8))
            }
            inputArea.apply {
                lineWrap = true; wrapStyleWord = true
                font = Font("Monospaced", Font.PLAIN, 12)
                border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color(0xCCCCCC)), EmptyBorder(6, 8, 6, 8))
            }
            val inputScroll = JScrollPane(inputArea).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(380, 52)
            }
            inputWrap.add(inputScroll, BorderLayout.CENTER)
            inputWrap.add(sendButton, BorderLayout.EAST)
            sendButton.isEnabled = false

            bottom.add(inputWrap, BorderLayout.SOUTH)
            add(bottom, BorderLayout.SOUTH)

            // 输入框自适应高度
            inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
            })
        }

        private fun adjustInputHeight() {
            ApplicationManager.getApplication().invokeLater {
                val maxH = (height * 0.8).toInt().coerceAtLeast(60)
                val pref = inputArea.preferredSize.height
                val newH = pref.coerceAtMost(maxH).coerceAtLeast(50)
                (inputArea.parent as? JScrollPane)?.preferredSize = Dimension(380, newH + 4)
                revalidate()
            }
        }

        private fun bindActions() {
            // Enter = send, Shift+Enter = newline
            inputArea.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send")
            inputArea.inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "newline")
            inputArea.actionMap.put("send", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) { send() }
            })
            inputArea.actionMap.put("newline", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    inputArea.append("\n")
                }
            })

            sendButton.addActionListener { send() }

            inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { sendButton.isEnabled = !thinking && inputArea.text.isNotBlank() }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { sendButton.isEnabled = !thinking && inputArea.text.isNotBlank() }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
            })

            val selectionTimer = javax.swing.Timer(500) { checkSelection() }
            selectionTimer.start()
        }

        private fun addWelcome() {
            addAssistantMessage("<h3>👋 Welcome to RX GUI</h3><p>Select code, type a question, press <b>Enter</b> to send.<br>Click ⚙ to configure Reasonix.</p>")
        }

        private fun send() {
            val input = inputArea.text.trim()
            if (input.isEmpty() || thinking) return
            val ctx = getCodeCtx()
            val prompt = if (ctx != null) "Code:\n\n$ctx\n\n---\nUser: $input" else input
            addUserMessage(input)
            inputArea.text = ""
            selectedBanner.isVisible = false
            showThinking()
            runner.run(prompt) { result ->
                ApplicationManager.getApplication().invokeLater {
                    hideThinking()
                    addAssistantMessage(result)
                }
            }
        }

        private fun addUserMessage(text: String) = addBubble(text, true)
        private fun addAssistantMessage(text: String) = addBubble(text, false)

        private fun addBubble(raw: String, user: Boolean) {
            val html = renderMd(raw)
            val pane = JEditorPane("text/html", html).apply {
                isEditable = false; isOpaque = true
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                val bg = if (user) Color(0xE3F2FD) else Color(0xF5F5F5)
                background = bg
                // 模拟圆角：外边框色=背景色
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0, true), 4),
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(bg, 4),
                        EmptyBorder(6, 10, 6, 10)
                    )
                )
                preferredSize = Dimension(360, preferredSize.height.coerceAtLeast(28))
            }
            val inner = pane.text.replace("<html>", "").replace("</html>", "")
                .replace("<head>", "").replace("</head>", "").replace("<body>", "").replace("</body>", "")
            pane.text = """<html><head><style>
                pre{background:#263238;color:#EEFFFF;padding:10px;font-size:12px}
                code{background:#ECEFF1;color:#C62828;padding:1px 4px;font-size:12px}
                pre code{background:transparent;color:#EEFFFF;padding:0}
            </style></head><body style='font-size:13px;margin:0;padding:0'>$inner</body></html>"""

            val outer = object : JPanel(FlowLayout(if (user) FlowLayout.RIGHT else FlowLayout.LEFT)) {
                override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            outer.isOpaque = false; outer.border = EmptyBorder(4, 8, 4, 8)
            outer.add(pane)
            messageContainer.add(outer)
            messageContainer.revalidate()
            messageContainer.repaint()
            SwingUtilities.invokeLater { messageScroll.verticalScrollBar.value = messageScroll.verticalScrollBar.maximum }
        }

        private fun showThinking() {
            thinking = true; sendButton.isEnabled = false
            val l = JLabel("Thinking …").apply { font = Font(name, Font.ITALIC, 12); foreground = Color(0x9E9E9E) }
            thinkingPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false; add(l) }
            messageContainer.add(thinkingPanel)
            messageContainer.revalidate(); messageContainer.repaint()
        }

        private fun hideThinking() {
            thinking = false
            thinkingPanel?.let { messageContainer.remove(it); messageContainer.revalidate(); messageContainer.repaint() }
            thinkingPanel = null
            sendButton.isEnabled = inputArea.text.isNotBlank()
        }

        private fun renderMd(s: String): String {
            var h = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            h = Regex("```(\\w*)\n([\\s\\S]*?)```").replace(h) { "<pre>${it.groupValues[2].trimEnd()}</pre>" }
            h = Regex("`([^`]+)`").replace(h) { "<code>${it.groupValues[1]}</code>" }
            h = Regex("\\*\\*(.+?)\\*\\*").replace(h) { "<b>${it.groupValues[1]}</b>" }
            h = Regex("\\*(.+?)\\*").replace(h) { "<i>${it.groupValues[1]}</i>" }
            h = Regex("(?m)^### (.+)$").replace(h) { "<h3>${it.groupValues[1]}</h3>" }
            h = Regex("(?m)^## (.+)$").replace(h) { "<h2>${it.groupValues[1]}</h2>" }
            h = Regex("(?m)^# (.+)$").replace(h) { "<h1>${it.groupValues[1]}</h1>" }
            h = h.replace("\n\n", "<br><br>").replace("\n", "<br>")
            return "<html>$h</html>"
        }

        private fun checkSelection() {
            val code = selectedCode()
            if (code != null) {
                val f = selectedFile() ?: "unknown"
                val preview = if (code.length > 60) code.take(60) + "…" else code
                selectedBanner.text = "  📎 $f (${code.lines().size} lines) — $preview"
                selectedBanner.isVisible = true
            } else selectedBanner.isVisible = false
        }

        private fun selectedCode(): String? {
            return try {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
                val t = editor.selectionModel.selectedText
                if (t.isNullOrBlank()) null else t.trim()
            } catch (_: Exception) { null }
        }

        private fun selectedFile(): String? {
            return try {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
                FileDocumentManager.getInstance().getFile(editor.document)?.name
            } catch (_: Exception) { null }
        }

        private fun getCodeCtx(): String? {
            val code = selectedCode() ?: return null
            val file = selectedFile() ?: "unknown"
            val lang = when {
                file.endsWith(".py") -> "python"
                file.endsWith(".kt") -> "kotlin"
                file.endsWith(".java") -> "java"
                file.endsWith(".ts") -> "typescript"
                file.endsWith(".js") -> "javascript"
                file.endsWith(".go") -> "go"
                file.endsWith(".rs") -> "rust"
                file.endsWith(".rb") -> "ruby"
                file.endsWith(".swift") -> "swift"
                file.endsWith(".sh") -> "bash"
                file.endsWith(".c") || file.endsWith(".h") -> "c"
                file.endsWith(".cpp") || file.endsWith(".hpp") -> "cpp"
                else -> ""
            }
            return "```$lang ($file)\n$code\n```"
        }
    }

    // ══════════════════════════════════════════════
    //  Settings View
    // ══════════════════════════════════════════════

    private class SettingsView(private val onBack: () -> Unit) : JPanel(BorderLayout()) {

        private val contentStack = JPanel(CardLayout())
        private val corePage = CorePage()
        private val aboutPage = AboutPage()

        init {
            // 顶级栏
            val topBar = JPanel(BorderLayout()).apply { border = MatteBorder(0, 0, 1, 0, Color(0xD0D0D0)) }
            val backBtn = JButton("← Chat").apply {
                font = Font(name, Font.PLAIN, 12)
                isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(2, 8, 2, 8)
                addActionListener { onBack() }
            }
            topBar.add(backBtn, BorderLayout.WEST)
            topBar.add(JLabel("Settings").apply { font = Font(name, Font.BOLD, 12); border = EmptyBorder(0, 12, 0, 0) }, BorderLayout.CENTER)
            add(topBar, BorderLayout.NORTH)

            // 左侧菜单
            val menu = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = MatteBorder(0, 0, 0, 1, Color(0xD0D0D0))
                preferredSize = Dimension(40, 1)
            }

            menu.add(Box.createVerticalStrut(8))
            menu.add(menuItem("\uD83D\uDD0C", "Core", true) { show("core") })
            menu.add(Box.createVerticalStrut(2))
            menu.add(menuItem("\u2139\uFE0F", "About", false) { show("about") })
            menu.add(Box.createVerticalGlue())

            contentStack.add(corePage, "core")
            contentStack.add(aboutPage, "about")

            val body = JPanel(BorderLayout())
            body.add(menu, BorderLayout.WEST)
            body.add(contentStack, BorderLayout.CENTER)
            add(body, BorderLayout.CENTER)
        }

        private fun show(name: String) {
            (contentStack.layout as CardLayout).show(contentStack, name)
        }

        private fun menuItem(emoji: String, tip: String, active: Boolean, action: () -> Unit): JButton {
            return JButton(emoji).apply {
                toolTipText = tip
                font = Font(name, Font.PLAIN, 14)
                isOpaque = false; isContentAreaFilled = false
                border = EmptyBorder(8, 0, 8, 0)
                maximumSize = Dimension(40, 36)
                alignmentX = Component.CENTER_ALIGNMENT
                addActionListener { action() }
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Core Page
    // ══════════════════════════════════════════════

    private class CorePage : JPanel(BorderLayout()) {

        private val runner = SetupRunner()
        private val statusLabel = JLabel()
        private val installBtn = JButton()
        private val terminal = JTextArea()

        init {
            border = EmptyBorder(24, 24, 16, 24)

            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

            box.add(JLabel("RX Core").apply { font = Font(name, Font.BOLD, 18); alignmentX = 0f })
            box.add(Box.createVerticalStrut(16))

            statusLabel.font = Font(name, Font.PLAIN, 13)
            statusLabel.alignmentX = 0f
            box.add(statusLabel)
            box.add(Box.createVerticalStrut(14))

            installBtn.apply {
                font = Font(name, Font.PLAIN, 13)
                alignmentX = 0f
                maximumSize = Dimension(240, 34)
                addActionListener { doInstall() }
            }
            box.add(installBtn)
            box.add(Box.createVerticalStrut(20))

            terminal.apply {
                isEditable = false
                font = Font("Monospaced", Font.PLAIN, 11)
                background = Color(0x263238); foreground = Color(0xEEFFFF)
                alignmentX = 0f
            }
            val ts = JScrollPane(terminal).apply {
                preferredSize = Dimension(300, 180)
                maximumSize = Dimension(Int.MAX_VALUE, 250)
                alignmentX = 0f
            }
            box.add(ts)

            add(box, BorderLayout.CENTER)
            refresh()
        }

        private fun refresh() {
            Thread {
                val installed = runner.isInstalled()
                val ver = runner.getVersion()
                SwingUtilities.invokeLater {
                    if (installed) {
                        statusLabel.text = "✅ Reasonix is installed — $ver"
                        statusLabel.foreground = Color(0x388E3C)
                        installBtn.text = "Reasonix already installed"
                        installBtn.isEnabled = false
                        terminal.text = ver
                    } else {
                        statusLabel.text = "⚠️ Reasonix CLI not found"
                        statusLabel.foreground = Color(0xE65100)
                        installBtn.text = "Install Reasonix"
                        installBtn.isEnabled = true
                        terminal.text = "(reasonix command not in PATH)\n"
                    }
                }
            }.start()
        }

        private fun doInstall() {
            installBtn.isEnabled = false
            terminal.append("> npm install -g reasonix\n")
            Thread {
                val r = runner.install { line -> SwingUtilities.invokeLater { terminal.append("  $line\n") } }
                SwingUtilities.invokeLater {
                    terminal.append(if (r.success) "✅ Done!\n" else "❌ ${r.output}\n")
                    terminal.append("────────────────────\n")
                    refresh()
                }
            }.start()
        }
    }

    // ══════════════════════════════════════════════
    //  About Page
    // ══════════════════════════════════════════════

    private class AboutPage : JPanel(BorderLayout()) {
        init {
            border = EmptyBorder(24, 24, 16, 24)
            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            box.add(JLabel("About RX GUI").apply { font = Font(name, Font.BOLD, 18) })
            box.add(Box.createVerticalStrut(14))
            val info = JEditorPane("text/html", """<html><body style='font-family:-apple-system,sans-serif;font-size:13px'>
                <p><b>RX GUI</b> v0.1.0</p>
                <p style='margin-top:6px'>Reasonix AI assistant for JetBrains IDEs.</p>
                <p style='margin-top:6px;color:#888'>https://reasonix.io</p>
                <p style='margin-top:12px;color:#888;font-size:11px'>License: MIT</p>
            </body></html>""").apply { isEditable = false; isOpaque = false; preferredSize = Dimension(300, 160) }
            box.add(info)
            add(box, BorderLayout.CENTER)
        }
    }
}
