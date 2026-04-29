package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

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

class ScanProjectAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CybriumService>()
        val findings = service.scan(project.basePath ?: ".")
        Messages.showInfoMessage(project, "${findings.size} finding(s) across the project", "Cybrium Project Scan")
    }
}

class SupplyChainAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CybriumService>()
        val findings = service.supplyChainScan(project.basePath ?: ".")
        Messages.showInfoMessage(project, "${findings.size} dependency finding(s)", "Cybrium Supply Chain")
    }
}

class RepoHealthAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CybriumService>()
        val result = service.repoHealth(project.basePath ?: ".")
        Messages.showInfoMessage(project, result.take(2000), "Cybrium Repo Health")
    }
}

class CiaSummaryAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CybriumService>()
        val findings = service.scan(project.basePath ?: ".", listOf("--cia"))
        Messages.showInfoMessage(project, "${findings.size} finding(s) with CIA posture", "CyTriad Summary")
    }
}
