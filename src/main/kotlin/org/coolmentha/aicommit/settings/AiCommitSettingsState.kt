package org.coolmentha.aicommit.settings

data class AiCommitSettingsState(
    var apiBaseUrl: String = DEFAULT_API_BASE_URL,
    var apiKey: String = "",
    var model: String = DEFAULT_MODEL,
    var promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,
    var commitFormatTemplate: String = "",
) {
    fun normalizedApiBaseUrl(): String = apiBaseUrl.trim().ifEmpty { DEFAULT_API_BASE_URL }

    fun normalizedPromptTemplate(): String = promptTemplate.ifBlank { DEFAULT_PROMPT_TEMPLATE }

    fun normalizedCommitFormatTemplate(): String = commitFormatTemplate.trim()

    fun isConfigured(): Boolean = apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4.1-mini"
        const val DEFAULT_PROMPT_TEMPLATE = """
你是一名资深开发者，请根据待提交代码差异生成一个简洁、准确、可直接提交的 commit message。
要求：
1. 只返回 commit message 本文，不要添加解释、序号、代码块或额外前后缀。
2. 优先总结主要改动和业务意图，避免空泛表述。
3. 如果差异较多，优先覆盖最核心的改动。
${'$'}{format_instruction}

当前分支：
${'$'}{branch}

待提交差异：
${'$'}{diff}
"""
    }
}
