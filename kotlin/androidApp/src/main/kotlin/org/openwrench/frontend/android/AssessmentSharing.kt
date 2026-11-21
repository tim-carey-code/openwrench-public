package org.openwrench.frontend.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val AI_ASSESSMENT_TITLE = "AI assessment — not a confirmed diagnosis"
private const val ASSESSMENT_FILENAME = "openwrench-ai-assessment.md"

internal fun assessmentExportText(markdown: String): String = "# $AI_ASSESSMENT_TITLE\n\n$markdown"

class AssessmentShareProvider : FileProvider()

internal fun ComponentActivity.copyAssessment(exportText: String) {
    val clip = ClipData.newPlainText(AI_ASSESSMENT_TITLE, exportText)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(this, R.string.ai_assessment_copied, Toast.LENGTH_SHORT).show()
    }
}

internal fun ComponentActivity.shareAssessment(exportText: String) {
    lifecycleScope.launch {
        try {
            val file = withContext(Dispatchers.IO) {
                val root = File(cacheDir, "assessment-share")
                if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create share cache")
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
                // Keep each URI tied to its own assessment across repeated shares.
                val folder = File(root, UUID.randomUUID().toString())
                if (!folder.mkdir()) throw IOException("Cannot create assessment directory")
                File(folder, ASSESSMENT_FILENAME).apply { writeText(exportText) }
            }
            val uri = FileProvider.getUriForFile(this@shareAssessment, "$packageName.assessment-share", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, ASSESSMENT_FILENAME, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_ai_assessment)))
        } catch (_: IOException) {
            Toast.makeText(this@shareAssessment, R.string.ai_assessment_share_failed, Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this@shareAssessment, R.string.ai_assessment_share_failed, Toast.LENGTH_LONG).show()
        }
    }
}
