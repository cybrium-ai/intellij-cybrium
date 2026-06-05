package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Sprint 125 P1 — Repository Health Report.
 *
 * Runs `cyscan health <project> -f json`, normalises the output to the
 * canonical Repo Health payload, and renders it in a JCEF browser
 * hosting the shared `webview/repo-health.html` template (byte-identical
 * to the one in vscode-cybrium).
 */
class RepoHealthAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        if (!JBCefApp.isSupported()) {
            Messages.showWarningDialog(
                project,
                "JCEF is not available in this IDE. Enable it via Registry > " +
                "'ide.browser.jcef.enabled' = true, then restart.",
                "Cybrium",
            )
            return
        }

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: repository health", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan health…"
                val service = project.service<CybriumService>()
                val raw = service.repoHealth(project.basePath ?: ".")

                if (raw.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "cyscan health returned no output. Confirm cyscan is installed.",
                            "Cybrium Repository Health",
                        )
                    }
                    return
                }

                val canonical = try {
                    normaliseHealth(raw)
                } catch (_: Exception) {
                    "{}"
                }

                ApplicationManager.getApplication().invokeLater {
                    RepoHealthDialog(project, canonical).show()
                }
            }
        })
    }

    private fun normaliseHealth(raw: String): String {
        val mapper = ObjectMapper()
        val root: JsonNode = mapper.readTree(raw)

        val checksOut = mapper.createArrayNode()
        val checksRaw = root.get("checks")
        var passing = 0
        var total = 0
        if (checksRaw != null && checksRaw.isArray) {
            for (c in checksRaw) {
                val passed = c.path("passed").asBoolean(false)
                if (passed) passing++
                total++

                val out = mapper.createObjectNode()
                    .put("id",       c.path("id").asText(c.path("rule_id").asText(c.path("name").asText(""))))
                    .put("name",     c.path("name").asText(c.path("title").asText(c.path("id").asText("Untitled"))))
                    .put("category", c.path("category").asText(c.path("group").asText("")))
                    .put("severity", c.path("severity").asText("info").lowercase())
                    .put("passed",   passed)
                if (c.path("detail").isTextual)      out.put("detail",      c.path("detail").asText())
                if (c.path("remediation").isTextual) out.put("remediation", c.path("remediation").asText())
                else if (c.path("fix").isTextual)    out.put("remediation", c.path("fix").asText())
                checksOut.add(out)
            }
        }
        val failing = total - passing

        val summary = mapper.createObjectNode()
            .put("passing", root.path("summary").path("passing").asInt(passing))
            .put("failing", root.path("summary").path("failing").asInt(failing))
            .put("total",   root.path("summary").path("total").asInt(total))

        val score = root.path("score").let { s ->
            if (s.isNumber) s.asInt()
            else if (total > 0) Math.round(100.0 * passing / total).toInt()
            else 0
        }

        val out = mapper.createObjectNode()
            .put("score", score)
            .put("generated_at",
                root.path("generated_at").takeIf { it.isTextual }?.asText() ?: java.time.Instant.now().toString())
            .put("ecosystem",
                root.path("ecosystem").takeIf { it.isTextual }?.asText() ?: root.path("project_kind").asText(""))
        out.set<ObjectNode>("summary", summary)
        out.set<ArrayNode>("checks",   checksOut)

        return mapper.writeValueAsString(out)
    }
}

private class RepoHealthDialog(
    private val project: Project,
    private val reportJson: String,
) : DialogWrapper(project, true) {

    init {
        title = "Cybrium · Repository Health"
        setSize(1100, 720)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1100, 700)

        val templateBytes = javaClass.getResourceAsStream("/webview/repo-health.html")
            ?.readBytes()
            ?: return panel.also {
                it.add(
                    com.intellij.ui.components.JBLabel(
                        "<html><body style='padding:20px;'>" +
                        "<b>Cybrium repo-health template missing.</b><br>" +
                        "Expected resource: <code>/webview/repo-health.html</code></body></html>"
                    ),
                    BorderLayout.CENTER,
                )
            }

        val template = String(templateBytes, Charsets.UTF_8)
        val safeJson = reportJson
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

        val isDark = !com.intellij.ui.JBColor.isBright() || isDarcula()
        val html = template
            .replace(
                Regex("""<script id="cybrium-repo-health" type="application/json">[\s\S]*?</script>"""),
                """<script id="cybrium-repo-health" type="application/json">$safeJson</script>""",
            )
            .replace(
                "<html lang=\"en\" data-theme=\"dark\">",
                "<html lang=\"en\" data-theme=\"${if (isDark) "dark" else "light"}\">",
            )

        val browser = JBCefBrowser()
        browser.loadHTML(html, "https://cybrium.local/repo-health")
        panel.add(browser.component, BorderLayout.CENTER)
        return panel
    }

    private fun isDarcula(): Boolean = try {
        javax.swing.UIManager.getLookAndFeel()?.name?.contains("Darcula", ignoreCase = true) == true
    } catch (_: Throwable) {
        true
    }
}
