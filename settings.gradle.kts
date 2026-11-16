package com.example.videodl

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        urlInput = EditText(this).apply { hint = "Pega o comparte un link de X / YouTube" }
        button = Button(this).apply { text = "Descargar HD" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        status = TextView(this)

        listOf(urlInput, button, progress, status).forEach {
            layout.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(layout)

        button.setOnClickListener { startDownload(urlInput.text.toString()) }

        handleIntent(intent)
        updateYtDlp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = Regex("https?://\\S+").find(text)?.value ?: return
            urlInput.setText(url)
            startDownload(url)   // descarga automática al compartir
        }
    }

    /** YouTube cambia a menudo: mantener yt-dlp actualizado es clave. */
    private fun updateYtDlp() = lifecycleScope.launch(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
        }
    }

    private fun startDownload(url: String) {
        if (!url.startsWith("http")) {
            status.text = "Link no válido"
            return
        }
        button.isEnabled = false
        progress.progress = 0
        status.text = "Iniciando…"

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(url) }
                status.text = "✅ Guardado en Películas/VideoDL:\n${file.name}"
            } catch (e: Exception) {
                status.text = "❌ Error: ${e.message}"
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun download(url: String): File {
        val tmpDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "tmp").apply {
            deleteRecursively(); mkdirs()
        }

        val request = YoutubeDLRequest(url).apply {
            // Mejor calidad hasta 1080p (cambia a 2160 para 4K)
            addOption("-f", "bv*[height<=1080]+ba/b[height<=1080]/best")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("-o", "${tmpDir.absolutePath}/%(title).80s.%(ext)s")
        }

        YoutubeDL.getInstance().execute(request) { pct, _, line ->
            runOnUiThread {
                progress.progress = pct.toInt().coerceIn(0, 100)
                status.text = line
            }
        }

        val video = tmpDir.listFiles()?.firstOrNull { it.extension == "mp4" }
            ?: tmpDir.listFiles()?.firstOrNull()
            ?: error("No se encontró el archivo descargado")

        return saveToGallery(video)
    }

    /** Copia el video a la galería (Películas/VideoDL) sin pedir permisos. */
    private fun saveToGallery(file: File): File {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoDL")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el archivo en la galería")
        contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        file.delete()
        return file
    }
}
