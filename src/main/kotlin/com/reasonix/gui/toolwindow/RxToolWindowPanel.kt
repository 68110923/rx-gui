package com.reasonix.gui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.reasonix.gui.runner.ReasonixRunner
import com.reasonix.gui.runner.SetupRunner
import java.awt.*
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
    //  HTML template (Chromium-rendered)
    // ══════════════════════════════════════════════

    private companion object {
        val BASE_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  :root { --bg:#fafafa; --user-bg:#e3f2fd; --ai-bg:#fff; --text:#333; --muted:#999; --bubble-shadow:0 1px 3px rgba(0,0,0,.08); }
  * { margin:0; padding:0; box-sizing:border-box; }
  html,body { height:100%; background:var(--bg); font:13px/1.5 -apple-system,'Segoe UI',sans-serif; color:var(--text); }
  #messages { padding:12px 10px 80px; min-height:100%; display:flex; flex-direction:column; gap:8px; }
  .bubble { max-width:85%; padding:10px 14px; border-radius:12px; line-height:1.55; word-wrap:break-word; white-space:pre-wrap; animation:in .15s ease; }
  @keyframes in { from{opacity:0;transform:translateY(6px)} to{opacity:1;transform:translateY(0)} }
  .bubble.user { align-self:flex-end; background:var(--user-bg); border-bottom-right-radius:4px; box-shadow:var(--bubble-shadow); }
  .bubble.ai { align-self:flex-start; background:var(--ai-bg); border-bottom-left-radius:4px; box-shadow:var(--bubble-shadow); }
  .bubble .time { font-size:10px; color:var(--muted); margin-top:4px; }
  .bubble.thinking { background:var(--ai-bg); color:var(--muted); font-style:italic; }
  pre { background:#263238; color:#eeffff; padding:10px 12px; border-radius:6px; overflow-x:auto; font:12px/1.45 'SF Mono','Fira Code',monospace; margin:6px 0; }
  code { background:#eceff1; color:#c62828; padding:1px 5px; border-radius:3px; font-size:12px; }
  pre code { background:transparent; color:#eeffff; padding:0; }
  p { margin:4px 0; }
  ul,ol { margin:4px 0; padding-left:18px; }
  h1,h2,h3 { margin:8px 0 4px; font-weight:600; }
  a { color:#1976d2; }
  .welcome { color:var(--muted); text-align:center; padding:20px 0; }
  .welcome h2 { font-size:18px; margin-bottom:6px; }
  #banner { position:fixed; top:0; left:0; right:0; background:#fff3e0; color:#607d8b; font-size:11px; padding:4px 12px; display:none; z-index:10; border-bottom:1px solid #ffcc80; }
</style>
<script>
function E(tag,cls,html){
  var e=document.createElement(tag);
  if(cls)e.className=cls;
  if(html)e.innerHTML=html;
  return e;
}
function addMessage(role,html){
  var d=E('div','bubble '+role,html);
  var t=new Date().toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});
  d.appendChild(E('div','time',t));
  document.getElementById('messages').appendChild(d);
  window.scrollTo(0,document.body.scrollHeight);
}
function showThinking(){
  var t=document.getElementById('thinking');
  if(!t){
    t=E('div','bubble ai thinking','Thinking …');
    t.id='thinking';
    document.getElementById('messages').appendChild(t);
  }
  window.scrollTo(0,document.body.scrollHeight);
}
function hideThinking(){
  var t=document.getElementById('thinking');
  if(t){t.remove();}
}
function clearMessages(){
  document.getElementById('messages').innerHTML='';
}
function addWelcome(){
  if(document.getElementById('messages').children.length>0) return;
  addMessage('ai','<h2>👋 Welcome to RX GUI</h2><p>Select code, type a question, press <b>Enter</b>.<br>Click ⚙ to configure.</p>');
}
</script>
</head>
<body>
<div id="banner"></div>
<div id="messages"><div class="bubble ai"><h2>👋 Welcome to RX GUI</h2><p>Select code, type a question, press <b>Enter</b>.<br>Click ⚙ to configure.</p><div class="time">--:--</div></div></div>
</body>
</html>""".trimIndent()
    }

    // ══════════════════════════════════════════════
    //  Chat View (web-based)
    // ══════════════════════════════════════════════

    private inner class ChatView : JPanel(BorderLayout()) {

        private val browser = JBCefBrowser()
        private val inputArea = JTextArea(3, 20)
        private val sendButton = JButton("Send")
        private val selectedBanner = JLabel()
        private var thinking = false
        private val runner = ReasonixRunner()

        init {
            browser.loadHTML(BASE_HTML)
            buildUI()
            bindActions()
            // 等浏览器加载完再渲染欢迎消息
            javax.swing.Timer(800) { runJs("addWelcome()") }.apply { isRepeats = false }.start()
        }

        private fun buildUI() {
            // 顶部
            val top = JPanel(BorderLayout()).apply { border = MatteBorder(0, 0, 1, 0, Color(0xD0D0D0)) }
            val dot = JLabel("●").apply { foreground = Color(0x4CAF50); font = Font(name, Font.PLAIN, 12) }
            val projLbl = JLabel(project.name).apply { font = Font(name, Font.PLAIN, 11); foreground = Color(0x999) }
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)); left.add(dot); left.add(projLbl)

            val settingsBtn = flatBtn("\u2699", 14).apply { toolTipText = "Settings"; addActionListener { cardLayout.show(cardPanel, "settings") } }
            val clearBtn = flatBtn("Clear", 11).apply { addActionListener { runJs("clearMessages()") } }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2)); right.add(clearBtn); right.add(settingsBtn)
            top.add(left, BorderLayout.WEST); top.add(right, BorderLayout.EAST)
            add(top, BorderLayout.NORTH)

            // WebView
            add(browser.component, BorderLayout.CENTER)

            // 底部
            val bottom = JPanel(BorderLayout())
            selectedBanner.apply {
                isVisible = false; font = Font("Monospaced", Font.PLAIN, 11); foreground = Color(0x607D8B)
                border = BorderFactory.createCompoundBorder(MatteBorder(1, 0, 0, 0, Color(0xFFCC80)), EmptyBorder(4, 10, 4, 10))
                background = Color(0xFFF3E0); isOpaque = true
            }
            bottom.add(selectedBanner, BorderLayout.NORTH)

            val inputWrap = JPanel(BorderLayout(6, 0)).apply {
                border = BorderFactory.createCompoundBorder(MatteBorder(1, 0, 0, 0, Color(0xD0D0D0)), EmptyBorder(8, 8, 8, 8))
            }
            inputArea.apply { lineWrap = true; wrapStyleWord = true; font = Font("Monospaced", Font.PLAIN, 12)
                border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color(0xCCCCCC)), EmptyBorder(6, 8, 6, 8)) }
            val inputScroll = JScrollPane(inputArea).apply { preferredSize = Dimension(380, 52) }
            inputWrap.add(inputScroll, BorderLayout.CENTER); inputWrap.add(sendButton, BorderLayout.EAST)
            sendButton.isEnabled = false
            bottom.add(inputWrap, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH)
        }

        private fun flatBtn(text: String, size: Int) = JButton(text).apply { font = Font(name, Font.PLAIN, size); isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(2, 8, 2, 8) }

        private fun bindActions() {
            inputArea.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send")
            inputArea.inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "newline")
            inputArea.actionMap.put("send", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { send() } })
            inputArea.actionMap.put("newline", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { inputArea.append("\n") } })
            sendButton.addActionListener { send() }
            inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { sendButton.isEnabled = !thinking && inputArea.text.isNotBlank() }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { sendButton.isEnabled = !thinking && inputArea.text.isNotBlank() }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
            })
            javax.swing.Timer(500) { checkSelection() }.start()
        }

        private fun addWelcome() { runJs("addWelcome()") }

        private fun send() {
            val input = inputArea.text.trim()
            if (input.isEmpty() || thinking) return
            val ctx = getCodeCtx()
            val prompt = if (ctx != null) "Code:\n\n$ctx\n\n---\nUser: $input" else input
            runJs("addMessage('user',${json(markdownToHtml(input))})")
            inputArea.text = ""
            selectedBanner.isVisible = false
            showThinking()
            thinking = true; sendButton.isEnabled = false
            runner.run(prompt) { result ->
                SwingUtilities.invokeLater {
                    hideThinking()
                    thinking = false; sendButton.isEnabled = inputArea.text.isNotBlank()
                    runJs("addMessage('ai',${json(markdownToHtml(result))})")
                }
            }
        }

        private fun runJs(js: String) {
            browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
        }

        private fun showThinking() { runJs("showThinking()") }
        private fun hideThinking() { runJs("hideThinking()") }

        private fun json(s: String): String {
            val escaped = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
                .replace("\u0000", "").replace("</script>", "<\\/script>")
            return "'$escaped'"
        }

        private fun markdownToHtml(s: String): String {
            var h = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            h = Regex("```(\\w*)\n([\\s\\S]*?)```").replace(h) { "<pre>${it.groupValues[2].trimEnd()}</pre>" }
            h = Regex("`([^`]+)`").replace(h) { "<code>${it.groupValues[1]}</code>" }
            h = Regex("\\*\\*(.+?)\\*\\*").replace(h) { "<b>${it.groupValues[1]}</b>" }
            h = Regex("\\*(.+?)\\*").replace(h) { "<i>${it.groupValues[1]}</i>" }
            h = Regex("(?m)^### (.+)$").replace(h) { "<h3>${it.groupValues[1]}</h3>" }
            h = Regex("(?m)^## (.+)$").replace(h) { "<h2>${it.groupValues[1]}</h2>" }
            h = Regex("(?m)^# (.+)$").replace(h) { "<h1>${it.groupValues[1]}</h1>" }
            h = h.replace("\n\n", "<br><br>").replace("\n", "<br>")
            return h
        }

        // ── 选区 ──

        private fun checkSelection() {
            val code = selectedCode()
            if (code != null) {
                val f = selectedFile() ?: "unknown"
                val preview = if (code.length > 60) code.take(60) + "…" else code
                selectedBanner.text = "  📎 $f (${code.lines().size} lines) — $preview"
                selectedBanner.isVisible = true
            } else selectedBanner.isVisible = false
        }

        private fun selectedCode(): String? = try {
            val t = FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText
            if (t.isNullOrBlank()) null else t.trim()
        } catch (_: Exception) { null }

        private fun selectedFile(): String? = try {
            FileDocumentManager.getInstance().getFile(FileEditorManager.getInstance(project).selectedTextEditor?.document!!)?.name
        } catch (_: Exception) { null }

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
            return "```$lang ($file)\n$code\n```"
        }
    }

    // ══════════════════════════════════════════════
    //  Settings (unchanged)
    // ══════════════════════════════════════════════

    private class SettingsView(private val onBack: () -> Unit) : JPanel(BorderLayout()) {
        private val contentStack = JPanel(CardLayout())
        private val corePage = CorePage()
        private val aboutPage = AboutPage()

        init {
            val topBar = JPanel(BorderLayout()).apply { border = MatteBorder(0, 0, 1, 0, Color(0xD0D0D0)) }
            topBar.add(JButton("← Chat").apply { font = Font(name, Font.PLAIN, 12); isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(2, 8, 2, 8); addActionListener { onBack() } }, BorderLayout.WEST)
            topBar.add(JLabel("Settings").apply { font = Font(name, Font.BOLD, 12); border = EmptyBorder(0, 12, 0, 0) }, BorderLayout.CENTER)
            add(topBar, BorderLayout.NORTH)

            val menu = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = MatteBorder(0, 0, 0, 1, Color(0xD0D0D0)); preferredSize = Dimension(40, 1) }
            menu.add(Box.createVerticalStrut(8))
            menu.add(menuItem("\uD83D\uDD0C", "Core") { (contentStack.layout as CardLayout).show(contentStack, "core") })
            menu.add(Box.createVerticalStrut(2))
            menu.add(menuItem("\u2139\uFE0F", "About") { (contentStack.layout as CardLayout).show(contentStack, "about") })
            menu.add(Box.createVerticalGlue())

            contentStack.add(corePage, "core"); contentStack.add(aboutPage, "about")
            val body = JPanel(BorderLayout()); body.add(menu, BorderLayout.WEST); body.add(contentStack, BorderLayout.CENTER)
            add(body, BorderLayout.CENTER)
        }

        private fun menuItem(emoji: String, tip: String, action: () -> Unit) = JButton(emoji).apply { toolTipText = tip; font = Font(name, Font.PLAIN, 14); isOpaque = false; isContentAreaFilled = false; border = EmptyBorder(8, 0, 8, 0); maximumSize = Dimension(40, 36); alignmentX = Component.CENTER_ALIGNMENT; addActionListener { action() } }
    }

    // ══════════════════════════════════════════════
    //  Core page
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
            statusLabel.font = Font(name, Font.PLAIN, 13); statusLabel.alignmentX = 0f; box.add(statusLabel)
            box.add(Box.createVerticalStrut(14))
            installBtn.apply { font = Font(name, Font.PLAIN, 13); alignmentX = 0f; maximumSize = Dimension(240, 34); addActionListener { doInstall() } }
            box.add(installBtn); box.add(Box.createVerticalStrut(20))
            terminal.apply { isEditable = false; font = Font("Monospaced", Font.PLAIN, 11); background = Color(0x263238); foreground = Color(0xEEFFFF); alignmentX = 0f }
            box.add(JScrollPane(terminal).apply { preferredSize = Dimension(300, 180); maximumSize = Dimension(Int.MAX_VALUE, 250); alignmentX = 0f })
            add(box, BorderLayout.CENTER)
            refresh()
        }

        private fun refresh() { Thread { val ok = runner.isInstalled(); val ver = runner.getVersion(); SwingUtilities.invokeLater { if (ok) { statusLabel.text = "✅ Reasonix is installed — $ver"; statusLabel.foreground = Color(0x388E3C); installBtn.text = "Already installed"; installBtn.isEnabled = false; terminal.text = ver } else { statusLabel.text = "⚠️ Reasonix CLI not found"; statusLabel.foreground = Color(0xE65100); installBtn.text = "Install Reasonix"; installBtn.isEnabled = true; terminal.text = "" } } }.start() }

        private fun doInstall() { installBtn.isEnabled = false; terminal.append("> npm install -g reasonix\n"); Thread { val r = runner.install { l -> SwingUtilities.invokeLater { terminal.append("  $l\n") } }; SwingUtilities.invokeLater { terminal.append(if (r.success) "✅ Done!\n" else "❌ ${r.output}\n"); terminal.append("──────────\n"); refresh() } }.start() }
    }

    // ══════════════════════════════════════════════
    //  About page
    // ══════════════════════════════════════════════

    private class AboutPage : JPanel(BorderLayout()) {
        init {
            border = EmptyBorder(24, 24, 16, 24)
            val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            box.add(JLabel("About RX GUI").apply { font = Font(name, Font.BOLD, 18) })
            box.add(Box.createVerticalStrut(14))
            val info = javax.swing.JEditorPane("text/html", "<html><body style='font-size:13px'><p><b>RX GUI</b> v0.1.0</p><p style='margin-top:6px'>Reasonix AI assistant.</p><p style='margin-top:6px;color:#888'>https://reasonix.io</p><p style='margin-top:12px;color:#888;font-size:11px'>MIT</p></body></html>").apply { isEditable = false; isOpaque = false; preferredSize = Dimension(300, 160) }
            box.add(info)
            add(box, BorderLayout.CENTER)
        }
    }
}
