package org.coolmentha.aicommit.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AiCommitSettingsPanel {
    private val apiBaseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val promptTemplateArea = JBTextArea()
    private val formatTemplateArea = JBTextArea()

    private val rootPanel = JPanel(BorderLayout())

    init {
        promptTemplateArea.lineWrap = true
        promptTemplateArea.wrapStyleWord = true
        promptTemplateArea.rows = 12

        formatTemplateArea.lineWrap = true
        formatTemplateArea.wrapStyleWord = true
        formatTemplateArea.rows = 4

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
            add(fieldBlock("API Base URL", "OpenAI-compatible 接口基础地址，默认直接使用 OpenAI 官方 `/v1`。", apiBaseUrlField))
            add(fieldBlock("API Key", "用于请求模型接口。", apiKeyField))
            add(fieldBlock("模型", "例如 `gpt-4.1-mini`。", modelField))
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
        apiBaseUrlField.text = state.normalizedApiBaseUrl()
        apiKeyField.text = state.apiKey
        modelField.text = state.model
        promptTemplateArea.text = state.normalizedPromptTemplate()
        formatTemplateArea.text = state.commitFormatTemplate
    }

    fun getState(): AiCommitSettingsState =
        AiCommitSettingsState(
            apiBaseUrl = apiBaseUrlField.text,
            apiKey = String(apiKeyField.password),
            model = modelField.text.trim(),
            promptTemplate = promptTemplateArea.text,
            commitFormatTemplate = formatTemplateArea.text,
        )

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
}
