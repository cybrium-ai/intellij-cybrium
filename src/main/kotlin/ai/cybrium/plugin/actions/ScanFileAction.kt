package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

/**
 * Scan the currently focused file via cyscan and surface the findings
 * in a message popup. Richer surfaces (tool window, dependency report,
 * CIA dashboard) live in their own action classes.
 */
class ScanFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = project.service<CybriumService>()

        val findings = service.scan(file.path)
        if (findings.isEmpty()) {
            Messages.showInfoMessage(project, "No findings in ${file.name}", "Cybrium")
        } else {
            val msg = findings.joinToString("\n") { "[${it.severity}] ${it.rule_id}: ${it.title} (line ${it.line})" }
            Messages.showInfoMessage(project, "${findings.size} finding(s):\n\n$msg", "Cybrium Scan Results")
        }
    }
}
