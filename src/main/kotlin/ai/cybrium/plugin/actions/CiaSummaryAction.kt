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
 * CyTriad CIA posture summary — Confidentiality / Integrity / Availability.
 * `cyscan scan --cia` emits per-finding CIA scoring; we surface the
 * aggregated counts in a simple popup. A richer dashboard view is a
 * follow-on parallel to the dependency report.
 */
class CiaSummaryAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: CyTriad CIA scoring", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan scan --cia…"
                val service = project.service<CybriumService>()
                val findings = service.scan(project.basePath ?: ".", listOf("--cia"))

                val sevCounts = findings.groupingBy { it.severity.lowercase() }.eachCount()
                val msg = buildString {
                    appendLine("Cybrium CyTriad scoring — ${findings.size} findings")
                    appendLine("")
                    listOf("critical", "high", "medium", "low", "info").forEach {
                        appendLine("${it.uppercase().padEnd(8)} ${sevCounts[it] ?: 0}")
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, msg, "Cybrium · CyTriad CIA")
                }
            }
        })
    }
}
