package org.coolmentha.aicommit.template

import org.coolmentha.aicommit.settings.AiCommitSettingsState

data class PromptTemplateVariables(
    val diff: String,
    val branch: String,
    val formatInstruction: String,
)

object PromptTemplateRenderer {
    private const val DIFF_TOKEN = "\${diff}"
    private const val BRANCH_TOKEN = "\${branch}"
    private const val FORMAT_TOKEN = "\${format_instruction}"

    fun render(template: String, variables: PromptTemplateVariables): String {
        val effectiveTemplate = template.ifBlank { AiCommitSettingsState.DEFAULT_PROMPT_TEMPLATE }
        return effectiveTemplate
            .replace(DIFF_TOKEN, variables.diff)
            .replace(BRANCH_TOKEN, variables.branch)
            .replace(FORMAT_TOKEN, variables.formatInstruction)
            .trim()
    }

    fun validateTemplate(template: String): String? {
        if (template.isBlank()) {
            return null
        }
        return if (template.contains(DIFF_TOKEN)) {
            null
        } else {
            "Prompt 模板必须包含 `${DIFF_TOKEN}`，否则 AI 无法分析待提交代码。"
        }
    }
}
