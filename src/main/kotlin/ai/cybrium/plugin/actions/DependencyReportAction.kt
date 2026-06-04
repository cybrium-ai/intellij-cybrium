package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Sprint 124 P3 — Dependency Analytics Report.
 *
 * Runs `cyscan supply <project> -f json` against the open project, normalises
 * the output into the canonical shape expected by the shared HTML template
 * at `/webview/report.html`, then renders that template in a JCEF browser
 * inside a modal dialog.
 *
 * The HTML is identical to the one shipped in cybrium-ai/vscode-cybrium —
 * single source of truth for both extensions.
 */
class DependencyReportAction : AnAction() {

    private val log = Logger.getInstance(DependencyReportAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        if (!JBCefApp.isSupported()) {
            Messages.showWarningDialog(
                project,
                "JCEF (Java Chromium Embedded Framework) is not available in this IDE. " +
                "Enable it via Registry > 'ide.browser.jcef.enabled' = true, then restart.",
                "Cybrium",
            )
            return
        }

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: scanning dependencies", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan supply…"
                val service = project.service<CybriumService>()
                val raw = service.supplyChainScanRaw(project.basePath ?: ".")

                if (raw.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "cyscan supply returned no output. Confirm cyscan is installed " +
                            "(brew tap cybrium-ai/cli && brew install cyscan).",
                            "Cybrium Dependency Report",
                        )
                    }
                    return
                }

                val canonical = try {
                    normaliseReport(raw)
                } catch (ex: Exception) {
                    log.warn("cyscan output normalise failed", ex)
                    "{}"
                }

                ApplicationManager.getApplication().invokeLater {
                    DependencyReportDialog(project, canonical).show()
                }
            }
        })
    }

    /**
     * Translate raw cyscan-supply JSON into the canonical shape the shared
     * HTML expects:
     *
     *   { ecosystem, generated_at,
     *     summary: {critical, high, medium, low},
     *     dependencies: [{name, version, ecosystem, is_direct,
     *                     direct_vulnerabilities: [{cve, severity, fixed_in?}],
     *                     transitive_vulnerabilities: [...],
     *                     remediation: {available, upgrade_to?}}],
     *     licenses: { summary: {permissive, weak_copyleft, strong_copyleft,
     *                            proprietary, unknown},
     *                 by_dependency: [{name, version, license: {name, category},
     *                                   evidences?, evidence_count?}] } }
     *
     * Accepts both the new `{dependencies:[...]}` shape and the older flat
     * `{findings:[...]}` shape (groups by dep name when flat).
     */
    private fun normaliseReport(raw: String): String {
        val mapper = ObjectMapper()
        val root: JsonNode = mapper.readTree(raw)

        val summary = mapper.createObjectNode()
            .put("critical", 0).put("high", 0).put("medium", 0).put("low", 0)
        val licSum = mapper.createObjectNode()
            .put("permissive", 0).put("weak_copyleft", 0)
            .put("strong_copyleft", 0).put("proprietary", 0).put("unknown", 0)
        val dependencies = mapper.createArrayNode()
        val byDepLic = mapper.createArrayNode()

        val depsNode = root.get("dependencies") ?: root.get("deps")
        if (depsNode != null && depsNode.isArray && depsNode.size() > 0) {
            for (d in depsNode) {
                val direct = (d.get("direct_vulnerabilities") ?: d.get("vulnerabilities"))?.toVulnArray(mapper) ?: mapper.createArrayNode()
                val transitive = d.get("transitive_vulnerabilities")?.toVulnArray(mapper) ?: mapper.createArrayNode()

                for (v in direct) bumpSummary(summary, v.get("severity")?.asText() ?: "info")
                for (v in transitive) bumpSummary(summary, v.get("severity")?.asText() ?: "info")

                val upgradeTo: String? =
                    d.path("remediation").path("upgrade_to").takeIf { it.isTextual }?.asText()
                        ?: (direct.firstOrNull { it.get("fixed_in")?.isTextual == true }
                            ?: transitive.firstOrNull { it.get("fixed_in")?.isTextual == true })
                            ?.get("fixed_in")?.asText()

                val dep = mapper.createObjectNode()
                    .put("name", d.path("name").asText(d.path("package").asText("?")))
                    .put("version", d.path("version").asText(d.path("current_version").asText("—")))
                    .put("ecosystem", d.path("ecosystem").asText(root.path("ecosystem").asText("")))
                    .put("is_direct", d.path("is_direct").asBoolean(d.path("direct").asBoolean(false)))
                dep.set<ArrayNode>("direct_vulnerabilities", direct)
                dep.set<ArrayNode>("transitive_vulnerabilities", transitive)
                val rem = mapper.createObjectNode()
                    .put("available", upgradeTo != null)
                if (upgradeTo != null) rem.put("upgrade_to", upgradeTo)
                dep.set<ObjectNode>("remediation", rem)
                dependencies.add(dep)

                val licName = d.path("license").let {
                    if (it.isObject) it.path("name").asText("Unknown")
                    else if (it.isTextual) it.asText("Unknown")
                    else "Unknown"
                }
                val licCat = d.path("license").path("category").takeIf { it.isTextual }?.asText()
                    ?: classifyLicense(licName)
                val catKey = when (licCat) {
                    "weak-copyleft" -> "weak_copyleft"
                    "strong-copyleft" -> "strong_copyleft"
                    else -> licCat
                }
                val current = licSum.path(catKey).asInt(0)
                if (licSum.has(catKey)) licSum.put(catKey, current + 1)
                else licSum.put("unknown", licSum.path("unknown").asInt(0) + 1)

                val licRow = mapper.createObjectNode()
                    .put("name", dep.path("name").asText())
                    .put("version", dep.path("version").asText())
                val licObj = mapper.createObjectNode()
                    .put("name", licName)
                    .put("category", licCat.replace("_", "-"))
                licRow.set<ObjectNode>("license", licObj)
                val ev = d.path("license_evidences")
                if (ev.isArray) licRow.set<ArrayNode>("evidences", ev.deepCopy())
                val evCount = d.path("license_evidence_count")
                if (evCount.isNumber) licRow.put("evidence_count", evCount.asInt())
                byDepLic.add(licRow)
            }
        } else {
            val findings = root.get("findings")
            if (findings != null && findings.isArray) {
                val byDep = LinkedHashMap<String, Pair<ObjectNode, ArrayNode>>()
                for (f in findings) {
                    val name = f.path("dependency").asText(f.path("package").asText("unknown"))
                    val version = f.path("version").asText("—")
                    val key = "$name@$version"
                    val slot = byDep.getOrPut(key) {
                        val dep = mapper.createObjectNode()
                            .put("name", name).put("version", version).put("ecosystem", "")
                            .put("is_direct", true)
                        dep.set<ArrayNode>("transitive_vulnerabilities", mapper.createArrayNode())
                        dep.set<ObjectNode>("remediation", mapper.createObjectNode().put("available", false))
                        dep to mapper.createArrayNode()
                    }
                    val v = mapper.createObjectNode()
                        .put("cve", f.path("cve").asText(f.path("id").asText(f.path("rule_id").asText(""))))
                        .put("severity", normaliseSeverity(f))
                    val fixedIn = f.path("fixed_in").takeIf { it.isTextual }?.asText()
                        ?: f.path("fix_version").takeIf { it.isTextual }?.asText()
                    if (fixedIn != null) v.put("fixed_in", fixedIn)
                    slot.second.add(v)
                    bumpSummary(summary, v.path("severity").asText("info"))
                    if (fixedIn != null) {
                        (slot.first.get("remediation") as ObjectNode).put("available", true)
                    }
                }
                for ((_, pair) in byDep) {
                    pair.first.set<ArrayNode>("direct_vulnerabilities", pair.second)
                    dependencies.add(pair.first)
                }
            }
        }

        val out = mapper.createObjectNode()
            .put("ecosystem", root.path("ecosystem").asText("—"))
            .put("generated_at",
                root.path("generated_at").takeIf { it.isTextual }?.asText() ?: java.time.Instant.now().toString())
        out.set<ObjectNode>("summary", summary)
        out.set<ArrayNode>("dependencies", dependencies)
        val lic = mapper.createObjectNode()
        lic.set<ObjectNode>("summary", licSum)
        lic.set<ArrayNode>("by_dependency", byDepLic)
        out.set<ObjectNode>("licenses", lic)

        return mapper.writeValueAsString(out)
    }

    private fun JsonNode.toVulnArray(mapper: ObjectMapper): ArrayNode {
        if (!isArray) return mapper.createArrayNode()
        val arr = mapper.createArrayNode()
        for (v in this) {
            val n = mapper.createObjectNode()
                .put("cve", v.path("cve").asText(v.path("id").asText(v.path("cve_id").asText(""))))
                .put("severity", normaliseSeverity(v))
            val fixedIn = v.path("fixed_in").takeIf { it.isTextual }?.asText()
                ?: v.path("fix_version").takeIf { it.isTextual }?.asText()
            if (fixedIn != null) n.put("fixed_in", fixedIn)
            arr.add(n)
        }
        return arr
    }

    private fun normaliseSeverity(v: JsonNode): String {
        val raw = v.path("severity")
        if (raw.isNumber) {
            val n = raw.asDouble()
            return when {
                n >= 9 -> "critical"
                n >= 7 -> "high"
                n >= 4 -> "medium"
                n > 0  -> "low"
                else   -> "info"
            }
        }
        val s = raw.asText("info").lowercase()
        return when {
            s.startsWith("crit") -> "critical"
            s.startsWith("high") -> "high"
            s.startsWith("med")  -> "medium"
            s.startsWith("low")  -> "low"
            else                 -> "info"
        }
    }

    private fun bumpSummary(summary: ObjectNode, sev: String) {
        val key = when {
            sev.startsWith("crit") -> "critical"
            sev.startsWith("high") -> "high"
            sev.startsWith("med")  -> "medium"
            sev.startsWith("low")  -> "low"
            else                   -> return
        }
        summary.put(key, summary.path(key).asInt(0) + 1)
    }

    private fun classifyLicense(name: String): String {
        val n = name.uppercase()
        if (n.isBlank() || n == "UNKNOWN" || n == "NOASSERTION") return "unknown"
        if (Regex("GPL-?3|AGPL|GNU GENERAL").containsMatchIn(n)) return "strong-copyleft"
        if (Regex("LGPL|MPL|EPL|CDDL|GPL-?2").containsMatchIn(n))   return "weak-copyleft"
        if (Regex("PROPRIETARY|COMMERCIAL").containsMatchIn(n))    return "proprietary"
        if (Regex("MIT|BSD|APACHE|ISC|ZLIB|UNLICENSE|CC0|WTFPL|0BSD|PYTHON").containsMatchIn(n)) return "permissive"
        return "unknown"
    }
}

