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
            add(JLabel("DeepSeek Code").apply { font = Font("Microsoft YaHei", Font.BOLD, 14) }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // 聊天区域
        add(JBScrollPane(chatPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        // 底部输入（包含渐变遮罩）
        add(createInputPanel(), BorderLayout.SOUTH)

        refreshStatus()
        addMessage("deepseek", "今天有什么可以帮你")
    }

    private fun createInputPanel(): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = JBColor(Color(252, 252, 253), Color(28, 30, 33))
        }
        
        // 渐变遮罩
        val gradientPanel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                val bgColor = JBColor(Color(252, 252, 253), Color(28, 30, 33))
                val transparent = Color(bgColor.red, bgColor.green, bgColor.blue, 0)
                val gradient = GradientPaint(0f, 0f, transparent, 0f, height.toFloat(), bgColor)
                g2.paint = gradient
                g2.fillRect(0, 0, width, height)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            preferredSize = Dimension(0, 24)
        }
        wrapper.add(gradientPanel, BorderLayout.NORTH)
        
        val panel = JPanel(BorderLayout()).apply {
            background = JBColor(Color(252, 252, 253), Color(28, 30, 33))
            border = JBUI.Borders.empty(8, 16, 12, 16)
        }
        inputArea.apply {
            rows = 2
            lineWrap = true
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            border = JBUI.Borders.empty(10, 14, 10, 40) // 右边留空间给发送按钮
            background = JBColor(Color(255, 255, 255), Color(45, 48, 52))
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); doSend() }
                }
            })
        }
        
        // 发送箭头按钮
        val sendBtn = object : JButton() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 圆形背景
                g2.color = if (model.isRollover) JBColor(Color(59, 130, 246), Color(96, 165, 250))
                           else JBColor(Color(99, 102, 241), Color(129, 140, 248))
                g2.fillOval(2, 2, width - 4, height - 4)
                // 画箭头 (向上的箭头，像 Kiro)
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val cx = width / 2
                val cy = height / 2
                // 箭头主体 (向上的线)
                g2.drawLine(cx, cy + 5, cx, cy - 5)
                // 箭头头部
                g2.drawLine(cx - 4, cy - 1, cx, cy - 5)
                g2.drawLine(cx + 4, cy - 1, cx, cy - 5)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(32, 32)
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "发送 (Enter)"
            addActionListener { doSend() }
        }
        
        // 圆弧形输入框容器
        val inputContainer = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = inputArea.background
                g2.fillRoundRect(0, 0, width, height, 24, 24)
                g2.color = JBColor(Color(209, 213, 219), Color(75, 85, 99))
                g2.drawRoundRect(0, 0, width - 1, height - 1, 24, 24)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2)
        }
        
        val inputScroll = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(0, 56)
        }
        
        // 发送按钮放在右边
        val sendPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 10)).apply {
            isOpaque = false
            add(sendBtn)
        }
        
        inputContainer.add(inputScroll, BorderLayout.CENTER)
        inputContainer.add(sendPanel, BorderLayout.EAST)
        panel.add(inputContainer, BorderLayout.CENTER)

        // 底部提示
        val hintPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { 
            isOpaque = false 
            add(JLabel("Enter 发送 · Shift+Enter 换行").apply {
                font = Font("Microsoft YaHei", Font.PLAIN, 11)
                foreground = JBColor(Color(156, 163, 175), Color(107, 114, 128))
            })
        }
        panel.add(hintPanel, BorderLayout.SOUTH)
        wrapper.add(panel, BorderLayout.CENTER)
        return wrapper
    }

    private fun addMessage(sender: String, content: String) {
        val prefix = when (sender) { "你" -> "[你]"; "DeepSeek" -> "[AI]"; "系统" -> "[系统]"; else -> "" }
        val label = JTextArea("$prefix $content").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            foreground = when (sender) {
                "你" -> JBColor(Color(37, 99, 235), Color(96, 165, 250))
                "DeepSeek" -> JBColor(Color(30, 41, 59), Color(220, 225, 235))
                else -> JBColor(Color(107, 114, 128), Color(156, 163, 175))
            }
            border = JBUI.Borders.empty(4, 16)
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

    private fun clearChat() { chatPanel.removeAll(); chatPanel.revalidate(); addMessage("系统", "已清空") }

    fun setEditorContext(editor: Editor?, text: String?, isSelectedText: Boolean) {
        currentEditor = editor
        selectionContext = text?.takeIf { it.isNotBlank() }
        isSelection = isSelectedText
        selectionContext?.let { addMessage("系统", "已获取${if (isSelectedText) "选中" else "文件"}代码（${it.lines().size}行）") }
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
        val responseArea = JTextArea("[AI] ").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 13)
            foreground = JBColor(Color(30, 41, 59), Color(220, 225, 235))
            border = JBUI.Borders.empty(4, 16)
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
                            thinkingHeader.text = "思考中 (${elapsed}秒) ▼"
                            thinkingContent.text = thinking.toString()
                            thinkingContent.isVisible = true
                            scrollToBottom()
                        }
                    } else {
                        response.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            if (!responseAdded) {
                                responseAdded = true
                                // 折叠思考，停止动画
                                val elapsed = String.format("%.1f", (System.currentTimeMillis() - startTime) / 1000.0)
                                thinkingHeader.putClientProperty("stopped", true)
                                thinkingHeader.text = "已深度思考 ${elapsed}秒 ▶"
                                thinkingContent.isVisible = false
                                chatPanel.add(responseArea)
                                chatPanel.revalidate()
                            }
                            responseArea.text = "[AI] $response"
                            scrollToBottom()
                        }
                    }
                }
            } catch (e: Exception) {
                response.append("错误: ${e.message}")
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
                    responseArea.text = "[AI] $response"
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
            border = JBUI.Borders.empty(2, 16, 0, 16)
        }
        val innerPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 渐变背景
                val gradient = GradientPaint(0f, 0f, JBColor(Color(248, 250, 255), Color(35, 39, 47)),
                    width.toFloat(), height.toFloat(), JBColor(Color(240, 245, 255), Color(40, 44, 52)))
                g2.paint = gradient
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                // 左边装饰条
                g2.color = JBColor(Color(99, 102, 241), Color(129, 140, 248))
                g2.fillRoundRect(0, 0, 4, height, 4, 4)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 16, 6, 12)
        }
        
        // 渐变动画的 header
        val header = object : JLabel("思考中...") {
            private var phase = 0f
            private var timer: javax.swing.Timer? = null
            
            init {
                font = Font("Microsoft YaHei", Font.BOLD, 13)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                startAnimation()
            }
            
            private fun startAnimation() {
                timer = javax.swing.Timer(50) {
                    phase += 0.1f
                    if (phase > 2 * Math.PI) phase = 0f
                    repaint()
                }
                timer?.start()
            }
            
            override fun paintComponent(g: Graphics) {
                // 检查是否需要停止动画
                if (getClientProperty("stopped") == true) {
                    timer?.stop()
                    timer = null
                    foreground = JBColor(Color(79, 70, 229), Color(165, 180, 252))
                    super.paintComponent(g)
                    return
                }
                
                if (timer?.isRunning == true) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.font = font
                    val fm = g2.fontMetrics
                    val text = getText()
                    var x = 0
                    for ((i, c) in text.withIndex()) {
                        // 渐变颜色：紫色 -> 蓝色 -> 紫色
                        val t = (Math.sin(phase + i * 0.3) + 1) / 2
                        val color = Color(
                            (79 + (59 - 79) * t).toInt(),
                            (70 + (130 - 70) * t).toInt(),
                            (229 + (246 - 229) * t).toInt()
                        )
                        g2.color = color
                        g2.drawString(c.toString(), x, fm.ascent)
                        x += fm.charWidth(c)
                    }
                } else {
                    super.paintComponent(g)
                }
            }
        }
        
        val content = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            font = Font("Microsoft YaHei", Font.PLAIN, 12)
            foreground = JBColor(Color(100, 116, 139), Color(148, 163, 184))
            border = JBUI.Borders.empty(10, 0, 0, 0)
            isVisible = false
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                content.isVisible = !content.isVisible
                val currentText = header.text
                header.text = if (content.isVisible) currentText.replace("▶", "▼") else currentText.replace("▼", "▶")
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
                        btns.add(JButton("拒绝").apply { addActionListener { panel.addMessage("系统", "已拒绝"); close(CANCEL_EXIT_CODE) } })
                        btns.add(JButton("取消").apply { addActionListener { close(CANCEL_EXIT_CODE) } })
                        return btns
                    }
                    override fun createActions(): Array<Action> = emptyArray()
                }.show()
            } catch (e: Exception) { addMessage("系统", "Diff错误: ${e.message}") }
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
            addMessage("系统", "已应用")
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
        val systemPrompt = """你是智能代码助手。请根据用户意图判断如何回复：

1. 如果用户只是闲聊、打招呼、问问题、咨询建议，直接用文字简短回复，不要返回代码块
2. 只有当用户明确要求修改代码、添加功能、修复bug、重构等需要改动代码的请求时，才返回完整修改后的代码（用```包裹）

判断标准：
- "你好"、"帮我解释"、"这段代码什么意思"、"有什么建议" → 文字回复
- "帮我改"、"添加xxx功能"、"修复这个bug"、"优化这段代码" → 返回代码块"""
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
            if (conn.responseCode != 200) { onChunk("API错误(${conn.responseCode})", false); return }

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
