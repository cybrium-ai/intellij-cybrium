package ai.cybrium.plugin.actions

import ai.cybrium.plugin.CybriumService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sprint 125 P2 — Send workspace findings to the Cybrium platform.
 *
 * Runs cyscan against the project, packages findings as the canonical
 * ingest payload, and POSTs to
 *   <CybriumService.platformUrl>/api/scans/findings/ingest/
 * using the configured API key. Surfaces a notification with a
 * "Open in Cybrium" choice on success.
 */
class SendToPlatformAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CybriumService>()

        val apiKey = service.apiKey()
        val apiUrl = service.platformUrl().trimEnd('/')

        if (apiKey.isBlank()) {
            val choice = Messages.showYesNoDialog(
                project,
                "Cybrium API key is not configured. Open settings now?",
                "Cybrium",
                "Open settings", "Cancel", null,
            )
            if (choice == Messages.YES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Cybrium")
            }
            return
        }

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Cybrium: collecting + uploading findings", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running cyscan…"
                val raw = service.runCyscanRawAction(
                    listOf(service.cyscanPath(), "scan", project.basePath ?: ".", "-f", "json"),
                )
                if (raw.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "cyscan returned no output.", "Cybrium")
                    }
                    return
                }

                val canonical = try {
                    canonicaliseForIngest(raw)
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project,
                            "Failed to parse cyscan output: ${ex.message}", "Cybrium")
                    }
                    return
                }
                if (canonical.findingsCount == 0) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, "No findings to send.", "Cybrium")
                    }
                    return
                }

                indicator.text = "Uploading ${canonical.findingsCount} finding(s)…"
                val mapper = ObjectMapper()
                val payload = mapper.createObjectNode()
                    .put("source", "intellij-cybrium")
                    .put("scan_type", "ide_ingest")
                    .put("host", project.name)
                payload.set<ArrayNode>("findings", canonical.findings)

                val body = mapper.writeValueAsString(payload)
                val resp = try {
                    httpPost("$apiUrl/api/scans/findings/ingest/", body, apiKey)
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project,
                            "Upload failed: ${ex.message}", "Cybrium")
                    }
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    handleResponse(project, apiUrl, canonical.findingsCount, resp)
                }
            }
        })
    }

    private fun handleResponse(project: Project, apiUrl: String, sent: Int, resp: HttpResponse<String>) {
        val status = resp.statusCode()
        if (status !in 200..299) {
            if (status == 401) {
                Messages.showErrorDialog(project,
                    "API key rejected. Mint a new key under Settings → API Keys at $apiUrl.",
                    "Cybrium",
                )
            } else {
                Messages.showErrorDialog(project,
                    "Upload failed (HTTP $status):\n${resp.body().take(400)}",
                    "Cybrium",
                )
            }
            return
        }

        var dashboard = "$apiUrl/findings"
        try {
            val parsed = ObjectMapper().readTree(resp.body())
            parsed.path("dashboard_url").takeIf { it.isTextual }?.asText()?.let { dashboard = it }
        } catch (_: Exception) {
            /* response body not JSON — keep default */
        }

        val choice = Messages.showYesNoDialog(
            project,
            "Uploaded $sent finding(s) to $apiUrl. Open the dashboard now?",
            "Cybrium",
            "Open in Cybrium", "Close", null,
        )
        if (choice == Messages.YES) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI(dashboard))
            } catch (_: Exception) {
                Messages.showInfoMessage(project, dashboard, "Cybrium dashboard URL")
            }
        }
    }

    /** Canonical ingest envelope from cyscan SARIF or flat-findings JSON. */
    private data class Canonical(val findings: ArrayNode, val findingsCount: Int)

    private fun canonicaliseForIngest(raw: String): Canonical {
        val mapper = ObjectMapper()
        val root = mapper.readTree(raw)
        val arr = mapper.createArrayNode()
        val allowed = setOf("critical", "high", "medium", "low", "info")

        // Shape A: SARIF runs[].results[]
        val runs = root.get("runs")
        if (runs != null && runs.isArray && runs.size() > 0) {
            for (run in runs) {
                val results = run.get("results") ?: continue
                if (!results.isArray) continue
                for (r in results) {
                    val loc = r.path("locations").get(0)?.path("physicalLocation")
                    val file = loc?.path("artifactLocation")?.path("uri")?.asText("") ?: ""
                    val line = loc?.path("region")?.path("startLine")?.let { if (it.isNumber) it.asInt() else null }
                    val sev = sevFromLevel(r.path("level").asText(""))
                    val text = r.path("message").path("text").asText(r.path("ruleId").asText("Finding"))
                    val out = mapper.createObjectNode()
                        .put("rule_id", r.path("ruleId").asText(""))
                        .put("title", text.take(500))
                        .put("severity", sev)
                        .put("description", text)
                    if (file.isNotEmpty()) out.put("file", file)
                    if (line != null)      out.put("line", line)
                    if (r.path("properties").isObject)
                        out.set<ObjectNode>("evidence",
                            mapper.createObjectNode().apply {
                                set<JsonNode>("properties", r.path("properties").deepCopy())
                            })
                    arr.add(out)
                }
            }
        }
        if (arr.size() > 0) return Canonical(arr, arr.size())

        // Shape B: flat findings list
        val findings = root.get("findings") ?: (if (root.isArray) root else null)
        if (findings != null && findings.isArray) {
            for (f in findings) {
                if (!f.isObject) continue
                val sev = f.path("severity").asText("info").lowercase()
                val out = mapper.createObjectNode()
                    .put("rule_id", f.path("rule_id").asText(f.path("id").asText("")))
                    .put("title", f.path("title").asText(f.path("message").asText("Finding")).take(500))
                    .put("severity", if (sev in allowed) sev else "info")
                    .put("description",
                        f.path("message").asText(f.path("description").asText("")))
                f.path("file").takeIf { it.isTextual }?.asText()?.let { out.put("file", it) }
                f.path("line").takeIf { it.isNumber }?.asInt()?.let { out.put("line", it) }
                f.path("snippet").takeIf { it.isTextual }?.asText()?.let { out.put("snippet", it) }
                val cwe = f.path("cwe")
                if (cwe.isArray) {
                    val ca = mapper.createArrayNode()
                    cwe.forEach { ca.add(it.asText("")) }
                    out.set<ArrayNode>("cwe", ca)
                }
                f.path("fix").takeIf { it.isTextual }?.asText()?.let { out.put("recommendation", it) }
                arr.add(out)
            }
        }
        return Canonical(arr, arr.size())
    }

    private fun sevFromLevel(level: String): String = when (level.lowercase()) {
        "error" -> "high"
        "warning" -> "medium"
        "note" -> "low"
        else -> "info"
    }

    private fun httpPost(url: String, body: String, apiKey: String): HttpResponse<String> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Api-Key $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