/**
 * Modal dialog hosting a JCEF browser bound to the shared
 * `/webview/report.html` resource, with the canonical report JSON
 * injected into the page's `<script id="cybrium-report">` placeholder.
 */
private class DependencyReportDialog(
    private val project: Project,
    private val reportJson: String,
) : DialogWrapper(project, true) {

    init {
        title = "Cybrium · Dependency Analytics"
        setSize(1100, 740)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1100, 720)

        val templateBytes = javaClass.getResourceAsStream("/webview/report.html")
            ?.readBytes()
            ?: return panel.also {
                it.add(
                    com.intellij.ui.components.JBLabel(
                        "<html><body style='padding:20px;'><b>Cybrium report template missing.</b><br>" +
                        "Expected resource: <code>/webview/report.html</code></body></html>"
                    ),
                    BorderLayout.CENTER,
                )
            }

        val template = String(templateBytes, Charsets.UTF_8)

        // Escape the canonical JSON the same way the VSCode extension does.
        val safeJson = reportJson
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

        // Detect light/dark theme so the inline CSS picks the right palette.
        val isDark = !com.intellij.ui.JBColor.isBright() || isDarcula()
        val html = template
            .replace(
                Regex("""<script id="cybrium-report" type="application/json">[\s\S]*?</script>"""),
                """<script id="cybrium-report" type="application/json">$safeJson</script>""",
            )
            .replace(
                "<html lang=\"en\" data-theme=\"dark\">",
                "<html lang=\"en\" data-theme=\"${if (isDark) "dark" else "light"}\">",
            )

        val browser = JBCefBrowser()
        // loadHTML respects an empty url; pass a stable base so relative
        // links would resolve (the template ships zero of them, but this
        // matters if we ever add CDN-style assets).
        browser.loadHTML(html, "https://cybrium.local/dependency-report")
        panel.add(browser.component, BorderLayout.CENTER)
        return panel
    }

    private fun isDarcula(): Boolean = try {
        val laf = javax.swing.UIManager.getLookAndFeel()
        laf?.name?.contains("Darcula", ignoreCase = true) == true
    } catch (_: Throwable) {
        true
    }
}
