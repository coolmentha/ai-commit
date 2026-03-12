package org.coolmentha.aicommit.settings

enum class AiCommitAuthMode(
    val id: String,
    val presentableName: String,
    val defaultApiBaseUrl: String,
) {
    API_KEY(
        id = "api_key",
        presentableName = "API Key",
        defaultApiBaseUrl = AiCommitSettingsState.DEFAULT_API_BASE_URL,
    ),
    CODEX_OAUTH(
        id = "codex_oauth",
        presentableName = "Codex OAuth",
        defaultApiBaseUrl = AiCommitSettingsState.DEFAULT_CODEX_OAUTH_API_BASE_URL,
    ),
    ;

    override fun toString(): String = presentableName

    companion object {
        fun fromId(id: String?): AiCommitAuthMode = entries.firstOrNull { it.id == id } ?: API_KEY
    }
}
