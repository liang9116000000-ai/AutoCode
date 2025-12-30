package com.example.copilot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.editor.Editor
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.*

data class Message(val role: String, val content: String)
data class DeepSeekRequest(
    val model: String = "deepseek-chat",
    val messages: List<Message>,
    val max_tokens: Int = 4096,
    val temperature: Double = 0.7,
    val stream: Boolean = true
)

class CopilotAction : AnAction("DeepSeek Code") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }
        val fileContent = if (selectedText == null) editor?.document?.text else null
        val codeContext = selectedText ?: fileContent

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val panel = project.getUserData(COPILOT_PANEL_KEY)
            panel?.setEditorContext(editor, codeContext, selectedText != null)
            panel?.focusInput()
        }
    }
}

private const val TOOL_WINDOW_ID = "DeepSeek Code"
private val COPILOT_PANEL_KEY = Key.create<CopilotToolWindowPanel>("com.example.copilot.panel")

class CopilotToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CopilotToolWindowPanel(project)
        project.putUserData(COPILOT_PANEL_KEY, panel)
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class CopilotToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val engine = DeepSeekEngine()
    private val chatPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor(Color(252, 252, 253), Color(28, 30, 33))
    }
    private val inputArea = JBTextArea()
    private val statusLabel = JLabel()

    @Volatile private var selectionContext: String? = null
    @Volatile private var currentEditor: Editor? = null
    @Volatile private var lastGeneratedCode: String? = null
    @Volatile private var isSelection: Boolean = false

    init {
        background = JBColor(Color(252, 252, 253), Color(28, 30, 33))

        // 顶部
        val headerPanel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(252, 252, 253), Color(28, 30, 33))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(230, 232, 236), Color(50, 52, 56))),
                JBUI.Borders.empty(10, 16)
            )
            add(JLabel("✨ DeepSeek Code").apply { font = Font("Microsoft YaHei", Font.BOLD, 14) }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // 聊天区域
        add(JBScrollPane(chatPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        // 底部输入
        add(createInputPanel(), BorderLayout.SOUTH)

        refreshStatus()
        addMessage("系统", "👋 欢迎！输入指令修改代码")
    }

    private fun createInputPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(252, 252, 253), Color(28, 30, 33))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(Color(230, 232, 236), Color(50, 52, 56))),
                JBUI.Borders.empty(12, 16)
            )
        }
        inputArea.apply {
            rows = 2
            lineWrap = true
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            border = JBUI.Borders.empty(8)
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); doSend() }
                }
            })
        }
        panel.add(JBScrollPane(inputArea).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
            preferredSize = Dimension(0, 60)
        }, BorderLayout.CENTER)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply { isOpaque = false }
        btnPanel.add(JButton("清空").apply { addActionListener { clearChat() } })
        btnPanel.add(JButton("发送").apply { addActionListener { doSend() } })
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun addMessage(sender: String, content: String) {
        val icon = when (sender) { "你" -> "👤"; "DeepSeek" -> "🤖"; "系统" -> "ℹ️"; else -> "•" }
        val label = JTextArea("$icon $sender: $content").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            foreground = JBColor(Color(30, 41, 59), Color(220, 225, 235))
            border = JBUI.Borders.empty(8, 16)
        }
        chatPanel.add(label)
        chatPanel.revalidate()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, chatPanel) as? JScrollPane)
                ?.verticalScrollBar?.let { it.value = it.maximum }
        }
    }

    private fun clearChat() { chatPanel.removeAll(); chatPanel.revalidate(); addMessage("系统", "👋 已清空") }

    fun setEditorContext(editor: Editor?, text: String?, isSelectedText: Boolean) {
        currentEditor = editor
        selectionContext = text?.takeIf { it.isNotBlank() }
        isSelection = isSelectedText
        selectionContext?.let { addMessage("系统", "📋 已获取${if (isSelectedText) "选中" else "文件"}代码（${it.lines().size}行）") }
    }

    fun focusInput() { SwingUtilities.invokeLater { inputArea.requestFocusInWindow() } }

    private fun refreshStatus() {
        val online = engine.hasApiKey()
        statusLabel.text = if (online) "🟢" else "🔴"
        statusLabel.foreground = if (online) JBColor(Color(34, 197, 94), Color(74, 222, 128)) else JBColor.RED
    }

    private fun doSend() {
        val text = inputArea.text?.trim().orEmpty()
        if (text.isEmpty()) return
        inputArea.text = ""
        if (selectionContext == null) {
            FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                currentEditor = editor
                val sel = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
                selectionContext = sel ?: editor.document.text
                isSelection = sel != null
            }
        }
        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        addMessage("你", text)
        val ctx = selectionContext
        selectionContext = null

        // 创建思考块
        val thinkingBlock = createThinkingBlock()
        chatPanel.add(thinkingBlock)
        chatPanel.revalidate()

        // 创建回复区域
        val responseArea = JTextArea("🤖 DeepSeek: ").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            foreground = JBColor(Color(30, 41, 59), Color(220, 225, 235))
            border = JBUI.Borders.empty(8, 16)
        }

        val startTime = System.currentTimeMillis()
        val thinkingHeader = thinkingBlock.getClientProperty("header") as JLabel
        val thinkingContent = thinkingBlock.getClientProperty("content") as JTextArea

        ApplicationManager.getApplication().executeOnPooledThread {
            val response = StringBuilder()
            val thinking = StringBuilder()
            var responseAdded = false

            try {
                engine.modifyCodeStream(text, ctx) { chunk, isThinking ->
                    if (isThinking) {
                        thinking.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            val elapsed = String.format("%.1f", (System.currentTimeMillis() - startTime) / 1000.0)
                            thinkingHeader.text = "🧠 思考中 (${elapsed}秒) ▼"
                            thinkingContent.text = thinking.toString()
                            thinkingContent.isVisible = true
                            scrollToBottom()
                        }
                    } else {
                        response.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            if (!responseAdded) {
                                responseAdded = true
                                // 折叠思考
                                val elapsed = String.format("%.1f", (System.currentTimeMillis() - startTime) / 1000.0)
                                thinkingHeader.text = "🧠 已深度思考 ${elapsed}秒 ▶"
                                thinkingContent.isVisible = false
                                chatPanel.add(responseArea)
                                chatPanel.revalidate()
                            }
                            responseArea.text = "� DeepSeekr: $response"
                            scrollToBottom()
                        }
                    }
                }
            } catch (e: Exception) {
                response.append("❌ 错误: ${e.message}")
            }

            ApplicationManager.getApplication().invokeLater {
                // 如果没有思考内容，移除思考块
                if (thinking.isEmpty()) {
                    chatPanel.remove(thinkingBlock)
                    chatPanel.revalidate()
                }
                // 如果没有添加回复区域
                if (!responseAdded && response.isNotEmpty()) {
                    chatPanel.add(responseArea)
                    responseArea.text = "🤖 DeepSeek: $response"
                    chatPanel.revalidate()
                }
                scrollToBottom()

                // Diff
                val code = extractCodeBlock(response.toString())
                if (code != null && ctx != null) {
                    lastGeneratedCode = code
                    showDiffDialog(ctx, code)
                }
            }
        }
    }

    private fun createThinkingBlock(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 16)
        }
        val innerPanel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(248, 250, 252), Color(40, 44, 52))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(226, 232, 240), Color(60, 64, 72)), 1, true),
                JBUI.Borders.empty(10, 12)
            )
        }
        val header = JLabel("🧠 思考中...").apply {
            font = Font("Microsoft YaHei", Font.BOLD, 12)
            foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val content = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 12)
            foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
            border = JBUI.Borders.empty(8, 0, 0, 0)
            isVisible = false
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                content.isVisible = !content.isVisible
                header.text = header.text.replace("▶", "▼").replace("▼", if (content.isVisible) "▼" else "▶")
                    .let { if (content.isVisible) it.replace("▶", "▼") else it.replace("▼", "▶") }
                panel.revalidate()
                scrollToBottom()
            }
        })
        innerPanel.add(header, BorderLayout.NORTH)
        innerPanel.add(content, BorderLayout.CENTER)
        panel.add(innerPanel, BorderLayout.CENTER)
        panel.putClientProperty("header", header)
        panel.putClientProperty("content", content)
        return panel
    }

    private fun extractCodeBlock(text: String): String? {
        return Regex("```(?:\\w*)?\\r?\\n([\\s\\S]*?)\\r?\\n```").find(text)?.groupValues?.get(1)?.trim()
    }

    private fun showDiffDialog(original: String, modified: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val request = SimpleDiffRequest("DeepSeek 代码对比",
                    DiffContentFactory.getInstance().create(original),
                    DiffContentFactory.getInstance().create(modified), "原始", "修改后")
                val panel = this
                object : com.intellij.openapi.ui.DialogWrapper(project, true) {
                    init { title = "DeepSeek 代码对比"; init() }
                    override fun createCenterPanel(): JComponent {
                        val p = JPanel(BorderLayout())
                        DiffManager.getInstance().createRequestPanel(project, disposable, null).let {
                            it.setRequest(request); p.add(it.component, BorderLayout.CENTER)
                        }
                        p.preferredSize = Dimension(900, 600)
                        return p
                    }
                    override fun createSouthPanel(): JComponent {
                        val btns = JPanel(FlowLayout(FlowLayout.CENTER, 16, 12))
                        btns.add(JButton("✓ 应用").apply { addActionListener { panel.applyCode(modified); close(OK_EXIT_CODE) } })
                        btns.add(JButton("✗ 拒绝").apply { addActionListener { panel.addMessage("系统", "❌ 已拒绝"); close(CANCEL_EXIT_CODE) } })
                        btns.add(JButton("取消").apply { addActionListener { close(CANCEL_EXIT_CODE) } })
                        return btns
                    }
                    override fun createActions(): Array<Action> = emptyArray()
                }.show()
            } catch (e: Exception) { addMessage("系统", "❌ Diff错误: ${e.message}") }
        }
    }

    fun applyCode(code: String) {
        currentEditor?.let { editor ->
            WriteCommandAction.runWriteCommandAction(project) {
                val doc = editor.document; val sel = editor.selectionModel
                when { sel.hasSelection() -> doc.replaceString(sel.selectionStart, sel.selectionEnd, code)
                    isSelection -> doc.insertString(editor.caretModel.offset, code)
                    else -> doc.setText(code) }
            }
            addMessage("系统", "✅ 已应用")
        }
    }
}


