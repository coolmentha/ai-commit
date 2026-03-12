package org.coolmentha.aicommit.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import org.coolmentha.aicommit.ai.CodexOAuthBrowserLogin
import org.coolmentha.aicommit.ai.CodexOAuthCredential
import org.coolmentha.aicommit.ai.CodexOAuthCredentialSource
import org.coolmentha.aicommit.ai.CodexOAuthCredentialsResolver
import java.awt.BorderLayout
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel

class AiCommitSettingsPanel {
    private val authModeComboBox = JComboBox(AiCommitAuthMode.entries.toTypedArray())
    private val apiBaseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val promptTemplateArea = JBTextArea()
    private val formatTemplateArea = JBTextArea()
    private val authModeHintLabel = JBLabel()
    private val codexOAuthStatusLabel = JBLabel()
    private val codexOAuthLoginButton = JButton("网页登录")

    private val rootPanel = JPanel(BorderLayout())
    private var suppressAuthModeSync = false
    private var currentAuthMode = AiCommitAuthMode.API_KEY
    private var apiKeyModeBaseUrl = ""
    private var codexOauthModeBaseUrl = ""
    private val statusTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        promptTemplateArea.lineWrap = true
        promptTemplateArea.wrapStyleWord = true
        promptTemplateArea.rows = 12

        formatTemplateArea.lineWrap = true
        formatTemplateArea.wrapStyleWord = true
        formatTemplateArea.rows = 4

        authModeComboBox.addActionListener {
            onAuthModeChanged()
        }
        codexOAuthLoginButton.addActionListener {
            startCodexOAuthLogin()
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
            add(
                fieldBlock(
                    "认证方式",
                    "API Key 模式沿用 OpenAI 兼容 Chat Completions；Codex OAuth 模式支持插件内网页登录，也兼容复用已有本机登录态，并走 ChatGPT Codex Responses。",
                    authModeBlock(),
                ),
            )
            add(fieldBlock("API Base URL", "认证模式留空时会自动回填对应默认地址，也可以手工覆盖。", apiBaseUrlField))
            add(fieldBlock("API Key", "仅 API Key 模式需要填写。", apiKeyField))
            add(fieldBlock("模型", "API Key 模式示例：`gpt-4.1-mini`；Codex OAuth 模式建议使用 `gpt-5.4` 或其他 Codex 模型。", modelField))
            add(
                fieldBlock(
                    "Prompt 模板",
                    "必须包含 `${'$'}{diff}`。可选变量：`${'$'}{branch}`、`${'$'}{format_instruction}`。",
                    scroll(promptTemplateArea, 620, 240),
                ),
            )
            add(
                fieldBlock(
                    "提交格式模板（可选）",
                    "使用 `{{字段名}}` 占位，例如 `{{type}}({{scope}}): {{subject}}`。生成结果会按该模板校验，不匹配则拒绝写回。",
                    scroll(formatTemplateArea, 620, 120),
                ),
            )
            add(Box.createVerticalGlue())
        }

