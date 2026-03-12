package org.coolmentha.aicommit.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AiCommitSettingsServiceTest {
    @Test
    fun `加载旧版 API Key 配置时只迁移 API Key base url`() {
        val service = AiCommitSettingsService()

        service.loadState(
            AiCommitSettingsState(
                authMode = AiCommitAuthMode.API_KEY.id,
                apiBaseUrl = "https://gateway.example.com/v1",
                apiKey = "test-key",
                model = "gpt-4.1-mini",
            ),
        )

        val snapshot = service.snapshot()
        assertEquals("https://gateway.example.com/v1", snapshot.normalizedApiBaseUrl(AiCommitAuthMode.API_KEY))
        assertEquals(AiCommitSettingsState.DEFAULT_CODEX_OAUTH_API_BASE_URL, snapshot.normalizedApiBaseUrl(AiCommitAuthMode.CODEX_OAUTH))
    }

    @Test
    fun `加载旧版 Codex OAuth 配置时会迁移到 Codex OAuth base url`() {
        val service = AiCommitSettingsService()

        service.loadState(
            AiCommitSettingsState(
                authMode = AiCommitAuthMode.CODEX_OAUTH.id,
                apiBaseUrl = "https://chatgpt-proxy.example.com/backend-api",
                model = "gpt-5.4",
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(AiCommitSettingsState.DEFAULT_API_BASE_URL, snapshot.normalizedApiBaseUrl(AiCommitAuthMode.API_KEY))
        assertEquals("https://chatgpt-proxy.example.com/backend-api", snapshot.normalizedApiBaseUrl(AiCommitAuthMode.CODEX_OAUTH))
    }
}
