package org.coolmentha.aicommit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AiCommitSettings", storages = [Storage("ai-commit.xml")])
class AiCommitSettingsService : PersistentStateComponent<AiCommitSettingsState> {
    private var state = AiCommitSettingsState()

    override fun getState(): AiCommitSettingsState = state

    override fun loadState(state: AiCommitSettingsState) {
        this.state = state.copy()
    }

    fun snapshot(): AiCommitSettingsState = state.copy()

    fun update(newState: AiCommitSettingsState) {
        state = newState.copy()
    }

    companion object {
        fun getInstance(): AiCommitSettingsService =
            ApplicationManager.getApplication().getService(AiCommitSettingsService::class.java)
    }
}
