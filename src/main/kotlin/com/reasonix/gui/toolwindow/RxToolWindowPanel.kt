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

/**
 * 主面板 — CardLayout 切换 Chat 和 Settings 视图。
 */
class RxToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val chatView = ChatView()
    private val settingsView = SettingsView { cardLayout.show(cardPanel, "chat") }

    init {
        cardPanel.add(chatView, "chat")
        cardPanel.add(settingsView, "settings")
        cardLayout.show(cardPanel, "chat")
        add(cardPanel, BorderLayout.CENTER)
        preferredSize = Dimension(420, 600)
    }

    fun switchToChat() = cardLayout.show(cardPanel, "chat")
    fun switchToSettings() = cardLayout.show(cardPanel, "settings")

    // ═══════════════════════════════════════════════════════
    //  Chat View
    // ═══════════════════════════════════════════════════════

    private inner class ChatView : JPanel(BorderLayout()) {

        private val messageContainer = JPanel()
        private val messageScroll = JScrollPane(messageContainer)
        private val inputField = JTextField()
        private val sendButton = JButton("Send")
        private val selectedBanner = JLabel()
        private var thinkingPanel: JPanel? = null
        private var thinking = false
        private val runner = ReasonixRunner()

        init {
            setupUI()
            setupActions()
            addWelcome()
        }

        private fun setupUI() {
            // ── 顶部栏 ──
            val topBar = JPanel(BorderLayout())
            topBar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE0E0E0))

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
            val statusDot = JLabel("●").apply {
                foreground = Color(0x4CAF50)
                font = Font(name, Font.PLAIN, 12)
            }
            val title = JLabel("RX GUI").apply { font = Font(name, Font.BOLD, 13) }
            val proj = JLabel(project.name).apply {
                font = Font(name, Font.PLAIN, 11)
                foreground = Color(0x9E9E9E)
            }
            left.add(statusDot)
            left.add(title)
            left.add(proj)

            val settingsBtn = JButton("⚙").apply {
                isOpaque = false
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                font = Font(name, Font.PLAIN, 14)
                toolTipText = "Settings / 设置"
                addActionListener { switchToSettings() }
            }
            val clearBtn = JButton("Clear").apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                font = Font(name, Font.PLAIN, 11)
                addActionListener {
                    messageContainer.removeAll()
                    addWelcome()
                    messageContainer.revalidate()
                    messageContainer.repaint()
                }
            }

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4))
            right.add(clearBtn)
            right.add(settingsBtn)

            topBar.add(left, BorderLayout.WEST)
            topBar.add(right, BorderLayout.EAST)
            add(topBar, BorderLayout.NORTH)

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
                isVisible = false
                font = Font("Monospaced", Font.PLAIN, 11)
                foreground = Color(0x607D8B)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE0E0E0)),
                    EmptyBorder(4, 10, 4, 10)
                )
                background = Color(0xFFF3E0)
                isOpaque = true
            }
            bottom.add(selectedBanner, BorderLayout.NORTH)

            val inputPanel = JPanel(BorderLayout(6, 0))
            inputPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE0E0E0)),
                EmptyBorder(8, 8, 8, 8)
            )
            inputField.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xCCCCCC)),
                EmptyBorder(6, 8, 6, 8)
            )
            sendButton.isEnabled = false
            inputPanel.add(inputField, BorderLayout.CENTER)
            inputPanel.add(sendButton, BorderLayout.EAST)
            bottom.add(inputPanel, BorderLayout.SOUTH)

            add(bottom, BorderLayout.SOUTH)
        }

        private fun setupActions() {
            inputField.addActionListener { send() }
            sendButton.addActionListener { send() }
            inputField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                private fun updateSend() { sendButton.isEnabled = inputField.text.isNotBlank() && !thinking }
            })
            Timer(500) { checkSelection() }.start()
        }

        private fun addWelcome() {
            addAssistantMsg("<h3>👋 Welcome to RX GUI</h3><p>Select code, type a question, click <b>Send</b>.<br>Settings → ⚙ top-right</p>")
        }

        private fun send() {
            val input = inputField.text.trim()
            if (input.isEmpty() || thinking) return

            val ctx = getCodeCtx()
            val prompt = if (ctx != null) "Selected code:\n\n$ctx\n\n---\nUser: $input" else input

            addUserMsg(input)
            inputField.text = ""
            selectedBanner.isVisible = false
            showThinking()

            runner.run(prompt) { result ->
                SwingUtilities.invokeLater {
                    hideThinking()
                    addAssistantMsg(result)
                }
            }
        }

        private fun addUserMsg(text: String) = addBubble(text, true)
        private fun addAssistantMsg(text: String) = addBubble(text, false)

        private fun addBubble(text: String, user: Boolean) {
            val html = renderMd(text)
            val pane = JEditorPane("text/html", html).apply {
                isEditable = false
                isOpaque = true
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                background = if (user) Color(0xE3F2FD) else Color(0xF5F5F5)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(if (user) Color(0x90CAF9) else Color(0xE0E0E0), 1),
                    EmptyBorder(8, 12, 8, 12)
                )
                preferredSize = Dimension(360, preferredSize.height.coerceAtLeast(30))
            }

            val styled = """
                <html><head><style>
                body { font-family: -apple-system, 'Segoe UI', sans-serif; font-size: 13px; margin: 0; padding: 0; }
                p { margin: 4px 0; }
                pre { background: #263238; color: #EEFFFF; padding: 10px; border-radius: 4px; overflow-x: auto; font-size: 12px; }
                code { background: #ECEFF1; color: #C62828; padding: 1px 4px; border-radius: 2px; font-size: 12px; }
                pre code { background: transparent; color: #EEFFFF; padding: 0; }
                h1,h2,h3 { margin: 8px 0 4px 0; }
                ul,ol { margin: 4px 0; padding-left: 20px; }
                li { margin: 2px 0; }
            </style></head><body>${
                pane.text
                    .replace("<html>", "").replace("</html>", "")
                    .replace("<head>", "").replace("</head>", "")
                    .replace("<body>", "").replace("</body>", "")
            }</body></html>
            """.trimIndent()
            pane.text = styled

            val outer = object : JPanel(FlowLayout(if (user) FlowLayout.RIGHT else FlowLayout.LEFT)) {
                override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            outer.isOpaque = false
            outer.border = EmptyBorder(6, 10, 6, 10)
            outer.add(pane)

            messageContainer.add(outer)
            scrollDown()
        }

        private fun showThinking() {
            thinking = true
            sendButton.isEnabled = false
            val p = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false }
            val l = JLabel("Thinking").apply {
                font = Font(name, Font.ITALIC, 12)
                foreground = Color(0x9E9E9E)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0xE0E0E0)),
                    EmptyBorder(8, 14, 8, 14)
                )
            }
            p.add(l)
            thinkingPanel = p
            messageContainer.add(p)
            scrollDown()
        }

        private fun hideThinking() {
            thinking = false
            thinkingPanel?.let { messageContainer.remove(it); messageContainer.revalidate(); messageContainer.repaint() }
            thinkingPanel = null
            sendButton.isEnabled = inputField.text.isNotBlank()
        }

        private fun scrollDown() {
            messageContainer.revalidate()
            messageContainer.repaint()
            SwingUtilities.invokeLater { messageScroll.verticalScrollBar.value = messageScroll.verticalScrollBar.maximum }
        }

        // ── Markdown ──

        private fun renderMd(s: String): String {
            var h = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            h = h.replace(Regex("```(\\w*)\n([\\s\\S]*?)```")) { "<pre>${it.groupValues[2].trimEnd()}</pre>" }
            h = h.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
            h = h.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            h = h.replace(Regex("\\*(.+?)\\*")) { "<i>${it.groupValues[1]}</i>" }
            h = h.replace(Regex("\\[(.+?)\\]\\((.+?)\\)")) { "<a href='${it.groupValues[2]}'>${it.groupValues[1]}</a>" }
            h = h.replace(Regex("(?m)^### (.+)$")) { "<h3>${it.groupValues[1]}</h3>" }
            h = h.replace(Regex("(?m)^## (.+)$")) { "<h2>${it.groupValues[1]}</h2>" }
            h = h.replace(Regex("(?m)^# (.+)$")) { "<h1>${it.groupValues[1]}</h1>" }
            h = h.replace("\n\n", "<br><br>").replace("\n", "<br>")
            return "<html>$h</html>"
        }

        // ── 选区 ──

        private fun checkSelection() {
            val code = selectedCode()
            if (code != null) {
                val f = selectedFile() ?: "unknown"
                val prev = if (code.length > 60) code.take(60) + "..." else code
                selectedBanner.text = "  📎 $f (${code.lines().size} lines) — $prev"
                selectedBanner.isVisible = true
            } else selectedBanner.isVisible = false
        }

        private fun selectedCode(): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val t = editor.selectionModel.selectedText
            return if (t.isNullOrBlank()) null else t.trim()
        }

        private fun selectedFile(): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            return FileDocumentManager.getInstance().getFile(editor.document)?.name
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
                else -> ""
            }
            return "```$lang (from $file)\n$code\n```"
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Settings View
    // ═══════════════════════════════════════════════════════

    private class SettingsView(private val onBack: () -> Unit) : JPanel(BorderLayout()) {

        private val contentPanel = JPanel(CardLayout())
        private val corePage = CorePage()
        private val aboutPage = AboutPage()

        init {
            // 顶部返回栏
            val top = JPanel(BorderLayout()).apply {
                border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE0E0E0))
            }
            val backBtn = JButton("← Chat").apply {
                isOpaque = false
                border = EmptyBorder(2, 8, 2, 8)
                font = Font(name, Font.PLAIN, 12)
                addActionListener { onBack() }
            }
            top.add(backBtn, BorderLayout.WEST)
            add(top, BorderLayout.NORTH)

            // 左侧图标菜单 + 右侧内容
            val body = JPanel(BorderLayout())

            // 左侧菜单 (icon only)
            val menu = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createMatteBorder(0, 0, 0, 1, Color(0xE0E0E0))
                background = Color(0xF5F5F5)
                preferredSize = Dimension(44, 400)
                maximumSize = Dimension(44, Int.MAX_VALUE)
            }

            val coreBtn = menuIcon("🔌", "Core Config / 核心配置", active = true) {
                (contentPanel.layout as CardLayout).show(contentPanel, "core")
                // highlight active
            }
            val aboutBtn = menuIcon("ℹ", "About / 关于") {
                (contentPanel.layout as CardLayout).show(contentPanel, "about")
            }
            menu.add(Box.createVerticalStrut(12))
            menu.add(coreBtn)
            menu.add(aboutBtn)
            menu.add(Box.createVerticalGlue())

            body.add(menu, BorderLayout.WEST)

            // 右侧内容
            contentPanel.add(corePage, "core")
            contentPanel.add(aboutPage, "about")
            (contentPanel.layout as CardLayout).show(contentPanel, "core")
            body.add(contentPanel, BorderLayout.CENTER)

            add(body, BorderLayout.CENTER)
        }

        private fun menuIcon(emoji: String, tooltip: String, active: Boolean = false, action: () -> Unit): JButton {
            return JButton(emoji).apply {
                font = Font(name, Font.PLAIN, 16)
                isOpaque = true
                isContentAreaFilled = true
                background = if (active) Color(0xE3F2FD) else Color(0xF5F5F5)
                border = EmptyBorder(10, 0, 10, 0)
                alignmentX = Component.CENTER_ALIGNMENT
                maximumSize = Dimension(44, 44)
                toolTipText = tooltip
                addActionListener { action() }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Core Config Page
    // ═══════════════════════════════════════════════════════

    private class CorePage : JPanel(BorderLayout()) {

        private val runner = SetupRunner()
        private val title = JLabel()
        private val status = JLabel()
        private val installBtn = JButton()
        private val terminal = JTextArea()
        private var installed = false

        init {
            border = EmptyBorder(20, 24, 16, 24)

            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

            title.apply {
                font = Font(name, Font.BOLD, 18)
                text = "RX Core"
                alignmentX = Component.LEFT_ALIGNMENT
            }
            box.add(title)
            box.add(Box.createVerticalStrut(12))

            status.apply {
                font = Font(name, Font.PLAIN, 13)
                text = "Checking..."
                alignmentX = Component.LEFT_ALIGNMENT
            }
            box.add(status)
            box.add(Box.createVerticalStrut(16))

            installBtn.apply {
                font = Font(name, Font.PLAIN, 13)
                isVisible = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(200, 36)
                addActionListener { doInstall() }
            }
            box.add(installBtn)
            box.add(Box.createVerticalStrut(20))

            terminal.apply {
                isEditable = false
                font = Font("Monospaced", Font.PLAIN, 11)
                background = Color(0x263238)
                foreground = Color(0xEEFFFF)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 200)
                text = ""
            }
            val scroll = JScrollPane(terminal).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 200)
            }
            box.add(scroll)

            add(box, BorderLayout.NORTH)
            check()
        }

        private fun check() {
            Thread {
                installed = runner.isInstalled()
                val ver = if (installed) " — " + runner.getVersion() else ""
                SwingUtilities.invokeLater {
                    if (installed) {
                        status.text = "✅ Reasonix is installed$ver"
                        status.foreground = Color(0x388E3C)
                        installBtn.isVisible = false
                        terminal.text = "> reasonix version\n${runner.getVersion()}\n"
                    } else {
                        status.text = "⚠️ Reasonix CLI not installed"
                        status.foreground = Color(0xE65100)
                        installBtn.text = "Install Reasonix"
                        installBtn.isVisible = true
                        terminal.text = ""
                    }
                }
            }.start()
        }

        private fun doInstall() {
            installBtn.isEnabled = false
            terminal.append("> npm install -g reasonix\n")
            Thread {
                val r = runner.install { line ->
                    SwingUtilities.invokeLater { terminal.append("  $line\n") }
                }
                SwingUtilities.invokeLater {
                    terminal.append(if (r.success) "✅ Done!\n" else "❌ ${r.output}\n")
                    terminal.append("────────────────────\n")
                    installBtn.isEnabled = true
                    check()
                }
            }.start()
        }
    }

    // ═══════════════════════════════════════════════════════
    //  About Page
    // ═══════════════════════════════════════════════════════

    private class AboutPage : JPanel(BorderLayout()) {

        init {
            border = EmptyBorder(20, 24, 16, 24)

            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

            box.add(JLabel("About RX GUI").apply { font = Font(name, Font.BOLD, 18) })
            box.add(Box.createVerticalStrut(12))

            val info = JEditorPane("text/html", """
                <html><body style='font-family:-apple-system,sans-serif;font-size:13px'>
                <p><b>RX GUI</b> v0.1.0</p>
                <p>Reasonix AI assistant for JetBrains IDEs.</p>
                <p>授权证 / License: MIT</p>

                <p style='margin-top:12px'>Chat with Reasonix in the side panel.<br>
                Select code to ask questions about it.</p>
                <p style='margin-top:12px;color:#888'>https://reasonix.io</p>
                </body></html>
            """.trimIndent()).apply {
                isEditable = false
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(300, 200)
            }
            box.add(info)

            add(box, BorderLayout.NORTH)
        }
    }
}