class DeepSeekEngine {
    private val gson = Gson()
    fun hasApiKey(): Boolean = DeepSeekSettings.getApiKey().isNotBlank()

    fun modifyCodeStream(userInstruction: String, codeContext: String?, onChunk: (String, Boolean) -> Unit) {
        val apiKey = DeepSeekSettings.getApiKey()
        if (apiKey.isBlank()) { onChunk("⚠️ 请配置 API Key", false); return }

        val settings = DeepSeekSettings.getInstance()
        val systemPrompt = "你是智能代码助手。闲聊简短回复；修改代码时返回完整代码(用```包裹)并说明。"
        val userPrompt = buildString {
            codeContext?.let { append("【代码】\n```\n${it.trim()}\n```\n\n") }
            append("【指令】${userInstruction.trim()}")
        }
        val request = DeepSeekRequest(model = settings.model,
            messages = listOf(Message("system", systemPrompt), Message("user", userPrompt)))

        val conn = URL("${settings.baseUrl.trimEnd('/')}/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.apply {
                instanceFollowRedirects = true; requestMethod = "POST"; doOutput = true; doInput = true
                connectTimeout = 30000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { OutputStreamWriter(it, Charsets.UTF_8).use { w -> w.write(gson.toJson(request)) } }
            if (conn.responseCode != 200) { onChunk("❌ API错误(${conn.responseCode})", false); return }

            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (!line.startsWith("data: ")) return@forEach
                    val json = line.removePrefix("data: ").trim()
                    if (json == "[DONE]" || json.isEmpty()) return@forEach
                    try {
                        val delta = gson.fromJson(json, JsonObject::class.java)
                            ?.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("delta")
                        delta?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotEmpty() }?.let { onChunk(it, true) }
                        delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotEmpty() }?.let { onChunk(it, false) }
                    } catch (_: Exception) {}
                }
            }
        } finally { conn.disconnect() }
    }
}
