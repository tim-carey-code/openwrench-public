package org.openwrench.frontend.android

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

private const val RELAY_URL = ""

internal val diagnosisAiRequests = DiagnosisAiRequests(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

internal fun ComponentActivity.showDiagnosisAiDialog(diagnosis: DiagnosisCoordinator, id: String) {
    showEvidenceAiDialog(id) { diagnosis.aiReport(id) }
}

internal fun ComponentActivity.showImportedDriveAiDialog(drive: SavedDrive) {
    lifecycleScope.launch {
        try {
            val report = withContext(Dispatchers.IO) { importedDriveAiReport(drive) }
            val digest = MessageDigest.getInstance("SHA-256").digest(report.toByteArray())
            val key = digest.joinToString("") { "%02x".format(it) }
            showEvidenceAiDialog("imported-drive:${drive.id}:$key") { report }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showAiMessage("Cannot prepare analysis", "Check the imported drive file. No report was sent.")
        }
    }
}

private fun ComponentActivity.showEvidenceAiDialog(
    id: String,
    startNew: Boolean = false,
    readReport: suspend () -> String,
) {
    if (RELAY_URL.isBlank()) {
        showAiMessage(
            "Hosted analysis not configured",
            "No report was sent. Export the diagnosis ZIP to review or share the evidence manually.",
        )
        return
    }
    val pending = diagnosisAiRequests.pending.value
    if (pending != null) {
        if (pending == id) showPendingAnalysis(id)
        else showAiMessage("Analysis in progress", "Wait for the other diagnosis to finish before sending another report.")
        return
    }
    val previous = diagnosisAiRequests.result(id)
    if (previous != null && !startNew) {
        showAnalysisResult(previous) { showEvidenceAiDialog(id, startNew = true, readReport = readReport) }
        return
    }
    val previewRevision = diagnosisAiRequests.revision
    val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun input(label: String, type: Int) = EditText(this).apply {
        hint = label
        inputType = type
        fields.addView(this)
    }
    val symptom = input("Symptom, whether it occurred, and approximate timing", InputType.TYPE_CLASS_TEXT)
    val scroll = ScrollView(this).apply { addView(fields) }
    AlertDialog.Builder(this)
        .setTitle("Analyze with AI")
        .setMessage("Review the saved report before sharing. Only the report and your symptom note will be sent to the OpenWrench relay and the provider shown in the preview. Full readings files and archives stay on this device.")
        .setView(scroll)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Preview") { _, _ ->
            val driverNote = symptom.text.toString().trim()
            lifecycleScope.launch {
                try {
                    val report = readReport()
                    val evidence = DiagnosticAiEvidence(driverNote, report)
                    val provider = try {
                        withContext(Dispatchers.IO) { readProvider() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        showAiMessage("Analysis unavailable", "Could not identify the AI provider. No report was sent.")
                        return@launch
                    }
                    AlertDialog.Builder(this@showEvidenceAiDialog)
                        .setTitle("Send to ${provider.name}?")
                        .setMessage(formatEvidencePreview(evidence.text))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send") { _, _ ->
                            if (diagnosisAiRequests.submit(id, previewRevision) {
                                    withContext(Dispatchers.IO) { requestAssessment(evidence.body, provider.revision) }
                                } || diagnosisAiRequests.pending.value == id
                            ) {
                                showPendingAnalysis(id)
                            } else if (diagnosisAiRequests.pending.value != null) {
                                showAiMessage("Analysis in progress", "Wait for the other diagnosis to finish before sending another report.")
                            } else {
                                showAiMessage("Preview expired", "No report was sent. Open Analyze with AI again.")
                            }
                        }.show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showAiMessage("Cannot prepare analysis", "Check the selected evidence or use the original export. No report was sent.")
                }
            }
        }.show()
}

internal fun ComponentActivity.reopenAiAnalysis(id: String) {
    if (diagnosisAiRequests.pending.value == id) showPendingAnalysis(id)
    else diagnosisAiRequests.result(id)?.let { showAnalysisResult(it) }
}

private fun ComponentActivity.showPendingAnalysis(id: String) {
    val loading = AlertDialog.Builder(this)
        .setMessage("Analyzing saved evidence. Hiding this dialog does not stop the request.")
        .setNegativeButton("Hide", null)
        .create()
    loading.show()
    val observer = lifecycleScope.launch {
        diagnosisAiRequests.pending.first { it != id }
        val showResult = loading.isShowing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        loading.dismiss()
        if (showResult) diagnosisAiRequests.result(id)?.let { showAnalysisResult(it) }
    }
    loading.setOnDismissListener { observer.cancel() }
}

