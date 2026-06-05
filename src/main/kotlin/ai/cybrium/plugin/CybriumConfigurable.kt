package ai.cybrium.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.*
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

/**
 * Settings page: Tools > Cybrium
 */
class CybriumConfigurable(private val project: Project) : Configurable {

    private var cyscanPathField  = JTextField(30)
    private var platformUrlField = JTextField(30)
    private var apiKeyField      = JPasswordField(30)
    private var autoScanCheckbox = JCheckBox("Scan on file save")
    private var showCiaCheckbox  = JCheckBox("Show CyTriad CIA posture summary")
    private var severityCombo    = JComboBox(arrayOf("info", "low", "medium", "high", "critical"))

    override fun getDisplayName(): String = "Cybrium"

    override fun createComponent(): JComponent {
        val service = project.service<CybriumService>()
        val state = service.state

        cyscanPathField.text     = state.cyscanPath
        platformUrlField.text    = state.platformUrl
        apiKeyField.text         = state.apiKey
        autoScanCheckbox.isSelected = state.autoScanOnSave
        showCiaCheckbox.isSelected  = state.showCia
        severityCombo.selectedItem  = state.severityFilter

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JLabel("cyscan binary path (leave empty for auto-detect):"), gbc)
        gbc.gridy = 1; gbc.weightx = 1.0
        panel.add(cyscanPathField, gbc)

        // Sprint 125 P2 — Cybrium platform URL + API key for Send-to-Platform.
        gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("Cybrium platform URL:"), gbc)
        gbc.gridy = 3; gbc.weightx = 1.0
        panel.add(platformUrlField, gbc)

        gbc.gridy = 4; gbc.weightx = 0.0
        panel.add(JLabel("API key (sk-…) — Settings > API Keys on app.cybrium.ai:"), gbc)
        gbc.gridy = 5; gbc.weightx = 1.0
        panel.add(apiKeyField, gbc)

        gbc.gridy = 6; gbc.weightx = 0.0
        panel.add(JLabel("Minimum severity to show:"), gbc)
        gbc.gridy = 7
        panel.add(severityCombo, gbc)

        gbc.gridy = 8
        panel.add(autoScanCheckbox, gbc)

        gbc.gridy = 9
        panel.add(showCiaCheckbox, gbc)

        gbc.gridy = 10
        val detected = service.cyscanPath()
        panel.add(JLabel("<html><small>Detected: $detected</small></html>"), gbc)

        return panel
    }

    override fun isModified(): Boolean {
        val service = project.service<CybriumService>()
        val state = service.state
        return cyscanPathField.text != state.cyscanPath
            || platformUrlField.text != state.platformUrl
            || String(apiKeyField.password) != state.apiKey
            || autoScanCheckbox.isSelected != state.autoScanOnSave
            || showCiaCheckbox.isSelected != state.showCia
            || severityCombo.selectedItem != state.severityFilter
    }

    override fun apply() {
        val service = project.service<CybriumService>()
        service.loadState(CybriumService.State(
            cyscanPath     = cyscanPathField.text,
            platformUrl    = platformUrlField.text.ifBlank { "https://app.cybrium.ai" },
            apiKey         = String(apiKeyField.password),
            autoScanOnSave = autoScanCheckbox.isSelected,
            showCia        = showCiaCheckbox.isSelected,
            severityFilter = severityCombo.selectedItem as String,
        ))
    }
}
