package org.coolmentha.aicommit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCommitSettingsStateTest {
    @Test
    fun `Codex OAuth 模式只要求模型`() {
        val state = AiCommitSettingsState(
            authMode = AiCommitAuthMode.CODEX_OAUTH.id,
            model = "gpt-5.4",
            apiBaseUrl = "",
        )

        assertTrue(state.isConfigured())
        assertEquals(AiCommitSettingsState.DEFAULT_CODEX_OAUTH_API_BASE_URL, state.normalizedApiBaseUrl())
    }

    @Test
    fun `API Key 模式缺少 key 时视为未配置`() {
        val state = AiCommitSettingsState(
            authMode = AiCommitAuthMode.API_KEY.id,
            model = "gpt-4.1-mini",
            apiKey = "",
        )

        assertFalse(state.isConfigured())
    }

    @Test
    fun `Codex OAuth 模式会忽略共享的 API Key 默认地址`() {
        val state = AiCommitSettingsState(
            authMode = AiCommitAuthMode.CODEX_OAUTH.id,
            apiBaseUrl = AiCommitSettingsState.DEFAULT_API_BASE_URL,
            model = "gpt-5.4",
        )

        assertEquals(AiCommitSettingsState.DEFAULT_CODEX_OAUTH_API_BASE_URL, state.normalizedApiBaseUrl())
    }

    @Test
    fun `会按认证模式分别读取 base url`() {
        val state = AiCommitSettingsState(
            authMode = AiCommitAuthMode.CODEX_OAUTH.id,
            apiKeyBaseUrl = "https://gateway.example.com/v1",
            codexOauthBaseUrl = "https://chatgpt-proxy.example.com/backend-api",
        )

        assertEquals("https://gateway.example.com/v1", state.normalizedApiBaseUrl(AiCommitAuthMode.API_KEY))
        assertEquals("https://chatgpt-proxy.example.com/backend-api", state.normalizedApiBaseUrl(AiCommitAuthMode.CODEX_OAUTH))
    }
}
