package com.usagi.editorvideo

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dono da exportação. Vive no processo (não na Activity nem no ViewModel), então a exportação
 * continua com o app em segundo plano, mantida viva pelo [ExportService].
 * Todas as funções devem ser chamadas na thread principal.
 */
object ExportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var exporter: Exporter? = null
    private var tmpFile: File? = null
    private var saving = false

    fun start(context: Context, project: Project) {
        if (project.clips.isEmpty() || _state.value is ExportState.Running) return
        val app = context.applicationContext

        // restos de exportações anteriores (processo morto, cancelamento antigo)
        app.cacheDir.listFiles { f -> f.name.startsWith("export_") }?.forEach { it.delete() }

        val tmp = File(app.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val ex = Exporter(app)
        exporter = ex
        tmpFile = tmp
        _state.value = ExportState.Running(0)
        ExportService.start(app)

        try {
            ex.start(
                project, tmp,
                onDone = { saveToGallery(app, tmp) },
                onFail = { msg -> fail("Falha ao exportar: $msg") }
            )
        } catch (e: Exception) {
            fail("Falha ao exportar: ${e.message}")
            return
        }

        scope.launch {
            while (_state.value is ExportState.Running && exporter === ex) {
                val pr = ex.progress()
                if (pr >= 0 && !saving) _state.value = ExportState.Running(pr)
                delay(300)
            }
        }
    }

    /** Cancela a exportação em andamento e apaga o arquivo temporário. */
    fun cancel() {
        if (saving) return // já terminou de renderizar; só falta copiar para a galeria
        exporter?.cancel()
        tmpFile?.delete()
        reset()
        _state.value = ExportState.Idle
    }

    /** Fecha o aviso de "Pronto"/"Falha". */
    fun dismiss() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    private fun fail(message: String) {
        tmpFile?.delete()
        reset()
        _state.value = ExportState.Failed(message)
    }

    private fun reset() {
        exporter = null
        tmpFile = null
        saving = false
    }

    private fun saveToGallery(app: Context, tmp: File) {
        saving = true
        _state.value = ExportState.Running(100)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EditorVideo_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EditorVideo")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Throwable) {
                    false
                } finally {
                    tmp.delete()
                }
            }
            reset()
            _state.value = if (ok) ExportState.Done("Vídeo salvo em Filmes/EditorVideo")
            else ExportState.Failed("Não consegui salvar o vídeo.")
        }
    }
}
