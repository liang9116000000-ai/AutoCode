package com.example.copilot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.*
import javax.swing.border.EmptyBorder

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

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val panel = project.getUserData(COPILOT_PANEL_KEY)
            panel?.setEditorContext(editor, selectedText)
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
        background = JBColor.background()
    }
    private val inputArea = JBTextArea()
    private val sendButton = JButton("发送")
    private val applyButton = JButton("✓ 应用代码")
    private val clearButton = JButton("清空")
    private val statusLabel = JLabel()

    @Volatile private var selectionContext: String? = null
    @Volatile private var currentEditor: Editor? = null
    @Volatile private var lastGeneratedCode: String? = null

    init {
        background = JBColor.background()
        border = JBUI.Borders.empty(4)

        // 顶部状态栏
        val headerPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(4, 8)
            add(JLabel("🤖 DeepSeek Auto").apply {
                font = font.deriveFont(Font.BOLD, 13f)
            }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // 聊天区域
        val chatScroll = JBScrollPane(chatPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(chatScroll, BorderLayout.CENTER)

        // 底部输入区域
        val inputPanel = createInputPanel()
        add(inputPanel, BorderLayout.SOUTH)

        refreshStatus()
        addWelcomeMessage()
    }

    private fun createInputPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(8)
        }

        // 输入框样式
        inputArea.apply {
            rows = 3
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(8)
            )
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                        e.consume()
                        doSend()
                    }
                }
            })
        }

        val inputScroll = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, 80)
        }
        panel.add(inputScroll, BorderLayout.CENTER)

        // 按钮区域
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            background = JBColor.background()
        }

        clearButton.apply {
            font = Font("Microsoft YaHei", Font.PLAIN, 12)
            addActionListener { clearChat() }
        }

        applyButton.apply {
            font = Font("Microsoft YaHei", Font.BOLD, 12)
            isEnabled = false
            background = JBColor(Color(76, 175, 80), Color(76, 175, 80))
            addActionListener { applyCodeToEditor() }
        }

        sendButton.apply {
            font = Font("Microsoft YaHei", Font.BOLD, 12)
            background = JBColor(Color(33, 150, 243), Color(33, 150, 243))
            addActionListener { doSend() }
        }

        buttonPanel.add(JLabel("Shift+Enter 换行").apply {
            font = Font("Microsoft YaHei", Font.PLAIN, 11)
            foreground = JBColor.gray
        })
        buttonPanel.add(clearButton)
        buttonPanel.add(applyButton)
        buttonPanel.add(sendButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun addWelcomeMessage() {
        addMessage("系统", "👋 欢迎使用 DeepSeek 代码助手！\n\n" +
                "使用方法：\n" +
                "1. 选中代码后按 Alt+D\n" +
                "2. 输入修改指令（如：添加注释、重构、修复bug）\n" +
                "3. 点击「应用代码」替换选中内容", MessageType.SYSTEM)
    }

    private fun clearChat() {
        chatPanel.removeAll()
        chatPanel.revalidate()
        chatPanel.repaint()
        addWelcomeMessage()
    }

    fun setEditorContext(editor: Editor?, text: String?) {
        currentEditor = editor
        selectionContext = text?.takeIf { it.isNotBlank() }
        val ctx = selectionContext
        if (ctx != null) {
            addMessage("系统", "📋 已获取选中代码（${ctx.lines().size} 行，${ctx.length} 字符）", MessageType.SYSTEM)
        }
        refreshStatus()
    }

    fun focusInput() {
        SwingUtilities.invokeLater { inputArea.requestFocusInWindow() }
    }

    private fun refreshStatus() {
        val online = engine.hasApiKey()
        statusLabel.text = if (online) "🟢 在线" else "🔴 离线"
        statusLabel.foreground = if (online) JBColor(Color(76, 175, 80), Color(76, 175, 80)) else JBColor.RED
    }

    private fun doSend() {
        val text = inputArea.text?.trim().orEmpty()
        if (text.isEmpty()) return
        inputArea.text = ""
        sendUserMessage(text)
    }


    private fun sendUserMessage(text: String) {
        addMessage("你", text, MessageType.USER)
        val ctxSnapshot = selectionContext
        sendButton.isEnabled = false

        // 思考面板（延迟创建，只有收到思考内容才显示）
        var thinkingMsg: JPanel? = null
        var thinkingArea: JTextArea? = null
        var thinkingHeader: JLabel? = null
        var contentPanel: JPanel? = null
        var thinkingTimer: Timer? = null
        var startTime = 0L
        
        // 创建流式消息面板
        val streamMsg = addMessage("DeepSeek", "", MessageType.ASSISTANT)
        val contentArea = streamMsg.components.filterIsInstance<JTextArea>().firstOrNull()

        ApplicationManager.getApplication().executeOnPooledThread {
            val fullResponse = StringBuilder()
            val thinkingContent = StringBuilder()
            try {
                engine.modifyCodeStream(text, ctxSnapshot) { chunk, isThinking ->
                    if (isThinking) {
                        thinkingContent.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            // 首次收到思考内容时创建面板
                            if (thinkingMsg == null) {
                                startTime = System.currentTimeMillis()
                                thinkingMsg = createThinkingPanel()
                                // 插入到 DeepSeek 回复之前
                                val idx = chatPanel.components.indexOf(streamMsg) - 1
                                if (idx >= 0) {
                                    chatPanel.add(thinkingMsg, idx)
                                    chatPanel.add(Box.createVerticalStrut(4), idx + 1)
                                } else {
                                    chatPanel.add(thinkingMsg, chatPanel.componentCount - 2)
                                    chatPanel.add(Box.createVerticalStrut(4), chatPanel.componentCount - 2)
                                }
                                thinkingArea = thinkingMsg?.getClientProperty("contentArea") as? JTextArea
                                thinkingHeader = thinkingMsg?.getClientProperty("headerLabel") as? JLabel
                                contentPanel = thinkingMsg?.getClientProperty("contentPanel") as? JPanel
                                
                                // 启动计时器
                                thinkingTimer = Timer(100) { 
                                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                    thinkingHeader?.text = "🧠 已深度思考 ${String.format("%.1f", elapsed)} 秒"
                                }
                                thinkingTimer?.start()
                            }
                            
                            thinkingArea?.text = thinkingContent.toString()
                            thinkingArea?.caretPosition = thinkingArea?.document?.length ?: 0
                            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, chatPanel) as? JScrollPane
                            scrollPane?.verticalScrollBar?.let { it.value = it.maximum }
                        }
                    } else {
                        fullResponse.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            contentArea?.text = fullResponse.toString()
                            contentArea?.caretPosition = contentArea?.document?.length ?: 0
                            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, chatPanel) as? JScrollPane
                            scrollPane?.verticalScrollBar?.let { it.value = it.maximum }
                        }
                    }
                }
            } catch (ex: Exception) {
                fullResponse.clear()
                fullResponse.append("❌ 请求失败：${ex.message ?: ex::class.java.simpleName}")
                ApplicationManager.getApplication().invokeLater {
                    contentArea?.text = fullResponse.toString()
                }
            }

            ApplicationManager.getApplication().invokeLater {
                thinkingTimer?.stop()
                
                // 如果有思考内容，更新标题并折叠
                if (thinkingContent.isNotEmpty() && thinkingMsg != null) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    thinkingHeader?.text = "🧠 已深度思考 ${String.format("%.1f", elapsed)} 秒 ▶"
                    contentPanel?.isVisible = false
                    thinkingMsg?.revalidate()
                }
                
                sendButton.isEnabled = true
                lastGeneratedCode = extractCodeBlock(fullResponse.toString())
                applyButton.isEnabled = lastGeneratedCode != null && currentEditor != null
            }
        }
    }
    
    private fun createThinkingPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(248, 250, 252), Color(40, 44, 52))
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 8),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(226, 232, 240), Color(60, 64, 72)), 1, true),
                    JBUI.Borders.empty(8)
                )
            )
        }
        
        val headerLabel = JLabel("🧠 思考中...").apply {
            font = Font("Microsoft YaHei", Font.BOLD, 12)
            foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        
        val contentArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = panel.background
            foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
            font = Font("Microsoft YaHei", Font.PLAIN, 12)
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        
        val contentPanel = JPanel(BorderLayout()).apply {
            background = panel.background
            add(contentArea, BorderLayout.CENTER)
            isVisible = true
        }
        
        // 点击标题折叠/展开
        headerLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                contentPanel.isVisible = !contentPanel.isVisible
                val text = headerLabel.text
                headerLabel.text = if (contentPanel.isVisible) {
                    text.replace(" ▶", " ▼").replace("▶", "▼")
                } else {
                    text.replace(" ▼", " ▶").replace("▼", "▶")
                }
                panel.revalidate()
                // 滚动到底部
                SwingUtilities.invokeLater {
                    val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, chatPanel) as? JScrollPane
                    scrollPane?.verticalScrollBar?.let { it.value = it.maximum }
                }
            }
        })
        
        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)
        
        // 存储引用
        panel.putClientProperty("headerLabel", headerLabel)
        panel.putClientProperty("contentArea", contentArea)
        panel.putClientProperty("contentPanel", contentPanel)
        
        return panel
    }

    private fun extractCodeBlock(text: String): String? {
        val regex = Regex("```(?:\\w+)?\\s*\\n([\\s\\S]*?)\\n```")
        return regex.find(text)?.groupValues?.get(1)?.trim()
    }

    private fun applyCodeToEditor() {
        val editor = currentEditor ?: return
        val code = lastGeneratedCode ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val doc = editor.document
            val sel = editor.selectionModel
            if (sel.hasSelection()) {
                doc.replaceString(sel.selectionStart, sel.selectionEnd, code)
            } else {
                doc.insertString(editor.caretModel.offset, code)
            }
        }

        addMessage("系统", "✅ 代码已应用到编辑器", MessageType.SYSTEM)
        applyButton.isEnabled = false
        lastGeneratedCode = null
    }

    private enum class MessageType { USER, ASSISTANT, SYSTEM, THINKING }

    private fun addMessage(sender: String, content: String, type: MessageType): JPanel {
        val msgPanel = JPanel(BorderLayout()).apply {
            background = when (type) {
                MessageType.USER -> JBColor(Color(227, 242, 253), Color(30, 50, 70))
                MessageType.ASSISTANT -> JBColor(Color(232, 245, 233), Color(30, 60, 40))
                MessageType.SYSTEM -> JBColor(Color(255, 243, 224), Color(60, 50, 30))
                MessageType.THINKING -> JBColor(Color(243, 229, 245), Color(50, 30, 60))
            }
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 8),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(8)
                )
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val headerLabel = JLabel(sender).apply {
            font = Font("Microsoft YaHei", Font.BOLD, 12)
            foreground = when (type) {
                MessageType.USER -> JBColor(Color(25, 118, 210), Color(100, 180, 255))
                MessageType.ASSISTANT -> JBColor(Color(56, 142, 60), Color(100, 200, 120))
                MessageType.SYSTEM -> JBColor(Color(245, 124, 0), Color(255, 180, 100))
                MessageType.THINKING -> JBColor(Color(142, 36, 170), Color(200, 130, 220))
            }
        }

        val contentArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = msgPanel.background
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }

        msgPanel.add(headerLabel, BorderLayout.NORTH)
        msgPanel.add(contentArea, BorderLayout.CENTER)

        chatPanel.add(msgPanel)
        chatPanel.add(Box.createVerticalStrut(4))
        chatPanel.revalidate()

        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, chatPanel) as? JScrollPane
            scrollPane?.verticalScrollBar?.let { it.value = it.maximum }
        }

        return msgPanel
    }
}


