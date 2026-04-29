package ai.cybrium.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * External annotator — runs cyscan on file save and shows inline findings.
 * ExternalAnnotator runs in background, doesn't block the UI.
 */
class CybriumAnnotator : ExternalAnnotator<PsiFile, List<Finding>>() {

    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): PsiFile = file

    override fun doAnnotate(psiFile: PsiFile): List<Finding> {
        val project = psiFile.project
        val service = project.service<CybriumService>()
        val virtualFile = psiFile.virtualFile ?: return emptyList()
        val path = virtualFile.path

        return service.scan(path)
    }

    override fun apply(file: PsiFile, findings: List<Finding>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        val filePath = file.virtualFile?.path ?: return

        for (finding in findings) {
            // Match findings to this file
            if (!finding.file.endsWith(file.name) && finding.file != filePath) continue

            val line = finding.line - 1 // 0-indexed
            if (line < 0 || line >= document.lineCount) continue

            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val range = TextRange(lineStart, lineEnd)

            val severity = when (finding.severity.lowercase()) {
                "critical" -> HighlightSeverity.ERROR
                "high"     -> HighlightSeverity.ERROR
                "medium"   -> HighlightSeverity.WARNING
                "low"      -> HighlightSeverity.WEAK_WARNING
                else       -> HighlightSeverity.INFORMATION
            }

            val cwes = if (finding.cwe.isNotEmpty()) " [${finding.cwe.joinToString(", ")}]" else ""
            val message = "${finding.rule_id}: ${finding.title}${cwes}\n${finding.message}"

            holder.newAnnotation(severity, "Cybrium: ${finding.title}")
                .range(range)
                .tooltip(message)
                .create()
        }
    }
}
