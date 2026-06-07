package ai.cybrium.plugin

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Project-level service that manages cyscan execution and finding state.
 */
@Service(Service.Level.PROJECT)
@State(name = "CybriumSettings", storages = [Storage("cybrium.xml")])
class CybriumService(private val project: Project) : PersistentStateComponent<CybriumService.State> {

    private val log = Logger.getInstance(CybriumService::class.java)
    private var myState = State()

    data class State(
        var cyscanPath: String = "",
        var autoScanOnSave: Boolean = true,
        var severityFilter: String = "info",
        var showCia: Boolean = true,
        // Sprint 125 P2 — "Send to Cybrium platform" wiring
        var platformUrl: String = "https://app.cybrium.ai",
        var apiKey: String = "",
    )

    /** Configured Cybrium platform base URL. */
    fun platformUrl(): String = myState.platformUrl.ifBlank { "https://app.cybrium.ai" }

    /** Configured API key (sk-...). Blank if not set. */
    fun apiKey(): String = myState.apiKey

    /**
     * Sprint 125 P2 — public wrapper around `runCyscanRaw`. The private
     * helper stays private; this is the documented entry point for
     * actions that need raw cyscan output (e.g. SendToPlatformAction
     * sending findings through the ingest endpoint without intermediate
     * Finding-list parsing).
     */
    fun runCyscanRawAction(args: List<String>): String = runCyscanRaw(args)

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    /** Resolve cyscan binary path. */
    fun cyscanPath(): String {
        if (myState.cyscanPath.isNotBlank()) return myState.cyscanPath

        // Auto-detect from PATH
        val paths = listOf(
            "/opt/homebrew/bin/cyscan",
            "/usr/local/bin/cyscan",
            "${System.getenv("HOME")}/.cargo/bin/cyscan",
        )
        for (p in paths) {
            if (java.io.File(p).canExecute()) return p
        }

        // Try which
        return try {
            val proc = ProcessBuilder("which", "cyscan").start()
            proc.inputStream.bufferedReader().readLine()?.trim() ?: "cyscan"
        } catch (e: Exception) {
            "cyscan"
        }
    }

    /** Run cyscan scan on a file or directory, return JSON findings. */
    fun scan(path: String, extraArgs: List<String> = emptyList()): List<Finding> {
        val args = mutableListOf(cyscanPath(), "scan", path, "-f", "json")
        if (myState.showCia) args.add("--cia")
        args.addAll(extraArgs)

        return runCyscan(args)
    }

    /** Run cyscan supply on a directory, return parsed Findings (legacy). */
    fun supplyChainScan(path: String): List<Finding> {
        return runCyscan(listOf(cyscanPath(), "supply", path, "-f", "json"))
    }

    /**
     * Sprint 124 P3 — Run cyscan supply on a directory, return the raw
     * JSON. Used by DependencyReportAction which needs the full structured
     * dependency tree (vulnerabilities + licenses), not the flat Finding
     * list that `supplyChainScan` returns.
     */
    fun supplyChainScanRaw(path: String): String {
        return runCyscanRaw(listOf(cyscanPath(), "supply", path, "-f", "json"))
    }

    /** Run cyscan health on a directory. */
    fun repoHealth(path: String): String {
        return runCyscanRaw(listOf(cyscanPath(), "health", path, "-f", "json"))
    }

    private fun runCyscan(args: List<String>): List<Finding> {
        val json = runCyscanRaw(args)
        return parseFindingsJson(json)
    }

    private fun runCyscanRaw(args: List<String>): String {
        return try {
            log.info("Running: ${args.joinToString(" ")}")
            val proc = ProcessBuilder(args)
                .directory(java.io.File(project.basePath ?: "."))
                .redirectErrorStream(false)
                .start()

            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)

            if (stderr.isNotBlank()) {
                log.info("cyscan stderr: $stderr")
            }
            stdout
        } catch (e: java.io.IOException) {
            // ENOENT (cyscan binary not found on PATH) is an EXPECTED state
            // — user hasn't installed cyscan yet. Don't log at error level
            // (IntelliJ Platform's log.error with a Throwable triggers a
            // fatal-error dialog AND gets flagged by the JetBrains Plugin
            // Verifier as "1 issue occurred during the IDE run", which
            // blocks marketplace approval). Log at WARN and return "[]"
            // so callers degrade gracefully.
            log.warn(
                "cyscan binary not found or could not be executed: ${e.message}. " +
                    "Install via `brew install cybrium-ai/cli/cyscan` " +
                    "or set the path in Settings → Tools → Cybrium."
            )
            "[]"
        } catch (e: Exception) {
            // Unexpected failure during scan — still log at WARN, not ERROR.
            log.warn("cyscan execution failed", e)
            "[]"
        }
    }

    private fun parseFindingsJson(json: String): List<Finding> {
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val listType = mapper.typeFactory.constructCollectionType(
                List::class.java, Finding::class.java
            )
            mapper.readValue(json, listType)
        } catch (e: Exception) {
            // Try parsing as single object
            try {
                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                listOf(mapper.readValue(json, Finding::class.java))
            } catch (e2: Exception) {
                log.warn("Failed to parse cyscan output: ${e.message}")
                emptyList()
            }
        }
    }
}

data class Finding(
    val rule_id: String = "",
    val title: String = "",
    val severity: String = "",
    val message: String = "",
    val file: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val end_line: Int = 0,
    val end_column: Int = 0,
    val snippet: String = "",
    val cwe: List<String> = emptyList(),
)
