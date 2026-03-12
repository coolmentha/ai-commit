package org.coolmentha.aicommit.ai

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import org.coolmentha.aicommit.settings.AiCommitSettingsState
import org.coolmentha.aicommit.template.CommitFormatValidator
import org.coolmentha.aicommit.template.PromptTemplateRenderer
import org.coolmentha.aicommit.template.PromptTemplateVariables
import org.coolmentha.aicommit.vcs.CommitDiffCollector

class AiCommitMessageGenerator(
    private val aiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(),
) {
    fun generate(
        project: Project,
        includedChanges: Collection<Change>,
        includedUnversionedFiles: Collection<FilePath>,
        settings: AiCommitSettingsState,
    ): String {
        val diff = CommitDiffCollector.collect(project, includedChanges, includedUnversionedFiles)
        if (diff.isBlank()) {
            throw AiCommitException("没有可分析的待提交代码差异。")
        }

        val prompt = PromptTemplateRenderer.render(
            settings.normalizedPromptTemplate(),
            PromptTemplateVariables(
                diff = diff,
                branch = CommitDiffCollector.currentBranch(project),
                formatInstruction = CommitFormatValidator.buildInstruction(settings.normalizedCommitFormatTemplate()),
            ),
        )

        val response = aiClient.generateCommitMessage(settings, prompt).trim()
        if (response.isBlank()) {
            throw AiCommitException("AI 返回了空的 commit message。")
        }

        val validationResult = CommitFormatValidator.validateMessage(settings.normalizedCommitFormatTemplate(), response)
        if (!validationResult.isValid) {
            throw AiCommitException(validationResult.message ?: "AI 返回内容不符合配置格式。")
        }

        return response
    }
}
