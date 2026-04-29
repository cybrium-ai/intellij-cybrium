package ai.cybrium.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.awt.BorderLayout

/**
 * Tool window showing scan findings in a tree grouped by severity.
 */
class CybriumToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CybriumToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Findings", false)
        toolWindow.contentManager.addContent(content)
    }
}

class CybriumToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("Cybrium Findings")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val statusLabel = JLabel("Ready — run a scan from Tools > Cybrium")

    init {
        val scrollPane = JBScrollPane(tree)
        add(scrollPane, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(statusLabel, BorderLayout.WEST)

        val scanButton = JButton("Scan Project")
        scanButton.addActionListener { scanProject() }
        bottomPanel.add(scanButton, BorderLayout.EAST)

        add(bottomPanel, BorderLayout.SOUTH)
    }

    fun scanProject() {
        statusLabel.text = "Scanning..."
        root.removeAllChildren()
        treeModel.reload()

        Thread {
            val service = project.service<CybriumService>()
            val findings = service.scan(project.basePath ?: ".")

            SwingUtilities.invokeLater {
                updateTree(findings)
                statusLabel.text = "${findings.size} finding(s)"
            }
        }.start()
    }

    fun updateTree(findings: List<Finding>) {
        root.removeAllChildren()

        // Group by severity
        val grouped = findings.groupBy { it.severity.lowercase() }
        val order = listOf("critical", "high", "medium", "low", "info")

        for (sev in order) {
            val items = grouped[sev] ?: continue
            val sevNode = DefaultMutableTreeNode("${sev.uppercase()} (${items.size})")
            for (f in items) {
                val label = "${f.rule_id} — ${f.title} (${f.file}:${f.line})"
                sevNode.add(DefaultMutableTreeNode(label))
            }
            root.add(sevNode)
        }

        treeModel.reload()
        // Expand first level
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }
}
