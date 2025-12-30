package com.example.copilot

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout
import java.awt.FlowLayout

@State(name = "DeepSeekSettings", storages = [Storage("deepseek.xml")])
@Service(Service.Level.APP)
class DeepSeekSettings : PersistentStateComponent<DeepSeekSettings.State> {
    
    data class State(
        var baseUrl: String = "https://api.deepseek.com",
        var model: String = "deepseek-reasoner"
    )
    
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }
    
    var baseUrl: String
        get() = myState.baseUrl
        set(value) { myState.baseUrl = value }
    
    var model: String
        get() = myState.model
        set(value) { myState.model = value }
    
    companion object {
        private const val SERVICE_NAME = "DeepSeek API Key"
        
        fun getInstance(): DeepSeekSettings = 
            ApplicationManager.getApplication().getService(DeepSeekSettings::class.java)
        
        private fun createCredentialAttributes(): CredentialAttributes =
            CredentialAttributes(generateServiceName("DeepSeekPlugin", SERVICE_NAME))
        
        fun getApiKey(): String {
            // 优先从设置读取，其次从环境变量
            val credentials = PasswordSafe.instance.get(createCredentialAttributes())
            return credentials?.getPasswordAsString() 
                ?: System.getenv("DEEPSEEK_API_KEY").orEmpty()
        }
        
        fun setApiKey(apiKey: String) {
            val attributes = createCredentialAttributes()
            PasswordSafe.instance.set(attributes, Credentials("", apiKey))
        }
    }
}


class DeepSeekConfigurable : Configurable {
    private var apiKeyField: JBPasswordField? = null
    private var baseUrlField: JBTextField? = null
    private var modelField: JBTextField? = null
    
    override fun getDisplayName(): String = "DeepSeek Code"
    
    override fun createComponent(): JComponent {
        apiKeyField = JBPasswordField()
        baseUrlField = JBTextField()
        modelField = JBTextField()
        
        val settings = DeepSeekSettings.getInstance()
        baseUrlField?.text = settings.baseUrl
        modelField?.text = settings.model
        
        // 加载已保存的 API Key（显示为掩码）
        val savedKey = DeepSeekSettings.getApiKey()
        if (savedKey.isNotBlank()) {
            apiKeyField?.text = savedKey
        }
        
        val testButton = JButton("测试连接").apply {
            addActionListener { testConnection() }
        }
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(testButton)
        }
        
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!, 1, false)
            .addComponentToRightColumn(JBLabel("<html><small>在 <a href='https://platform.deepseek.com/'>platform.deepseek.com</a> 获取</small></html>"), 0)
            .addLabeledComponent(JBLabel("API 地址:"), baseUrlField!!, 1, false)
            .addLabeledComponent(JBLabel("模型:"), modelField!!, 1, false)
            .addComponent(buttonPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        
        panel.border = JBUI.Borders.empty(10)
        return panel
    }
    
    private fun testConnection() {
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        if (apiKey.isBlank()) {
            Messages.showWarningDialog("请先输入 API Key", "提示")
            return
        }
        
        val baseUrl = baseUrlField?.text ?: "https://api.deepseek.com"
        
        Thread {
            try {
                val url = java.net.URL("$baseUrl/v1/models")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val code = conn.responseCode
                conn.disconnect()
                
                javax.swing.SwingUtilities.invokeLater {
                    if (code == 200) {
                        Messages.showInfoMessage("连接成功！API Key 有效", "测试结果")
                    } else {
                        Messages.showErrorDialog("连接失败，状态码: $code", "测试结果")
                    }
                }
            } catch (e: Exception) {
                javax.swing.SwingUtilities.invokeLater {
                    Messages.showErrorDialog("连接失败: ${e.message}", "测试结果")
                }
            }
        }.start()
    }
    
    override fun isModified(): Boolean {
        val settings = DeepSeekSettings.getInstance()
        val currentKey = String(apiKeyField?.password ?: charArrayOf())
        val savedKey = DeepSeekSettings.getApiKey()
        
        return currentKey != savedKey ||
               baseUrlField?.text != settings.baseUrl ||
               modelField?.text != settings.model
    }
    
    override fun apply() {
        val settings = DeepSeekSettings.getInstance()
        
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        if (apiKey.isNotBlank()) {
            DeepSeekSettings.setApiKey(apiKey)
        }
        
        settings.baseUrl = baseUrlField?.text ?: "https://api.deepseek.com"
        settings.model = modelField?.text ?: "deepseek-chat"
    }
    
    override fun reset() {
        val settings = DeepSeekSettings.getInstance()
        apiKeyField?.text = DeepSeekSettings.getApiKey()
        baseUrlField?.text = settings.baseUrl
        modelField?.text = settings.model
    }
}