private fun ComponentActivity.showAnalysisResult(result: Result<String>, analyzeAgain: (() -> Unit)? = null) {
    val assessment = result.getOrNull()
    if (assessment != null) {
        showAiAssessment(assessment, analyzeAgain)
        return
    }
    val (title, message) = when (result.exceptionOrNull()) {
        is AnalysisProviderChanged -> "AI provider changed" to "No report was forwarded. Preview again to review the new provider."
        is AnalysisLimitReached -> "AI limit reached" to "Try again later. Your saved diagnosis is unchanged."
        else -> "Analysis unavailable" to "The report may already have been sent. Your saved diagnosis is unchanged."
    }
    AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null)
        .apply { if (analyzeAgain != null) setNeutralButton("Analyze again") { _, _ -> analyzeAgain() } }
        .show()
}

private fun formatEvidencePreview(evidence: String): CharSequence {
    // This is a consent preview: style known labels without interpreting or hiding evidence.
    val preview = SpannableString(evidence)
    val headings = setOf("Observed evidence", "Interpretation", "Coverage and recording limits",
        "Recorded drive assessment and evidence")
    var start = 0
    for (line in evidence.lineSequence()) {
        val label = when {
            line.startsWith("Driver report:") -> "Driver report:"
            line == "Saved report:" || line in headings -> line
            else -> null
        }
        if (label != null) {
            val end = start + label.length
            preview.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            preview.setSpan(RelativeSizeSpan(1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        start += line.length + 1
    }
    return preview
}

private val assessmentMarkdownParser = Parser.builder().build()
private val assessmentHtmlRenderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()

internal fun ComponentActivity.showAiAssessment(markdown: String, analyzeAgain: (() -> Unit)? = null) {
    val html = assessmentHtmlRenderer.render(assessmentMarkdownParser.parse(markdown))
    val formatted = SpannableString(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
    // Model-supplied links are text, not actions. No image loader is provided.
    formatted.getSpans(0, formatted.length, URLSpan::class.java).forEach(formatted::removeSpan)
    val exportText = assessmentExportText(markdown)
    val dialog = AlertDialog.Builder(this)
        .setTitle(AI_ASSESSMENT_TITLE)
        .setView(R.layout.ai_assessment_dialog)
        .apply { if (analyzeAgain != null) setNeutralButton("Analyze again") { _, _ -> analyzeAgain() } }
        .setPositiveButton("OK", null)
        .show()
    dialog.findViewById<TextView>(R.id.ai_assessment_body).text = formatted
    dialog.findViewById<TextView>(R.id.copy_ai_assessment).setOnClickListener { copyAssessment(exportText) }
    dialog.findViewById<TextView>(R.id.share_ai_assessment).setOnClickListener { shareAssessment(exportText) }
}

private fun ComponentActivity.showAiMessage(title: String, message: String) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
}

private class AnalysisLimitReached : Exception()

private class AnalysisProviderChanged : Exception()

private data class AnalysisProvider(val name: String, val revision: String)

private fun readProvider(): AnalysisProvider {
    val info = relayRequest("/provider")
    val name = info.getValue("name").jsonPrimitive.content
    val revision = info.getValue("revision").jsonPrimitive.content
    require(name.isNotBlank() && name.length <= 80 && revision.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,150}")))
    return AnalysisProvider(name, revision)
}

private fun requestAssessment(body: ByteArray, revision: String): String =
    relayRequest("/analyze", body, revision).getValue("assessment").jsonPrimitive.content

private fun relayRequest(path: String, body: ByteArray? = null, revision: String? = null): JsonObject {
    val connection = URI(RELAY_URL + path).toURL().openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = if (body == null) 10_000 else 55_000
        connection.instanceFollowRedirects = false
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("OpenWrench-AI-Provider", checkNotNull(revision))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        if (status == 409) throw AnalysisProviderChanged()
        if (status == 429) throw AnalysisLimitReached()
        check(status == 200)
        val response = connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(32_768)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read == -1) break
                count += read
            }
            check(count < buffer.size)
            String(buffer, 0, count)
        }
        return Json.parseToJsonElement(response).jsonObject
    } finally {
        connection.disconnect()
    }
}
