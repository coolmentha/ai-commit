package org.coolmentha.aicommit.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import org.coolmentha.aicommit.template.CommitFormatValidator
import org.coolmentha.aicommit.template.PromptTemplateRenderer
import javax.swing.JComponent

class AiCommitSettingsConfigurable : SearchableConfigurable {
    private val settingsService = AiCommitSettingsService.getInstance()
    private var settingsPanel: AiCommitSettingsPanel? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = "AI Commit"

    override fun createComponent(): JComponent {
        val panel = AiCommitSettingsPanel()
        panel.reset(settingsService.snapshot())
        settingsPanel = panel
        return panel.component
    }

    override fun isModified(): Boolean {
        val panel = settingsPanel ?: return false
        return panel.getState() != settingsService.snapshot()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val panel = settingsPanel ?: return
        val newState = panel.getState()

        PromptTemplateRenderer.validateTemplate(newState.promptTemplate)?.let {
            throw ConfigurationException(it)
        }

        CommitFormatValidator.validateTemplate(newState.commitFormatTemplate)?.let {
            throw ConfigurationException(it)
        }

        settingsService.update(newState)
    }

    override fun reset() {
        settingsPanel?.reset(settingsService.snapshot())
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }

    companion object {
        const val ID = "org.coolmentha.aicommit.settings"
    }
}