class DeepSeekEngine {
    private val gson = Gson()

    fun hasApiKey(): Boolean = DeepSeekSettings.getApiKey().isNotBlank()

    fun modifyCodeStream(userInstruction: String, codeContext: String?, onChunk: (String, Boolean) -> Unit) {
        val apiKey = DeepSeekSettings.getApiKey()
        if (apiKey.isBlank()) {
            onChunk(offlineReply(userInstruction, codeContext), false)
            return
        }
        streamModify(userInstruction, codeContext, apiKey, onChunk)
    }

    private fun offlineReply(userText: String, codeContext: String?): String {
        val ctx = codeContext.orEmpty().trim()
        return buildString {
            append("⚠️ 【离线模式】\n\n")
            append("请在 Settings → Tools → DeepSeek Code 中配置 API Key。\n\n")
            if (ctx.isNotBlank()) append("已收到代码上下文（${ctx.lines().size} 行）\n")
            append("你的指令：$userText")
        }
    }

    private fun streamModify(userInstruction: String, codeContext: String?, apiKey: String, onChunk: (String, Boolean) -> Unit) {
        val settings = DeepSeekSettings.getInstance()
        val baseUrl = settings.baseUrl
        val model = settings.model

        val systemPrompt = """你是一个专业的代码助手。用户会提供代码和修改指令，你需要：
1. 理解用户的修改意图
2. 对代码进行相应的修改
3. 返回修改后的完整代码（用代码块包裹）
4. 简要说明你做了哪些修改

注意：保持代码风格一致，只修改必要的部分，确保代码可以正常运行。"""

        val userPrompt = buildString {
            if (!codeContext.isNullOrBlank()) {
                append("【原始代码】\n```\n${codeContext.trim()}\n```\n\n")
            }
            append("【修改指令】\n${userInstruction.trim()}")
        }

        val request = DeepSeekRequest(model = model, messages = listOf(
            Message("system", systemPrompt),
            Message("user", userPrompt)
        ))

        val url = URL("$baseUrl/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "text/event-stream")

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(request)) }

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                onChunk("❌ API 调用失败 ($code): $error", false)
                return
            }

            // 流式读取 SSE
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val data = line ?: continue
                    if (!data.startsWith("data: ")) continue
                    val json = data.removePrefix("data: ").trim()
                    if (json == "[DONE]") break

                    try {
                        val obj = gson.fromJson(json, JsonObject::class.java)
                        val choices = obj?.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val choice = choices.get(0).asJsonObject
                            val delta = choice?.getAsJsonObject("delta")
                            
                            // 检查 reasoning_content（思考过程）
                            if (delta?.has("reasoning_content") == true) {
                                val reasoningContent = delta.get("reasoning_content")
                                if (!reasoningContent.isJsonNull) {
                                    val text = reasoningContent.asString
                                    if (text.isNotEmpty()) {
                                        onChunk(text, true)
                                    }
                                }
                            }
                            
                            // 检查 content（正式回复）
                            if (delta?.has("content") == true) {
                                val content = delta.get("content")
                                if (!content.isJsonNull) {
                                    val text = content.asString
                                    if (text.isNotEmpty()) {
                                        onChunk(text, false)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
