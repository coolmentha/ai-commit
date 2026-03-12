package org.coolmentha.aicommit.settings

import com.intellij.ui.components.JBTextField
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiCommitSettingsPanelTest {
    @Test
    fun `切换认证模式时会保留各自的 base url`() {
        SwingUtilities.invokeAndWait {
            val panel = AiCommitSettingsPanel()
            panel.reset(
                AiCommitSettingsState(
                    authMode = AiCommitAuthMode.API_KEY.id,
                    apiKeyBaseUrl = "https://gateway.example.com/v1",
                    apiKey = "test-key",
                    model = "gpt-4.1-mini",
                ),
            )

            val authModeComboBox = assertNotNull(findFirstComponent(panel.component, JComboBox::class.java))
            val apiBaseUrlField = assertNotNull(findAllComponents(panel.component, JBTextField::class.java).firstOrNull())

            assertEquals("https://gateway.example.com/v1", apiBaseUrlField.text)

            authModeComboBox.selectedItem = AiCommitAuthMode.CODEX_OAUTH
            assertEquals(AiCommitSettingsState.DEFAULT_CODEX_OAUTH_API_BASE_URL, apiBaseUrlField.text)

            apiBaseUrlField.text = "https://chatgpt-proxy.example.com/backend-api"
            authModeComboBox.selectedItem = AiCommitAuthMode.API_KEY
            assertEquals("https://gateway.example.com/v1", apiBaseUrlField.text)

            authModeComboBox.selectedItem = AiCommitAuthMode.CODEX_OAUTH
            assertEquals("https://chatgpt-proxy.example.com/backend-api", apiBaseUrlField.text)

            val state = panel.getState()
            assertEquals("https://gateway.example.com/v1", state.normalizedApiBaseUrl(AiCommitAuthMode.API_KEY))
            assertEquals("https://chatgpt-proxy.example.com/backend-api", state.normalizedApiBaseUrl(AiCommitAuthMode.CODEX_OAUTH))
        }
    }

    private fun <T : Component> findFirstComponent(root: Component, type: Class<T>): T? =
        findAllComponents(root, type).firstOrNull()

    private fun <T : Component> findAllComponents(root: Component, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        if (type.isInstance(root)) {
            result += type.cast(root)
        }
        if (root is Container) {
            root.components.forEach { child ->
                result += findAllComponents(child, type)
            }
        }
        return result
    }
}
