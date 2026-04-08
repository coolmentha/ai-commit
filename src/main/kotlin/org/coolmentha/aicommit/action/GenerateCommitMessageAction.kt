package org.coolmentha.aicommit.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.CommitWorkflowHandler
import com.intellij.vcs.commit.CommitWorkflowUi
import org.coolmentha.aicommit.ai.AiCommitException
import org.coolmentha.aicommit.ai.AiCommitMessageGenerator
import org.coolmentha.aicommit.settings.AiCommitSettingsConfigurable
import org.coolmentha.aicommit.settings.AiCommitSettingsService

class GenerateCommitMessageAction : DumbAwareAction("AI 生成提交信息") {
    private val settingsService = AiCommitSettingsService.getInstance()
    private val generator = AiCommitMessageGenerator()

    override fun update(event: AnActionEvent) {
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val workflowHandler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        val hasChanges = workflowUi?.getIncludedChanges()?.isNotEmpty() == true ||
            workflowUi?.getIncludedUnversionedFiles()?.isNotEmpty() == true ||
            isAmendCommit(workflowHandler)
        event.presentation.isVisible = workflowUi != null
        event.presentation.isEnabled = hasChanges
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val workflowHandler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        val settings = settingsService.snapshot()
        val isAmendCommit = isAmendCommit(workflowHandler)

        if (!settings.isConfigured()) {
            notify(project, NotificationType.WARNING, "请先在设置中完成 AI 接口配置。")
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiCommitSettingsConfigurable::class.java)
            return
        }

        val includedChanges = workflowUi.getIncludedChanges().toList()
        val includedUnversionedFiles = workflowUi.getIncludedUnversionedFiles().toList()
        val commitMessageUi = workflowUi.commitMessageUi
        commitMessageUi.startLoading()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "AI 生成提交信息", true) {
                private var generatedMessage: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.2
                    indicator.text = "收集待提交差异"
                    generatedMessage = generator.generate(
                        project = project,
                        includedChanges = includedChanges,
                        includedUnversionedFiles = includedUnversionedFiles,
                        settings = settings,
                        isAmendCommit = isAmendCommit,
                    )
                    indicator.fraction = 1.0
                }

                override fun onSuccess() {
                    commitMessageUi.stopLoading()
                    val message = generatedMessage ?: return
                    commitMessageUi.setText(message)
                    commitMessageUi.focus()
                    notify(project, NotificationType.INFORMATION, "已生成提交信息。")
                }

                override fun onThrowable(error: Throwable) {
                    commitMessageUi.stopLoading()
                    val message = if (error is AiCommitException) error.message else error.localizedMessage
                    notify(project, NotificationType.ERROR, message ?: "生成提交信息失败。")
                }

                override fun onCancel() {
                    commitMessageUi.stopLoading()
                }
            },
        )
    }

    private fun notify(project: com.intellij.openapi.project.Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Commit")
            .createNotification(content, type)
            .notify(project)
    }

    private fun isAmendCommit(workflowHandler: CommitWorkflowHandler?): Boolean {
        return workflowHandler?.amendCommitHandler?.isAmendCommitMode == true
    }
}
