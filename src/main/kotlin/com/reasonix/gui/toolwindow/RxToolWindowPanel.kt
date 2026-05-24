package com.reasonix.gui.toolwindow

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.reasonix.gui.runner.ReasonixRunner
import com.reasonix.gui.runner.SetupRunner
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder

class RxToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tabbedPane = JTabbedPane()
    private val chatPanel = ChatPanel(project)
    private val setupPanel = SetupPanel()

    init {
        tabbedPane.addTab("Chat", chatPanel)
        tabbedPane.addTab("Setup", setupPanel)
        setupPanel.checkInstallation()
        add(tabbedPane, BorderLayout.CENTER)
        preferredSize = Dimension(420, 600)
    }

    // ═══════════════════════════════════════════════════════
    //  Chat Panel
    // ═══════════════════════════════════════════════════════

    private class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

        // ── 数据 ──
        private val messages = mutableListOf<ChatMessage>()

        // ── UI 组件 ──
        private val statusLabel = JLabel("● Ready")
        private val projectLabel = JLabel()
        private val clearButton = JButton("Clear")

        private val messageContainer = JPanel()
        private val messageScrollPane = JScrollPane(messageContainer)

        private val selectedCodeBanner = JLabel()
        private val inputField = JTextField()
        private val sendButton = JButton("Send")

        private var thinkingPanel: JPanel? = null
        private var isThinking = false

        // ── Runner ──
        private val runner = ReasonixRunner()

        // ── 消息模型 ──
        private enum class ChatRole { USER, ASSISTANT }
        private data class ChatMessage(val role: ChatRole, val content: String)

        init {
            setupUI()
            setupActions()
            addWelcomeMessage()
        }

        private fun setupUI() {
            preferredSize = Dimension(420, 600)

            // ── 顶部信息栏 ──
            val infoBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 5))
            infoBar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE0E0E0))

            statusLabel.foreground = Color(0x4CAF50)
            statusLabel.font = Font(statusLabel.font.name, Font.PLAIN, 12)

            projectLabel.text = project.name
            projectLabel.font = Font(projectLabel.font.name, Font.PLAIN, 12)

            clearButton.font = Font(clearButton.font.name, Font.PLAIN, 11)
            clearButton.isOpaque = false
            clearButton.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)

            infoBar.add(statusLabel)
            infoBar.add(projectLabel)
            infoBar.add(Box.createHorizontalGlue())
            infoBar.add(clearButton)
            add(infoBar, BorderLayout.NORTH)

            // ── 中间消息列表 ──
            messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
            messageContainer.background = Color(0xFAFAFA)
            messageScrollPane.border = null
            messageScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            messageScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            add(messageScrollPane, BorderLayout.CENTER)

            // ── 底部输入区 ──
            val southPanel = JPanel(BorderLayout())

            selectedCodeBanner.isVisible = false
            selectedCodeBanner.font = Font("Monospaced", Font.PLAIN, 11)
            selectedCodeBanner.foreground = Color(0x607D8B)
            selectedCodeBanner.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
            )
            selectedCodeBanner.background = Color(0xFFF3E0)
            selectedCodeBanner.isOpaque = true
            southPanel.add(selectedCodeBanner, BorderLayout.NORTH)

            val inputPanel = JPanel(BorderLayout(6, 0))
            inputPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
            )
            inputField.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            sendButton.isEnabled = false
            inputPanel.add(inputField, BorderLayout.CENTER)
            inputPanel.add(sendButton, BorderLayout.EAST)

            southPanel.add(inputPanel, BorderLayout.SOUTH)
            add(southPanel, BorderLayout.SOUTH)
        }

        private fun setupActions() {
            sendButton.addActionListener { _: ActionEvent -> sendMessage() }
            inputField.addActionListener { _: ActionEvent -> sendMessage() }

            inputField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateSendButton()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateSendButton()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateSendButton()
                private fun updateSendButton() {
                    sendButton.isEnabled = inputField.text.isNotBlank() && !isThinking
                }
            })

            clearButton.addActionListener { _: ActionEvent ->
                messageContainer.removeAll()
                messages.clear()
                addWelcomeMessage()
                messageContainer.revalidate()
                messageContainer.repaint()
            }

            val timer = Timer(500) { checkSelectedCode() }
            timer.start()
        }

        // ── 消息处理 ──

        private fun addWelcomeMessage() {
            addAssistantMessage("""
                <h3>👋 Welcome to RX GUI</h3>
                <p>
                <b>How to use:</b><br>
                1. Select code in your editor (optional)<br>
                2. Type your question below<br>
                3. Click <b>Send</b> or press <b>Enter</b>
                </p>
                <p style='color:#888; font-size:11px;'>
                Make sure Reasonix is configured: check the <b>Setup</b> tab.
                </p>
            """.trimIndent())
        }

        private fun sendMessage() {
            val userInput = inputField.text.trim()
            if (userInput.isEmpty() || isThinking) return

            val context = getCodeContext()
            val fullPrompt = if (context != null) {
                "Here is the selected code:\n\n$context\n\n---\nUser question: $userInput"
            } else {
                userInput
            }

            addUserMessage(userInput)
            inputField.text = ""
            selectedCodeBanner.isVisible = false

            showThinking()

            runner.run(fullPrompt) { result ->
                SwingUtilities.invokeLater {
                    hideThinking()
                    addAssistantMessage(result)
                }
            }
        }

        private fun addUserMessage(text: String) {
            val bubble = createMessageBubble(text, isUser = true)
            messageContainer.add(bubble)
            messages.add(ChatMessage(ChatRole.USER, text))
            scrollToBottom()
        }

        private fun addAssistantMessage(text: String) {
            val bubble = createMessageBubble(text, isUser = false)
            messageContainer.add(bubble)
            messages.add(ChatMessage(ChatRole.ASSISTANT, text))
            scrollToBottom()
        }

        private fun showThinking() {
            isThinking = true
            sendButton.isEnabled = false

            val bubble = JPanel(FlowLayout(FlowLayout.LEFT))
            val label = JLabel("Thinking")
            label.font = Font(label.font.name, Font.ITALIC, 12)
            label.foreground = Color(0x9E9E9E)
            label.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)
            )
            bubble.add(label)
            bubble.isOpaque = false
            thinkingPanel = bubble

            messageContainer.add(bubble)
            scrollToBottom()
        }

        private fun hideThinking() {
            isThinking = false
            thinkingPanel?.let {
                messageContainer.remove(it)
                messageContainer.revalidate()
                messageContainer.repaint()
            }
            thinkingPanel = null
            sendButton.isEnabled = inputField.text.isNotBlank()
        }

        // ── 消息气泡渲染 ──

        private fun createMessageBubble(rawText: String, isUser: Boolean): JPanel {
            val html = renderMarkdown(rawText)

            val contentPane = JEditorPane("text/html", html)
            contentPane.isEditable = false
            contentPane.isOpaque = true
            contentPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

            val bgColor: Color
            val borderColor: Color
            val align: Int

            if (isUser) {
                bgColor = Color(0xE3F2FD)
                borderColor = Color(0x90CAF9)
                align = FlowLayout.RIGHT
            } else {
                bgColor = Color(0xF5F5F5)
                borderColor = Color(0xE0E0E0)
                align = FlowLayout.LEFT
            }

            contentPane.background = bgColor
            contentPane.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )

            val maxWidth = 380
            contentPane.preferredSize = Dimension(maxWidth, contentPane.preferredSize.height.coerceAtLeast(30))

            val styledHtml = """
                <html><head>
                <style>
                    body { font-family: -apple-system, 'Segoe UI', sans-serif; font-size: 13px; margin: 0; padding: 0; }
                    p { margin: 4px 0; }
                    pre { background: #263238; color: #EEFFFF; padding: 10px; border-radius: 4px; overflow-x: auto; font-size: 12px; }
                    code { background: #ECEFF1; color: #C62828; padding: 1px 4px; border-radius: 2px; font-size: 12px; }
                    pre code { background: transparent; color: #EEFFFF; padding: 0; }
                    h1,h2,h3 { margin: 8px 0 4px 0; }
                    ul,ol { margin: 4px 0; padding-left: 20px; }
                    li { margin: 2px 0; }
                </style></head>
                <body>
            """.trimIndent() + contentPane.text
                .removePrefix("<html>").removePrefix("<head>").removePrefix("</head>")
                .removePrefix("<body>").removeSuffix("</body>").removeSuffix("</html>") +
                "</body></html>"

            contentPane.text = styledHtml

            val outerPanel = object : JPanel(FlowLayout(align)) {
                override fun getMaximumSize(): Dimension {
                    return Dimension(Int.MAX_VALUE, preferredSize.height)
                }
            }
            outerPanel.isOpaque = false
            outerPanel.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            outerPanel.add(contentPane)

            val labelHeight = contentPane.preferredSize.height + 14
            outerPanel.preferredSize = Dimension(outerPanel.preferredSize.width, labelHeight)

            return outerPanel
        }

        // ── Markdown 渲染 ──

        private fun renderMarkdown(text: String): String {
            var html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            html = html.replace(Regex("```(\\w*)\n([\\s\\S]*?)```")) { "<pre>${it.groupValues[2].trimEnd()}</pre>" }
            html = html.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
            html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            html = html.replace(Regex("\\*(.+?)\\*")) { "<i>${it.groupValues[1]}</i>" }
            html = html.replace(Regex("\\[(.+?)\\]\\((.+?)\\)")) { "<a href='${it.groupValues[2]}'>${it.groupValues[1]}</a>" }
            html = html.replace(Regex("(?m)^### (.+)$")) { "<h3>${it.groupValues[1]}</h3>" }
            html = html.replace(Regex("(?m)^## (.+)$")) { "<h2>${it.groupValues[1]}</h2>" }
            html = html.replace(Regex("(?m)^# (.+)$")) { "<h1>${it.groupValues[1]}</h1>" }
            html = html.replace("\n\n", "<br><br>")
            html = html.replace("\n", "<br>")

            return "<html>$html</html>"
        }

        // ── 代码选择上下文 ──

        private fun checkSelectedCode() {
            val code = getSelectedCode()
            if (code != null) {
                val file = getSelectedFile() ?: "unknown"
                val preview = if (code.length > 80) code.take(80) + "..." else code
                selectedCodeBanner.text = "  📎 Selected: $file (${code.lines().size} lines)  —  $preview"
                selectedCodeBanner.isVisible = true
            } else {
                selectedCodeBanner.isVisible = false
            }
        }

        private fun getSelectedCode(): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val text = editor.selectionModel.selectedText
            return if (text.isNullOrBlank()) null else text.trim()
        }

        private fun getSelectedFile(): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            return FileDocumentManager.getInstance().getFile(editor.document)?.name
        }

        private fun getCodeContext(): String? {
            val code = getSelectedCode() ?: return null
            val file = getSelectedFile() ?: "unknown"
            val lang = codeLanguage(file)
            return "```$lang (from $file)\n$code\n```"
        }

        private fun codeLanguage(filename: String): String = when {
            filename.endsWith(".py") -> "python"
            filename.endsWith(".kt") -> "kotlin"
            filename.endsWith(".java") -> "java"
            filename.endsWith(".ts") -> "typescript"
            filename.endsWith(".js") -> "javascript"
            filename.endsWith(".go") -> "go"
            filename.endsWith(".rs") -> "rust"
            filename.endsWith(".rb") -> "ruby"
            filename.endsWith(".c") || filename.endsWith(".h") -> "c"
            filename.endsWith(".cpp") || filename.endsWith(".hpp") -> "cpp"
            filename.endsWith(".swift") -> "swift"
            filename.endsWith(".sh") -> "bash"
            filename.endsWith(".html") -> "html"
            filename.endsWith(".css") -> "css"
            filename.endsWith(".sql") -> "sql"
            else -> ""
        }

        private fun scrollToBottom() {
            messageContainer.revalidate()
            messageContainer.repaint()
            SwingUtilities.invokeLater {
                val bar = messageScrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Setup Panel
    // ═══════════════════════════════════════════════════════

    private class SetupPanel : JPanel(BorderLayout()) {

        private val statusArea = JTextArea()
        private val installButton = JButton("Install / Update Reasonix")
        private val apiKeyField = JTextField()
        private val saveKeyButton = JButton("Save API Key")
        private val checkLabel = JLabel("Checking...")

        private val setupRunner = SetupRunner()

        init {
            setupUI()
            setupActions()
        }

        fun checkInstallation() {
            Thread {
                val installed = setupRunner.isInstalled()
                val hasKey = setupRunner.hasApiKey()
                SwingUtilities.invokeLater {
                    checkLabel.text = if (installed) "✅ Reasonix is installed" else "⚠️ Reasonix not installed"
                    checkLabel.foreground = if (installed) Color(0x388E3C) else Color(0xE65100)
                    installButton.text = if (installed) "Update Reasonix" else "Install Reasonix"
                    apiKeyField.text = if (hasKey) "•••••••• (configured)" else ""
                }
            }
        }

        private fun setupUI() {
            preferredSize = Dimension(420, 600)
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

            // ── 安装区 ──
            val installSection = JPanel(BorderLayout(0, 8))
            installSection.border = TitledBorder("1. Install Reasonix CLI")
            installSection.maximumSize = Dimension(Int.MAX_VALUE, 120)

            val installDesc = JLabel("<html><body style='font-size:11px'>" +
                "Runs <code>npm install -g reasonix</code>. Node.js is required.<br>" +
                "执行 <code>npm install -g reasonix</code>，需要 Node.js。" +
                "</body></html>")
            installDesc.verticalAlignment = SwingConstants.TOP

            val installBtnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            installBtnPanel.add(installButton)
            installBtnPanel.add(checkLabel)
            checkLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
            checkLabel.font = Font(checkLabel.font.name, Font.PLAIN, 12)

            installSection.add(installDesc, BorderLayout.NORTH)
            installSection.add(installBtnPanel, BorderLayout.SOUTH)

            // ── API Key 配置区 ──
            val keySection = JPanel(BorderLayout(0, 8))
            keySection.border = TitledBorder("2. Configure API Key / 配置 API Key")
            keySection.maximumSize = Dimension(Int.MAX_VALUE, 90)

            val keyDesc = JLabel("<html><body style='font-size:11px'>" +
                "Sets <code>reasonix.apiKey</code> via <code>reasonix config set</code>.<br>" +
                "通过 <code>reasonix config set reasonix.apiKey &lt;key&gt;</code> 写入配置。" +
                "</body></html>")

            val keyInputPanel = JPanel(BorderLayout(6, 0))
            apiKeyField.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            apiKeyField.toolTipText = "Paste your Reasonix API key here"
            keyInputPanel.add(apiKeyField, BorderLayout.CENTER)
            keyInputPanel.add(saveKeyButton, BorderLayout.EAST)

            keySection.add(keyDesc, BorderLayout.NORTH)
            keySection.add(keyInputPanel, BorderLayout.SOUTH)

            // ── 状态输出区 ──
            val statusSection = JPanel(BorderLayout(0, 4))
            statusSection.border = TitledBorder("Status / 状态输出")

            statusArea.isEditable = false
            statusArea.font = Font("Monospaced", Font.PLAIN, 11)
            statusArea.background = Color(0x263238)
            statusArea.foreground = Color(0xEEFFFF)
            statusArea.text = "Ready.\n"

            val statusScroll = JScrollPane(statusArea)
            statusScroll.preferredSize = Dimension(380, 200)

            statusSection.add(statusScroll, BorderLayout.CENTER)

            content.add(installSection)
            content.add(Box.createVerticalStrut(12))
            content.add(keySection)
            content.add(Box.createVerticalStrut(12))
            content.add(statusSection)

            add(content, BorderLayout.CENTER)
        }

        private fun setupActions() {
            installButton.addActionListener { _: ActionEvent -> installReasonix() }
            saveKeyButton.addActionListener { _: ActionEvent -> saveApiKey() }
        }

        private fun installReasonix() {
            installButton.isEnabled = false
            statusArea.append("▶ Installing Reasonix...\n")
            statusArea.append("$ npm install -g reasonix\n")

            Thread {
                val result = setupRunner.install { line ->
                    SwingUtilities.invokeLater { statusArea.append("  $line\n") }
                }
                SwingUtilities.invokeLater {
                    statusArea.append(if (result.success) "✅ Done!\n" else "❌ ${result.output}\n")
                    statusArea.append("─────────────────────────────\n")
                    installButton.isEnabled = true
                    checkInstallation()
                }
            }.start()
        }

        private fun saveApiKey() {
            val key = apiKeyField.text.trim()
            if (key.isEmpty() || key.startsWith("•")) {
                statusArea.append("⚠️ Please enter a valid API key.\n")
                return
            }

            saveKeyButton.isEnabled = false
            statusArea.append("▶ Saving API key...\n")

            Thread {
                val result = setupRunner.setApiKey(key)
                SwingUtilities.invokeLater {
                    statusArea.append(if (result.success) "✅ API key saved.\n" else "❌ ${result.output}\n")
                    statusArea.append("─────────────────────────────\n")
                    apiKeyField.text = "•••••••• (configured)"
                    saveKeyButton.isEnabled = true
                }
            }.start()
        }
    }
}
