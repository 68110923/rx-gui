package com.reasonix.gui.toolwindow

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.reasonix.gui.runner.ReasonixRunner
import com.reasonix.gui.runner.SetupRunner
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

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

    // ═══════════════════════════════════════════════
    //  Chat
    // ═══════════════════════════════════════════════

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
            setupUI()
            setupActions()
            addWelcome()
        }

        private fun setupUI() {
            // 顶部栏 — 只保留状态和工具按钮
            val topBar = JPanel(BorderLayout())
            topBar.border = MatteBorder(0, 0, 1, 0, Color(0xE0E0E0))

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
            val statusDot = JLabel("●").apply {
                foreground = Color(0x4CAF50)
                font = Font(name, Font.PLAIN, 12)
            }
            val projectName = JLabel(project.name).apply {
                font = Font(name, Font.PLAIN, 11)
                foreground = Color(0x808080)
            }
            left.add(statusDot)
            left.add(projectName)

            // 扁平按钮
            val clearBtn = flatButton("Clear")
            clearBtn.addActionListener {
                messageContainer.removeAll()
                addWelcome()
                messageContainer.revalidate()
                messageContainer.repaint()
            }

            val settingsBtn = flatButton("\u2699") // ⚙
            settingsBtn.font = Font(name, Font.PLAIN, 14)
            settingsBtn.toolTipText = "Settings"
            settingsBtn.addActionListener { cardLayout.show(cardPanel, "settings") }

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2))
            right.add(clearBtn)
            right.add(settingsBtn)

            topBar.add(left, BorderLayout.WEST)
            topBar.add(right, BorderLayout.EAST)
            add(topBar, BorderLayout.NORTH)

            // 消息区
            messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
            messageContainer.background = Color(0xFAFAFA)
            messageScroll.border = null
            messageScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            messageScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            add(messageScroll, BorderLayout.CENTER)

            // 底部
            val bottom = JPanel(BorderLayout())

            selectedBanner.apply {
                isVisible = false
                font = Font("Monospaced", Font.PLAIN, 11)
                foreground = Color(0x607D8B)
                border = BorderFactory.createCompoundBorder(
                    MatteBorder(1, 0, 0, 0, Color(0xFFCC80)),
                    EmptyBorder(4, 10, 4, 10)
                )
                background = Color(0xFFF3E0)
                isOpaque = true
            }
            bottom.add(selectedBanner, BorderLayout.NORTH)

            // 输入区域：JTextArea + scroll，默认 3 行，最高占 80%
            val inputPanel = JPanel(BorderLayout(6, 0))
            inputPanel.border = BorderFactory.createCompoundBorder(
                MatteBorder(1, 0, 0, 0, Color(0xE0E0E0)),
                EmptyBorder(8, 8, 8, 8)
            )

            inputArea.apply {
                lineWrap = true
                wrapStyleWord = true
                font = Font("Monospaced", Font.PLAIN, 12)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0xCCCCCC)),
                    EmptyBorder(6, 8, 6, 8)
                )
                // Ctrl+Enter 发送
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "send")
                actionMap.put("send", object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) = send()
                })
            }

            val inputScroll = JScrollPane(inputArea).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize = Dimension(380, inputArea.preferredSize.height + 4)
            }

            inputPanel.add(inputScroll, BorderLayout.CENTER)
            inputPanel.add(sendButton, BorderLayout.EAST)
            sendButton.isEnabled = false

            bottom.add(inputPanel, BorderLayout.SOUTH)
            add(bottom, BorderLayout.SOUTH)

            // 监听输入框高度变化，限制 80%
            inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = adjustInputHeight()
            })
        }

        private fun adjustInputHeight() {
            val maxH = (this.height * 0.8).toInt().coerceAtLeast(60)
            val pref = inputArea.preferredSize.height
            val newH = pref.coerceAtMost(maxH).coerceAtLeast(50)
            (inputArea.parent as? JScrollPane)?.preferredSize = Dimension(380, newH + 4)
            parent?.revalidate()
        }

        private fun flatButton(text: String) = JButton(text).apply {
            isOpaque = false
            isContentAreaFilled = false
            border = EmptyBorder(4, 6, 4, 6)
            font = Font(name, Font.PLAIN, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        private fun setupActions() {
            sendButton.addActionListener { send() }
            // Enter = send (only if no modifier)
            inputArea.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send-plain")
            inputArea.actionMap.put("send-plain", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = send()
            })

            inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { updateSend() }
                private fun updateSend() {
                    sendButton.isEnabled = inputArea.text.isNotBlank() && !thinking
                }
            })
            Timer(500) { checkSelection() }.start()
        }

        private fun addWelcome() {
            addAssistantMsg("<h3>👋 Welcome to RX GUI</h3><p>Select code, type, click <b>Send</b> or press <b>Enter</b>.<br>Settings → top-right ⚙</p>")
        }

        private fun send() {
            val input = inputArea.text.trim()
            if (input.isEmpty() || thinking) return

            val ctx = getCodeCtx()
            val prompt = if (ctx != null) "Selected code:\n\n$ctx\n\n---\nUser: $input" else input

            addUserMsg(input)
            inputArea.text = ""
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

            val inner = pane.text
                .replace("<html>", "").replace("</html>", "")
                .replace("<head>", "").replace("</head>", "")
                .replace("<body>", "").replace("</body>", "")

            pane.text = """<html><head><style>
                body { font-family:-apple-system,'Segoe UI',sans-serif; font-size:13px; margin:0; padding:0; }
                p { margin:4px 0; }
                pre { background:#263238; color:#EEFFFF; padding:10px; border-radius:4px; overflow-x:auto; font-size:12px; }
                code { background:#ECEFF1; color:#C62828; padding:1px 4px; border-radius:2px; font-size:12px; }
                pre code { background:transparent; color:#EEFFFF; padding:0; }
                h1,h2,h3 { margin:8px 0 4px 0; }
                ul,ol { margin:4px 0; padding-left:20px; }
                li { margin:2px 0; }
            </style></head><body>$inner</body></html>"""

            val outer = JPanel(FlowLayout(if (user) FlowLayout.RIGHT else FlowLayout.LEFT)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                border = EmptyBorder(6, 10, 6, 10)
            }
            outer.add(pane)
            messageContainer.add(outer)
            scrollDown()
        }

        private fun showThinking() {
            thinking = true
            sendButton.isEnabled = false
            val l = JLabel("Thinking").apply {
                font = Font(name, Font.ITALIC, 12)
                foreground = Color(0x9E9E9E)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0xE0E0E0)),
                    EmptyBorder(8, 14, 8, 14)
                )
            }
            thinkingPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false; add(l) }
            messageContainer.add(thinkingPanel)
            scrollDown()
        }

        private fun hideThinking() {
            thinking = false
            thinkingPanel?.let { messageContainer.remove(it); messageContainer.revalidate(); messageContainer.repaint() }
            thinkingPanel = null
            sendButton.isEnabled = inputArea.text.isNotBlank()
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

    // ═══════════════════════════════════════════════
    //  Settings
    // ═══════════════════════════════════════════════

    private class SettingsView(private val onBack: () -> Unit) : JPanel(BorderLayout()) {

        private val contentStack = JPanel(CardLayout())
        private val corePage = CorePage()
        private val aboutPage = AboutPage()

        init {
            // 顶部返回栏
            val top = JPanel(BorderLayout()).apply {
                border = MatteBorder(0, 0, 1, 0, Color(0xE0E0E0))
            }
            top.add(JLabel("  Settings").apply { font = Font(name, Font.BOLD, 13) }, BorderLayout.WEST)
            add(top, BorderLayout.NORTH)

            // 左侧菜单 + 右侧内容
            val body = JPanel(BorderLayout())

            // 左侧图标菜单
            val menu = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = MatteBorder(0, 0, 0, 1, Color(0xE0E0E0))
                background = UIManager.getColor("Panel.background")
                preferredSize = Dimension(42, 1)
            }

            menu.add(Box.createVerticalStrut(8))
            menu.add(menuIcon("🔌", "Core") { showPage("core") })
            menu.add(Box.createVerticalStrut(2))
            menu.add(menuIcon("\u2139", "About") { showPage("about") })
            menu.add(Box.createVerticalGlue())

            body.add(menu, BorderLayout.WEST)

            contentStack.add(corePage, "core")
            contentStack.add(aboutPage, "about")
            body.add(contentStack, BorderLayout.CENTER)

            add(body, BorderLayout.CENTER)
        }

        private fun showPage(name: String) {
            (contentStack.layout as CardLayout).show(contentStack, name)
        }

        private fun menuIcon(emoji: String, tip: String, action: () -> Unit): JButton {
            return JButton(emoji).apply {
                toolTipText = tip
                font = Font(name, Font.PLAIN, 15)
                isOpaque = false
                isContentAreaFilled = false
                border = EmptyBorder(10, 0, 10, 0)
                maximumSize = Dimension(42, 40)
                alignmentX = Component.CENTER_ALIGNMENT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { action() }
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  Core page
    // ═══════════════════════════════════════════════

    private class CorePage : JPanel(BorderLayout()) {

        private val runner = SetupRunner()
        private val statusIcon = JLabel()
        private val statusText = JLabel()
        private val installBtn = JButton()
        private val terminal = JTextArea()
        private var installed = false

        init {
            border = EmptyBorder(24, 24, 16, 24)

            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

            // 标题
            val title = JLabel("RX Core").apply {
                font = Font(name, Font.BOLD, 18)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            box.add(title)
            box.add(Box.createVerticalStrut(16))

            // 状态行
            val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            statusIcon.font = Font(name, Font.PLAIN, 14)
            statusText.font = Font(name, Font.PLAIN, 13)
            statusRow.add(statusIcon)
            statusRow.add(statusText)
            box.add(statusRow)
            box.add(Box.createVerticalStrut(14))

            // 安装按钮
            installBtn.apply {
                font = Font(name, Font.PLAIN, 13)
                isVisible = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(220, 34)
                addActionListener { doInstall() }
            }
            box.add(installBtn)
            box.add(Box.createVerticalStrut(20))

            // 终端输出
            terminal.apply {
                isEditable = false
                font = Font("Monospaced", Font.PLAIN, 11)
                background = Color(0x263238)
                foreground = Color(0xEEFFFF)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val termScroll = JScrollPane(terminal).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(300, 180)
                maximumSize = Dimension(Int.MAX_VALUE, 250)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            box.add(termScroll)

            add(box, BorderLayout.CENTER)
            refresh()
        }

        private fun refresh() {
            Thread {
                installed = runner.isInstalled()
                val ver = if (installed) runner.getVersion() else ""
                SwingUtilities.invokeLater {
                    if (installed) {
                        statusIcon.text = "✅"
                        statusText.text = "Reasonix is installed — $ver"
                        statusText.foreground = Color(0x388E3C)
                        installBtn.text = "Install Reasonix"
                        installBtn.isVisible = true
                        installBtn.isEnabled = false
                        installBtn.toolTipText = "Already installed"
                        terminal.text = "> reasonix version\n$ver\n"
                    } else {
                        statusIcon.text = "⚠️"
                        statusText.text = "Reasonix CLI not installed"
                        statusText.foreground = Color(0xE65100)
                        installBtn.text = "Install Reasonix"
                        installBtn.isVisible = true
                        installBtn.isEnabled = true
                        installBtn.toolTipText = null
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
                    refresh()
                }
            }.start()
        }
    }

    // ═══════════════════════════════════════════════
    //  About page
    // ═══════════════════════════════════════════════

    private class AboutPage : JPanel(BorderLayout()) {

        init {
            border = EmptyBorder(24, 24, 16, 24)

            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

            box.add(JLabel("About RX GUI").apply { font = Font(name, Font.BOLD, 18) })
            box.add(Box.createVerticalStrut(14))

            val info = JEditorPane("text/html", """
                <html><body style='font-family:-apple-system,sans-serif;font-size:13px'>
                <p><b>RX GUI</b> v0.1.0</p>
                <p style='margin-top:6px'>Reasonix AI assistant for JetBrains IDEs.</p>
                <p style='margin-top:6px;color:#888'>https://reasonix.io</p>
                <p style='margin-top:12px;color:#888;font-size:11px'>License: MIT</p>
                </body></html>
            """.trimIndent()).apply {
                isEditable = false
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(300, 160)
            }
            box.add(info)
            add(box, BorderLayout.CENTER)
        }
    }
}