        rootPanel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)
    }

    val component: JComponent
        get() = rootPanel

    fun reset(state: AiCommitSettingsState) {
        val normalizedState = state.migrateLegacyModeFields().normalizedForStorage()
        suppressAuthModeSync = true
        currentAuthMode = normalizedState.authModeType()
        apiKeyModeBaseUrl = normalizedState.rawApiBaseUrl(AiCommitAuthMode.API_KEY)
        codexOauthModeBaseUrl = normalizedState.rawApiBaseUrl(AiCommitAuthMode.CODEX_OAUTH)
        authModeComboBox.selectedItem = currentAuthMode
        apiBaseUrlField.text = normalizedState.normalizedApiBaseUrl(currentAuthMode)
        apiKeyField.text = normalizedState.apiKey
        modelField.text = normalizedState.model
        promptTemplateArea.text = normalizedState.normalizedPromptTemplate()
        formatTemplateArea.text = normalizedState.commitFormatTemplate
        suppressAuthModeSync = false
        refreshCodexOAuthStatus()
        updateAuthModeUi()
    }

    fun getState(): AiCommitSettingsState {
        val selectedMode = selectedAuthMode()
        rememberCurrentModeBaseUrl(selectedMode)
        return AiCommitSettingsState(
            authMode = selectedMode.id,
            apiBaseUrl = normalizedBaseUrlFor(selectedMode),
            apiKeyBaseUrl = apiKeyModeBaseUrl,
            codexOauthBaseUrl = codexOauthModeBaseUrl,
            apiKey = String(apiKeyField.password),
            model = modelField.text.trim(),
            promptTemplate = promptTemplateArea.text,
            commitFormatTemplate = formatTemplateArea.text,
        ).normalizedForStorage()
    }

    private fun fieldBlock(title: String, description: String, component: JComponent): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            alignmentX = 0.0f
            border = JBUI.Borders.emptyBottom(12)
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(title))
            add(
                JBLabel("<html><body style='width: 620px'>$description</body></html>").apply {
                    foreground = JBColor.GRAY
                },
            )
        }
        panel.add(header, BorderLayout.NORTH)
        panel.add(component, BorderLayout.CENTER)
        return panel
    }

    private fun scroll(area: JBTextArea, width: Int, height: Int): JBScrollPane =
        JBScrollPane(area).apply {
            preferredSize = JBUI.size(width, height)
        }

    private fun authModeBlock(): JComponent {
        authModeHintLabel.foreground = JBColor.GRAY
        codexOAuthStatusLabel.foreground = JBColor.GRAY
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(authModeComboBox)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(authModeHintLabel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(codexOAuthStatusLabel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(codexOAuthLoginButton)
        }
    }

    private fun onAuthModeChanged() {
        val nextMode = selectedAuthMode()
        if (suppressAuthModeSync) {
            currentAuthMode = nextMode
        } else {
            rememberCurrentModeBaseUrl(currentAuthMode)
            currentAuthMode = nextMode
            apiBaseUrlField.text = normalizedBaseUrlFor(nextMode)
        }
        updateAuthModeUi()
    }

    private fun updateAuthModeUi() {
        val authMode = selectedAuthMode()
        val apiKeyEnabled = authMode == AiCommitAuthMode.API_KEY
        apiKeyField.isEnabled = apiKeyEnabled
        codexOAuthLoginButton.isVisible = authMode == AiCommitAuthMode.CODEX_OAUTH
        codexOAuthLoginButton.isEnabled = authMode == AiCommitAuthMode.CODEX_OAUTH
        codexOAuthStatusLabel.isVisible = authMode == AiCommitAuthMode.CODEX_OAUTH
        authModeHintLabel.text = when (authMode) {
            AiCommitAuthMode.API_KEY ->
                "手工输入 API Key，请求会发送到 OpenAI 兼容 `/chat/completions` 接口。"
            AiCommitAuthMode.CODEX_OAUTH ->
                "优先使用插件内网页登录写入的 OAuth 凭证，也兼容复用本机已有登录态，并请求 ChatGPT Codex Responses。"
        }
        if (authMode == AiCommitAuthMode.CODEX_OAUTH) {
            refreshCodexOAuthStatus()
        }
    }

    private fun selectedAuthMode(): AiCommitAuthMode =
        authModeComboBox.selectedItem as? AiCommitAuthMode ?: AiCommitAuthMode.API_KEY

    private fun rememberCurrentModeBaseUrl(mode: AiCommitAuthMode) {
        val persistedValue = normalizedStoredBaseUrl(apiBaseUrlField.text, mode)
        when (mode) {
            AiCommitAuthMode.API_KEY -> apiKeyModeBaseUrl = persistedValue
            AiCommitAuthMode.CODEX_OAUTH -> codexOauthModeBaseUrl = persistedValue
        }
    }

    private fun normalizedBaseUrlFor(mode: AiCommitAuthMode): String =
        when (mode) {
            AiCommitAuthMode.API_KEY -> apiKeyModeBaseUrl
            AiCommitAuthMode.CODEX_OAUTH -> codexOauthModeBaseUrl
        }.ifBlank { mode.defaultApiBaseUrl }

    private fun normalizedStoredBaseUrl(value: String, mode: AiCommitAuthMode): String {
        val trimmed = value.trim()
        return if (trimmed.isBlank() || trimmed == mode.defaultApiBaseUrl) {
            ""
        } else {
            trimmed
        }
    }

    private fun startCodexOAuthLogin() {
        codexOAuthLoginButton.isEnabled = false
        codexOAuthStatusLabel.text = "当前状态：正在等待浏览器完成 Codex OAuth 登录。"
        notify("已打开 Codex OAuth 登录流程；如果浏览器没有自动回调，插件会提示粘贴回调 URL。", NotificationType.INFORMATION)

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                CodexOAuthBrowserLogin().login { authUrl ->
                    promptForManualCallback(authUrl)
                }
            }

            ApplicationManager.getApplication().invokeLater {
                codexOAuthLoginButton.isEnabled = selectedAuthMode() == AiCommitAuthMode.CODEX_OAUTH
                result.onSuccess { credential ->
                    setCodexOAuthStatus(credential)
                    notify("Codex OAuth 登录成功。", NotificationType.INFORMATION)
                }.onFailure { error ->
                    refreshCodexOAuthStatus()
                    notify(error.message ?: "Codex OAuth 登录失败。", NotificationType.ERROR)
                }
            }
        }
    }

    private fun promptForManualCallback(authUrl: URI): String? {
        val result = arrayOfNulls<String>(1)
        ApplicationManager.getApplication().invokeAndWait {
            result[0] = Messages.showInputDialog(
                rootPanel,
                """
浏览器未自动完成回调。
请将浏览器地址栏中的完整回调 URL 粘贴到这里后继续。

如果登录页没有打开，可以手工访问：
$authUrl
                """.trimIndent(),
                "粘贴 Codex OAuth 回调 URL",
                null,
            )
        }
        return result[0]
    }

    private fun refreshCodexOAuthStatus() {
        runCatching {
            CodexOAuthCredentialsResolver().peekStoredCredential()
        }.onSuccess { credential ->
            if (credential == null) {
                codexOAuthStatusLabel.text = "当前状态：未检测到 Codex OAuth 凭证。"
                codexOAuthStatusLabel.toolTipText = null
            } else {
                setCodexOAuthStatus(credential)
            }
        }.onFailure { error ->
            codexOAuthStatusLabel.text = "当前状态：读取 Codex OAuth 登录状态失败。"
            codexOAuthStatusLabel.toolTipText = error.message
        }
    }

    private fun setCodexOAuthStatus(credential: CodexOAuthCredential) {
        val source = when (credential.source) {
            CodexOAuthCredentialSource.KEYCHAIN -> "macOS Keychain"
            CodexOAuthCredentialSource.FILE -> credential.sourcePath?.fileName?.toString() ?: "~/.codex/auth.json"
        }
        codexOAuthStatusLabel.text =
            "当前状态：已登录，来源：$source，过期时间：${statusTimeFormatter.format(credential.expiresAt)}。"
        codexOAuthStatusLabel.toolTipText = credential.sourcePath?.toString()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Commit")
            .createNotification(message, type)
            .notify(null)
    }
}
