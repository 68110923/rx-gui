package com.reasonix.gui.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.reasonix.gui.acp.*
import com.reasonix.gui.diff.DiffResult
import com.reasonix.gui.diff.InteractiveDiffManager
import com.reasonix.gui.diff.InteractiveDiffRequest
import com.reasonix.gui.util.ReasonixCli
import com.reasonix.gui.util.ReasonixApiClient
import java.awt.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class ChatPanel(val project: Project) : JPanel(BorderLayout()) {

    private val client = AcpClient()
    private val browser = JBCefBrowser()
    private val bridge = JBCefJSQuery.create(browser)
    @Volatile private var sessionId: String? = null
    private var thinking = false
    private val toolCalls = ConcurrentHashMap<String, String>()
    private val cachedToolInputs = ConcurrentHashMap<String, String>()
    private var lastSelection: String? = null
    private var selectedFile: String? = null
    @Volatile private var currentMode: String = "auto"
    @Volatile private var currentModel: String = "deepseek-v4-flash"
    @Volatile private var currentPreset: String = "auto"
    @Volatile private var currentEffort: String = "medium"
    private var devResourcesDir: String? = null
    @Volatile private var currentCachePct: String? = null
    @Volatile private var currentCostLeft: String? = null
    @Volatile private var currentCostSpent: String? = null
    @Volatile private var currentCostUsd: Double? = null
    @Volatile private var currentBalance: String? = null
    private var apiClient: ReasonixApiClient? = null
    private var devWatchService: WatchService? = null
    @Volatile private var currentBranch: String? = null
    @Volatile private var ctxTokens: Long = 0
    @Volatile private var ctxCap: Long = 1_000_000
    @Volatile private var trackedInputChars: Long = 0
    @Volatile private var trackedOutputChars: Long = 0

    init {
        devResourcesDir = System.getProperty("rx-gui.dev-resources")
        currentMode = ReasonixCli.getEditMode()
        currentModel = ReasonixCli.readConfig().get("model")?.asString ?: "deepseek-v4-flash"
        currentPreset = ReasonixCli.readConfig().get("preset")?.asString ?: "auto"
        currentEffort = ReasonixCli.readConfig().get("reasoningEffort")?.asString ?: "medium"
        reloadHtml()
        add(browser.component, BorderLayout.CENTER)
        bridge.addHandler { msg -> handleJsMessage(msg); null }
        injectBridge()
        client.addCallback(AcpCallback())
        connect()
        // Send initial status to frontend (even before ACP connects)
        SwingUtilities.invokeLater { buildAndSendStatusInfo() }
        javax.swing.Timer(500) { checkSelection() }.start()
        startDevWatcher()
    }

    fun dispose() {
        try { devWatchService?.close() } catch (_: Exception) {}
        toolCalls.clear()
        cachedToolInputs.clear()
        client.disconnect()
    }

    private fun connect() {
        client.connect(project.basePath ?: System.getProperty("user.home"))
    }

    // ── Cost tracking from usage.jsonl ──

    private fun startApiClient() {
        Thread({
            try {
                val dir = project.basePath ?: return@Thread
                apiClient = ReasonixApiClient.startAndDetect(dir)
                if (apiClient != null) pollApiStats()
                else readUsageCost() // fallback: read usage.jsonl
            } catch (_: Exception) {}
        }, "rx-api-start").start()
    }

    private fun readUsageCost() {
        try {
            val home = System.getProperty("user.home") ?: return
            val usageFile = java.io.File(home, ".reasonix/usage.jsonl")
            if (!usageFile.exists()) return
            var totalCost = 0.0
            usageFile.forEachLine { line ->
                try {
                    val obj = JsonParser.parseString(line).asJsonObject
                    totalCost += obj.get("costUsd")?.asDouble ?: 0.0
                } catch (_: Exception) {}
            }
            currentCostUsd = totalCost
            buildAndSendStatusInfo()
        } catch (_: Exception) {}
    }

    private fun pollApiStats() {
        val api = apiClient ?: return
        try {
            var changed = false
            val cost = api.getCost()
            if (cost != null && (currentCostUsd == null || cost != currentCostUsd)) {
                currentCostUsd = cost
                changed = true
            }
            val balance = api.getBalance()
            if (balance != null && balance != currentBalance) {
                currentBalance = balance
                changed = true
            }
            val cache = api.getCachePct()
            if (cache != null) {
                val pct = "%.1f".format(cache * 100)
                if (pct != currentCachePct) {
                    currentCachePct = pct
                    changed = true
                }
            }
            if (changed) buildAndSendStatusInfo()
        } catch (_: Exception) {}
    }

    // ── Dev-mode HTML hot-reload ──

    private fun reloadHtml() {
        var html = loadHtmlTemplate().replace("{{PROJECT}}", project.name)
        html = html.replace("</body>", "<script>window._rxMode=${jsStr(currentMode)};setConfig(${jsStr(currentModel)}, ${jsStr(currentPreset)}, ${jsStr(currentEffort)})</script></body>")
        browser.loadHTML(html)
    }

    private fun loadHtmlTemplate(): String {
        if (devResourcesDir != null) {
            val devFile = java.io.File(devResourcesDir, "chat.html")
            if (devFile.exists()) return devFile.readText()
        }
        return ChatPanel::class.java.getResourceAsStream("/chat.html")
            ?.bufferedReader()?.readText() ?: "<html><body><h2>Error</h2></body></html>"
    }

    private fun startDevWatcher() {
        val dir = devResourcesDir ?: return
        try {
            val path = Paths.get(dir)
            val watcher = FileSystems.getDefault().newWatchService()
            devWatchService = watcher
            path.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
            Thread({
                while (true) {
                    val key = try { watcher.take() } catch (_: InterruptedException) { break }
                    val reload = key.pollEvents().any { event ->
                        event.context().toString() == "chat.html"
                    }
                    key.reset()
                    if (reload) {
                        SwingUtilities.invokeLater {
                            runJs("console.log('chat.html changed, reloading...')")
                            reloadHtml()
                        }
                    }
                }
            }, "rx-dev-watcher").apply { isDaemon = true; start() }
        } catch (_: Exception) {}
    }

    // ── JS Bridge ──

    private fun injectBridge() {
        browser.cefBrowser.executeJavaScript(
            "window.sendToJava = function(msg) { ${bridge.inject("msg")} };",
            browser.cefBrowser.url, 0
        )
    }

    private fun handleJsMessage(msg: String) {
        when {
            msg.startsWith("send:") -> {
                val raw = msg.removePrefix("send:")
                val split = raw.indexOf("__CTX__")
                val text = if (split < 0) raw else raw.substring(0, split).trimEnd()
                val ctx = if (split >= 0) raw.substring(split + 8).trimStart() else null
                if (text.startsWith("/")) {
                    execCommand(text)
                } else {
                    doSend(text, ctx)
                }
            }
            msg == "stop" -> {
                setThinking(false)
                client.cancelSession()
            }
            msg.startsWith("config:model:") -> {
                currentModel = msg.removePrefix("config:model:")
                ReasonixCli.writeConfig("model", currentModel)
                sessionId = null
                updateStatusBar()
            }
            msg.startsWith("config:preset:") -> {
                currentPreset = msg.removePrefix("config:preset:")
                ReasonixCli.writeConfig("preset", currentPreset)
                sessionId = null
                updateStatusBar()
            }
            msg.startsWith("config:effort:") -> {
                currentEffort = msg.removePrefix("config:effort:")
                ReasonixCli.writeConfig("reasoningEffort", currentEffort)
                sessionId = null
            }
            msg == "settings" -> showManageReasonix()
            msg == "ctx:clear" -> { lastSelection = null; selectedFile = null }
            msg.startsWith("config:mode:") -> {
                currentMode = msg.removePrefix("config:mode:")
                ReasonixCli.writeConfig("editMode", currentMode)
                sessionId = null
                updateStatusBar()
            }
            msg == "new-session" -> { sessionId = null; runJs("clearChat()") }
            msg.startsWith("permission:") -> {
                val parts = msg.removePrefix("permission:").split(":", limit = 2)
                if (parts.size == 2) client.respondPermission(parts[0], parts[1])
            }
        }
    }

    private fun runJs(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    // ── Command execution ──

    private fun execCommand(text: String) {
        val cmdLine = text.removePrefix("/").trim()
        runJs("appendUserMessage(${jsStr(text)})")
        // Handle built-in commands
        if (cmdLine.startsWith("model ")) {
            val model = cmdLine.removePrefix("model ").trim()
            if (model.isNotEmpty()) {
                currentModel = model
                ReasonixCli.writeConfig("model", model)
                sessionId = null
                setThinking(false)
                buildAndSendStatusInfo()
                val html = "<div style=\"padding:6px 0;color:var(--text2);font-size:12px\">✓ Model switched to <code>${escHtml(model)}</code></div>"
                runJs("appendHtmlToLast(${jsStr(html)})")
            }
            return
        }
        if (cmdLine.startsWith("preset ")) {
            val preset = cmdLine.removePrefix("preset ").trim()
            if (preset.isNotEmpty()) {
                currentPreset = preset
                ReasonixCli.writeConfig("preset", preset)
                sessionId = null
                setThinking(false)
                buildAndSendStatusInfo()
                val html = "<div style=\"padding:6px 0;color:var(--text2);font-size:12px\">✓ Preset switched to <code>${escHtml(preset)}</code></div>"
                runJs("appendHtmlToLast(${jsStr(html)})")
            }
            return
        }
        if (cmdLine.startsWith("effort ")) {
            val effort = cmdLine.removePrefix("effort ").trim()
            if (effort.isNotEmpty()) {
                currentEffort = effort
                ReasonixCli.writeConfig("reasoningEffort", effort)
                sessionId = null
                setThinking(false)
                val html = "<div style=\"padding:6px 0;color:var(--text2);font-size:12px\">✓ Effort switched to <code>${escHtml(effort)}</code></div>"
                runJs("appendHtmlToLast(${jsStr(html)})")
            }
            return
        }
        if (cmdLine.startsWith("mode ")) {
            val mode = cmdLine.removePrefix("mode ").trim()
            if (mode.isNotEmpty()) {
                currentMode = mode
                ReasonixCli.writeConfig("editMode", mode)
                sessionId = null
                setThinking(false)
                buildAndSendStatusInfo()
                val html = "<div style=\"padding:6px 0;color:var(--text2);font-size:12px\">✓ Mode switched to <code>${escHtml(mode)}</code></div>"
                runJs("appendHtmlToLast(${jsStr(html)})")
            }
            return
        }
        if (cmdLine == "clear") {
            sessionId = null
            setThinking(false)
            runJs("clearChat()")
            return
        }
        if (cmdLine == "help") {
            val html = "<div style=\"padding:8px 0;color:var(--text2);font-size:12px;line-height:1.8\"><b>Available commands:</b><br>/model &lt;name&gt; — Switch model (deepseek-v4-flash, deepseek-v4-pro)<br>/preset &lt;name&gt; — Switch preset (auto, flash, pro)<br>/mode &lt;mode&gt;  — Switch mode (review, auto, yolo)<br>/effort &lt;lvl&gt; — Set effort (low, medium, high, max)<br>/clear &mdash; Clear chat and start new session<br>/help &mdash; Show this help<br>/setup &mdash; Run interactive setup<br>/doctor &mdash; Run system diagnostics<br>/update &mdash; Update Reasonix CLI</div>"
            runJs("appendHtmlToLast(${jsStr(html)})")
            return
        }
        // External reasonix commands
        setThinking(true)
        Thread({
            val (code, output) = ReasonixCli.runWithExit(*cmdLine.split(" ").toTypedArray())
            val display = if (output.isBlank()) "(no output)" else escHtml(output)
            val html = "<pre style=\"background:var(--code-bg);color:var(--code-text);padding:10px;border-radius:6px;font:12px/1.45 monospace;white-space:pre-wrap;margin:4px 0;\">$display</pre>" +
                       "<div style=\"font-size:10px;color:var(--text3);margin-top:2px\">exit code: $code</div>"
            SwingUtilities.invokeLater {
                setThinking(false)
                runJs("appendHtmlToLast(${jsStr(html)})")
            }
        }, "rx-cmd").start()
    }

    // ── Messaging ──

    private fun doSend(text: String, ctx: String?) {
        setThinking(true)
        // Track input tokens (~4 chars per token)
        val inputText = text + (ctx ?: "")
        trackedInputChars += inputText.length
        ctxTokens = ((trackedInputChars + trackedOutputChars) / 4).coerceAtMost(ctxCap)
        buildAndSendStatusInfo()
        val sid = sessionId
        val future = if (sid == null) {
            client.newSession(project.basePath ?: System.getProperty("user.home"), currentModel)
                .thenCompose { sessionId = it.sessionId; client.sendPrompt(text, ctx, currentMode, currentModel, currentEffort) }
        } else {
            client.sendPrompt(text, ctx, currentMode, currentModel, currentEffort)
        }
        future.thenAccept {
            SwingUtilities.invokeLater { onPromptDone(it.stopReason) }
        }.exceptionally {
            SwingUtilities.invokeLater { onError(it.message ?: "Unknown error") }; null
        }
    }

    private fun setThinking(v: Boolean) {
        thinking = v
        if (v) runJs("setThinking(true)") else runJs("onDone()")
    }

    private fun onPromptDone(stopReason: String) {
        setThinking(false)
        refreshProjectFiles()
    }

    private fun refreshProjectFiles() {
        val basePath = project.basePath ?: return
        Thread({
            try {
                Thread.sleep(500)
                val dir = java.io.File(basePath)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
                vf?.refresh(false, false)
                SwingUtilities.invokeLater {
                    VirtualFileManager.getInstance().asyncRefresh()
                }
            } catch (_: Exception) {}
        }, "rx-file-refresh").start()
    }

    private fun onError(msg: String) {
        setThinking(false)
        runJs("onError(${jsStr(msg)})")
    }

    // ── ACP Callback ──

    private inner class AcpCallback : AcpClientCallbacks {
        override fun onUpdate(sessionId: String, update: SessionUpdate) {
            SwingUtilities.invokeLater {
                when (update) {
                    is MessageChunk -> {
                        trackedOutputChars += update.content.length
                        ctxTokens = ((trackedInputChars + trackedOutputChars) / 4).coerceAtMost(ctxCap)
                        runJs("appendChunk(${jsStr(update.content)})")
                    }
                    is ThoughtChunk -> runJs("showThoughtChunk(${jsStr(update.content)})")
                    is ToolCall -> {
                        toolCalls[update.toolCallId] = update.title
                        if (update.rawInput != null) {
                            cachedToolInputs[update.toolCallId] = update.rawInput
                        }
                        runJs("startToolCall(${jsStr(update.toolCallId)}, ${jsStr(update.title)})")
                    }
                    is ToolCallUpdate -> runJs("updateToolCall(${jsStr(update.toolCallId)}, ${jsStr(update.status)}, ${jsStr(update.content ?: "")})")
                    is PlanUpdate -> {}
                    is SessionDone -> {
                        pollApiStats()
                        onPromptDone(update.stopReason)
                    }
                    is SessionError -> onError(update.error)
                }
            }
        }

        override fun onPermission(request: PermissionRequest) {
            when (currentMode) {
                "review" -> {
                    if (isFileEditTool(request)) {
                        handleFileEditInReview(request)
                    } else {
                        // Show webview permission card for non-file-edit tools
                        SwingUtilities.invokeLater {
                            runJs("showPermission(${jsStr(request.callbackId)}, ${jsStr(request.toolTitle)}, ${jsStr(GSON.toJson(request.options))})")
                        }
                    }
                }
                "auto", "yolo" -> {
                    client.respondPermission(request.callbackId, "allow_once")
                }
            }
        }

        override fun onStatus(connected: Boolean, info: String?) {
            if (connected) {
                currentMode = ReasonixCli.getEditMode()
                startApiClient()
                updateStatusBar()
            }
            if (!connected && thinking) SwingUtilities.invokeLater { setThinking(false) }
        }

        override fun onStderr(line: String) {
            parseStatusLine(line)
        }
    }

    // ── Status Info ──

    private fun updateStatusBar() {
        buildAndSendStatusInfo()
    }

    private fun parseStatusLine(line: String) {
        var changed = false
        // Match cache percentage: "cache 45%"
        val cacheMatch = Regex("cache\\s+(\\d+)%").find(line)
        if (cacheMatch != null) {
            currentCachePct = cacheMatch.groupValues[1]
            changed = true
        }
        // Match cost left: "left¥32.78" or "left¥32.21"
        val costMatch = Regex("[lL]eft[¥￥]([\\d.]+)").find(line)
        if (costMatch != null) {
            currentCostLeft = costMatch.groupValues[1]
            changed = true
        }
        // Match cost spent: "¥0.57 spent"
        val spentMatch = Regex("[¥￥]([\\d.]+)\\s*spent").find(line)
        if (spentMatch != null) {
            currentCostSpent = spentMatch.groupValues[1]
            changed = true
        }
        // Match branch: "· main"
        val branchMatch = Regex("·\\s+(\\S+)").find(line)
        if (branchMatch != null) {
            currentBranch = branchMatch.groupValues[1]
            changed = true
        }
        // Match context usage: "ctx 18%" or "ctx ██░░ 18% · 182K/1000K"
        val ctxPctMatch = Regex("ctx.*?(\\d+)%").find(line)
        if (ctxPctMatch != null) {
            val pct = ctxPctMatch.groupValues[1].toIntOrNull()
            if (pct != null) {
                ctxTokens = (ctxCap * pct / 100).coerceAtMost(ctxCap)
            }
            // Also try to extract token counts: "182K/1000K"
            val tokensMatch = Regex("(\\d+)K/(\\d+)K").find(line)
            if (tokensMatch != null) {
                val used = tokensMatch.groupValues[1].toLongOrNull()
                val cap = tokensMatch.groupValues[2].toLongOrNull()
                if (used != null && cap != null) {
                    ctxTokens = used * 1000
                    ctxCap = cap * 1000
                }
            }
            changed = true
        }
        // Match mode info: "auto · deepseek · deepseek-v4-flash"
        if (line.contains("·") && cacheMatch == null && costMatch == null && spentMatch == null && ctxPctMatch == null) {
            val parts = line.split("·").map { it.trim() }
            if (parts.size >= 3) {
                val mode = parts[0].lowercase()
                val model = parts.drop(2).joinToString(" ").trim()
                currentMode = mode
                currentModel = model
                changed = true
            }
        }
        if (changed) {
            buildAndSendStatusInfo()
        }
    }

    private fun buildAndSendStatusInfo() {
        val info = buildString {
            append("{")
            append("\"mode\":${jsStr(currentMode)}")
            append(",\"model\":${jsStr(currentModel)}")
            append(",\"preset\":${jsStr(currentPreset)}")
            append(",\"effort\":${jsStr(currentEffort)}")
            if (currentCachePct != null) append(",\"cachePct\":${jsStr(currentCachePct!!)}")
            append(",\"costUsd\":${currentCostUsd ?: 0.0}")
            if (currentBalance != null) append(",\"balance\":${jsStr(currentBalance!!)}")
            if (currentCostLeft != null) append(",\"costLeft\":${jsStr(currentCostLeft!!)}")
            if (currentCostSpent != null) append(",\"costSpent\":${jsStr(currentCostSpent!!)}")
            if (currentBranch != null) append(",\"branch\":${jsStr(currentBranch!!)}")
            // Context usage — always send so info bar shows
            val pct = if (ctxTokens > 0) (ctxTokens * 100 / ctxCap).coerceAtMost(100).toInt() else 0
            append(",\"ctxPct\":$pct")
            append(",\"ctxUsed\":$ctxTokens")
            append(",\"ctxCap\":$ctxCap")
            append("}")
        }
        SwingUtilities.invokeLater { runJs("setStatusInfo(${jsStr(info)})") }
    }

    // ── Editor Selection ──

    private fun checkSelection() {
        val code = selectedCode()
        val file = selectedFileName()
        if (code == null) {
            if (lastSelection != null) {
                lastSelection = null; selectedFile = null
                runJs("clearCodeContext()")
            }
            return
        }
        if (code == lastSelection && file == selectedFile) return
        lastSelection = code; selectedFile = file
        val f = file ?: "unknown"
        runJs("setCodeContext(${jsStr(f)}, ${code.lines().size}, ${code.length}, ${jsStr(code)})")
    }

    private fun selectedCode(): String? = try {
        val t = FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText
        if (t.isNullOrBlank()) null else t.trim()
    } catch (_: Exception) { null }

    private fun selectedFileName(): String? = try {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        FileDocumentManager.getInstance().getFile(editor.document)?.name
    } catch (_: Exception) { null }

    // ── Manage Reasonix (was Settings) ──

    private fun showManageReasonix() {
        val path = ReasonixCli.detect()
        val rxConfig = ReasonixCli.readConfig()
        val editMode = rxConfig.get("editMode")?.asString ?: "—"
        val preset = rxConfig.get("preset")?.asString ?: "—"
        val apiKey = rxConfig.get("apiKey")?.asString ?: ""
        val apiKeyMasked = if (apiKey.length > 8) "${apiKey.take(4)}****${apiKey.takeLast(4)}" else if (apiKey.isNotEmpty()) "****" else "—"
        val version = ReasonixCli.run("version").getOrNull() ?: "—"

        val statusText = if (path != null) "✓ Installed at $path" else "✗ Reasonix CLI not found"

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.border.EmptyBorder(12, 12, 12, 12)
            fun addRow(label: String, value: String) {
                val p = JPanel(BorderLayout(8, 2))
                val lbl = JLabel(label).apply {
                    font = font.deriveFont(java.awt.Font.BOLD); foreground = UIManager.getColor("Label.foreground")
                }
                p.add(lbl, BorderLayout.WEST)
                p.add(JLabel(value).apply { font = font.deriveFont(java.awt.Font.PLAIN) }, BorderLayout.CENTER)
                add(p); add(Box.createVerticalStrut(4))
            }
            addRow("Status:", statusText)
            addRow("Version:", version)
            addRow("Mode:", editMode)
            addRow("Preset:", preset)
            addRow("API Key:", apiKeyMasked)
        }

        val options = mutableListOf("Close", "⚙ Setup", "🔍 Doctor", "🔄 Update")
        val choice = JOptionPane.showOptionDialog(
            this, panel, "Reasonix Manager",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
            options.toTypedArray(), options[0]
        )
        when (choice) {
            1 -> execCommand("/setup")
            2 -> execCommand("/doctor")
            3 -> execCommand("/update")
        }
    }

    // ── Config (gui-config.json, legacy) ──

    private fun loadConfig(key: String): String {
        val cfgFile = java.io.File(System.getProperty("user.home"), ".reasonix/gui-config.json")
        if (!cfgFile.exists()) return ""
        return try { com.google.gson.JsonParser.parseString(cfgFile.readText()).asJsonObject.get(key)?.asString ?: "" }
        catch (_: Exception) { "" }
    }

    private fun saveConfig(key: String, value: String) {
        val cfgDir = java.io.File(System.getProperty("user.home"), ".reasonix")
        cfgDir.mkdirs()
        val cfgFile = java.io.File(cfgDir, "gui-config.json")
        val obj = try { com.google.gson.JsonParser.parseString(cfgFile.readText()).asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
        obj.addProperty(key, value)
        cfgFile.writeText(GSON.toJson(obj))
    }

    // ── Helpers ──

    private fun jsStr(s: String): String = GSON.toJson(s)

    private fun escHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ── Interactive Diff (Review Mode) ──

    /** Check if a permission request is for a file-edit tool. */
    private fun isFileEditTool(request: PermissionRequest): Boolean {
        val rawInput = request.rawInput ?: return false
        return rawInput.contains("\"filePath\"", ignoreCase = true)
    }

    /** Handle a file-edit permission in review mode: show interactive diff. */
    private fun handleFileEditInReview(request: PermissionRequest) {
        Thread({
            try {
                val rawObj = JsonParser.parseString(request.rawInput).asJsonObject
                val filePath = rawObj.get("filePath")?.asString ?: run {
                    client.respondPermission(request.callbackId, "deny"); return@Thread
                }

                val file = File(filePath)
                val isNew = !file.exists()
                val currentContent = if (!isNew) file.readText() else ""

                val proposedContent = buildProposedContent(currentContent, rawObj) ?: run {
                    client.respondPermission(request.callbackId, "deny"); return@Thread
                }

                val tabName = "Review: ${file.name}"
                val diffRequest = InteractiveDiffRequest(
                    filePath = filePath,
                    originalContent = currentContent,
                    newContent = proposedContent,
                    tabName = tabName,
                    isNewFile = isNew,
                    readOnly = true
                )

                SwingUtilities.invokeLater {
                    InteractiveDiffManager.showInteractiveDiff(project, diffRequest)
                        .thenAccept { result ->
                            when (result) {
                                is DiffResult.Apply -> {
                                    client.respondPermission(request.callbackId, "allow_once")
                                }
                                else -> { // Reject or Dismiss
                                    client.respondPermission(request.callbackId, "deny")
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                client.respondPermission(request.callbackId, "deny")
            }
        }, "rx-diff-review").apply { isDaemon = true }.start()
    }

    /** Build the proposed file content from rawInput JSON. */
    private fun buildProposedContent(currentContent: String, rawObj: com.google.gson.JsonObject): String? {
        // Write/Create: rawInput has "content" with full new content
        if (rawObj.has("content")) return rawObj.get("content").asString

        // Edit: rawInput has "oldString" and "newString" (single replacement)
        if (rawObj.has("oldString") && rawObj.has("newString")) {
            val oldS = rawObj.get("oldString").asString
            val newS = rawObj.get("newString").asString
            return currentContent.replaceFirst(oldS, newS)
        }
        return null
    }

    companion object {
        private val GSON = Gson()
    }
}
