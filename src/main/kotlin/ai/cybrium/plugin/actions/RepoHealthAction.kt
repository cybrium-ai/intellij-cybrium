package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Run `cyscan health` and show the resulting JSON in a popup. A richer
 * webview (parallel to the dependency report) is a Sprint 125 follow-on.
 */
class RepoHealthAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: repository health", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan health…"
                val service = project.service<CybriumService>()
                val raw = service.repoHealth(project.basePath ?: ".")

                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        project,
                        raw.ifBlank { "cyscan health returned no output." },
                        "Cybrium · Repository Health",
                    )
                }
            }
        })
    }
}
