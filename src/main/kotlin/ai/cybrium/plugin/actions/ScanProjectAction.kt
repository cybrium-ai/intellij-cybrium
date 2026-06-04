package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import ai.cybrium.plugin.CybriumToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Run cyscan against the whole project and surface results in the
 * Cybrium tool window.
 */
class ScanProjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: scanning project", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan…"
                val service = project.service<CybriumService>()
                val findings = service.scan(project.basePath ?: ".")

                ApplicationManager.getApplication().invokeLater {
                    val tw = ToolWindowManager.getInstance(project).getToolWindow("Cybrium")
                    tw?.activate {
                        val content = tw.contentManager.getContent(0) ?: return@activate
                        (content.component as? CybriumToolWindowPanel)?.updateTree(findings)
                    }
                }
            }
        })
    }
}
