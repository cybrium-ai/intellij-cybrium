package ai.cybrium.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * "Supply Chain Scan" — same surface as the new DependencyReportAction.
 * Kept for plugin.xml compatibility (the menu item already exists);
 * delegates to the report view so users get the donut + table UI.
 */
class SupplyChainAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        DependencyReportAction().actionPerformed(e)
    }
}
