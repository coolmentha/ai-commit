package org.coolmentha.aicommit.settings

data class AiCommitSettingsState(
    var authMode: String = AiCommitAuthMode.API_KEY.id,
    var apiBaseUrl: String = DEFAULT_API_BASE_URL,
    var apiKeyBaseUrl: String = "",
    var codexOauthBaseUrl: String = "",
    var apiKey: String = "",
    var model: String = DEFAULT_MODEL,
    var promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,
    var commitFormatTemplate: String = "",
) {
    fun authModeType(): AiCommitAuthMode = AiCommitAuthMode.fromId(authMode)

    fun normalizedApiBaseUrl(mode: AiCommitAuthMode = authModeType()): String =
        rawApiBaseUrl(mode).ifEmpty { mode.defaultApiBaseUrl }

    fun rawApiBaseUrl(mode: AiCommitAuthMode = authModeType()): String {
        val modeSpecificValue = when (mode) {
            AiCommitAuthMode.API_KEY -> apiKeyBaseUrl.trim()
            AiCommitAuthMode.CODEX_OAUTH -> codexOauthBaseUrl.trim()
        }
        if (modeSpecificValue.isNotBlank()) {
            return modeSpecificValue
        }
        if (authModeType() != mode) {
            return ""
        }
        return migrateLegacyBaseUrl(mode, apiBaseUrl.trim())
    }

    fun normalizedPromptTemplate(): String = promptTemplate.ifBlank { DEFAULT_PROMPT_TEMPLATE }

    fun normalizedCommitFormatTemplate(): String = commitFormatTemplate.trim()

    fun migrateLegacyModeFields(): AiCommitSettingsState {
        val currentMode = authModeType()
        val legacyBaseUrl = apiBaseUrl.trim()
        return copy(
            apiBaseUrl = normalizedApiBaseUrl(currentMode),
            apiKeyBaseUrl = migrateModeSpecificBaseUrl(apiKeyBaseUrl, AiCommitAuthMode.API_KEY, currentMode, legacyBaseUrl),
            codexOauthBaseUrl = migrateModeSpecificBaseUrl(codexOauthBaseUrl, AiCommitAuthMode.CODEX_OAUTH, currentMode, legacyBaseUrl),
            model = model.trim(),
        )
    }

    fun normalizedForStorage(): AiCommitSettingsState {
        val currentMode = authModeType()
        return copy(
            apiBaseUrl = normalizedApiBaseUrl(currentMode),
            apiKeyBaseUrl = normalizeStoredBaseUrl(apiKeyBaseUrl, AiCommitAuthMode.API_KEY),
            codexOauthBaseUrl = normalizeStoredBaseUrl(codexOauthBaseUrl, AiCommitAuthMode.CODEX_OAUTH),
            model = model.trim(),
        )
    }

    fun isConfigured(): Boolean {
        if (model.isBlank()) {
            return false
        }
        return when (authModeType()) {
            AiCommitAuthMode.API_KEY -> apiKey.isNotBlank()
            AiCommitAuthMode.CODEX_OAUTH -> true
        }
    }

    private fun migrateModeSpecificBaseUrl(
        currentValue: String,
        mode: AiCommitAuthMode,
        currentMode: AiCommitAuthMode,
        legacyBaseUrl: String,
    ): String {
        val normalizedCurrentValue = normalizeStoredBaseUrl(currentValue, mode)
        if (normalizedCurrentValue.isNotBlank() || currentMode != mode) {
            return normalizedCurrentValue
        }
        return normalizeStoredBaseUrl(migrateLegacyBaseUrl(mode, legacyBaseUrl), mode)
    }

    private fun migrateLegacyBaseUrl(mode: AiCommitAuthMode, legacyBaseUrl: String): String {
        if (legacyBaseUrl.isBlank()) {
            return ""
        }
        val conflictingDefault = when (mode) {
            AiCommitAuthMode.API_KEY -> DEFAULT_CODEX_OAUTH_API_BASE_URL
            AiCommitAuthMode.CODEX_OAUTH -> DEFAULT_API_BASE_URL
        }
        return if (legacyBaseUrl == conflictingDefault) {
            ""
        } else {
            legacyBaseUrl
        }
    }

    private fun normalizeStoredBaseUrl(value: String, mode: AiCommitAuthMode): String {
        val trimmed = value.trim()
        return if (trimmed.isBlank() || trimmed == mode.defaultApiBaseUrl) {
            ""
        } else {
            trimmed
        }
    }

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_CODEX_OAUTH_API_BASE_URL = "https://chatgpt.com/backend-api/codex/responses"
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
