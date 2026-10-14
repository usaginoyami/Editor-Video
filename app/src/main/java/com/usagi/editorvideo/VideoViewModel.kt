package com.usagi.editorvideo

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val _project = MutableStateFlow(Project())
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var nextId = 1L
    private var exporter: Exporter? = null

    fun clearMessage() { _message.value = null }
    fun select(id: Long?) { _selectedId.value = id }

    // ---------- clipes ----------

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val ctx = getApplication<Application>()
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    MediaUtils.readVideo(ctx, uri)?.let { info ->
                        Clip(nextId++, uri, info.durationMs, info.width, info.height, thumb = info.thumb)
                    }
                }
            }
            _loading.value = false
            if (added.isEmpty()) {
                _message.value = "Não consegui abrir esse vídeo."
                return@launch
            }
            _project.value = _project.value.copy(clips = _project.value.clips + added)
            if (_selectedId.value == null) _selectedId.value = added.first().id
        }
    }

    private fun updateClips(f: (List<Clip>) -> List<Clip>) {
        val p = _project.value
        val clips = f(p.clips)
        val total = clips.sumOf { it.lengthMs }
        val texts = p.texts.mapNotNull {
            if (it.startMs >= total) null else it.copy(endMs = minOf(it.endMs, total))
        }
        _project.value = p.copy(clips = clips, texts = texts)
        if (clips.none { it.id == _selectedId.value }) _selectedId.value = clips.firstOrNull()?.id
    }

    fun trim(id: Long, startMs: Long, endMs: Long) = updateClips { list ->
        list.map { if (it.id == id) it.copy(trimStartMs = startMs, trimEndMs = endMs) else it }
    }

    fun toggleMute(id: Long) = updateClips { list ->
        list.map { if (it.id == id) it.copy(muted = !it.muted) else it }
    }

    fun deleteClip(id: Long) = updateClips { list -> list.filter { it.id != id } }

    fun moveClip(id: Long, delta: Int) = updateClips { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) list else {
            val m = list.toMutableList()
            val c = m.removeAt(i)
            m.add(j, c)
            m
        }
    }

    /** Divide o clipe que contém [timelineMs]. Retorna false se o ponto estiver muito perto da borda. */
    fun splitAt(timelineMs: Long): Boolean {
        var acc = 0L
        val list = _project.value.clips
        for ((i, c) in list.withIndex()) {
            if (timelineMs < acc + c.lengthMs || i == list.lastIndex) {
                val offset = timelineMs - acc
                if (offset < MIN_PART_MS || c.lengthMs - offset < MIN_PART_MS) return false
                val cut = c.trimStartMs + offset
                val first = c.copy(trimEndMs = cut)
                val second = c.copy(id = nextId++, trimStartMs = cut)
                updateClips { l -> l.flatMap { if (it.id == c.id) listOf(first, second) else listOf(it) } }
                _selectedId.value = second.id
                return true
            }
            acc += c.lengthMs
        }
        return false
    }

    // ---------- texto ----------

    fun newTextId(): Long = nextId++

    fun saveText(item: TextItem) {
        val p = _project.value
        val exists = p.texts.any { it.id == item.id }
        _project.value = p.copy(texts = if (exists) p.texts.map { if (it.id == item.id) item else it } else p.texts + item)
    }

    fun deleteText(id: Long) {
        _project.value = _project.value.let { it.copy(texts = it.texts.filter { t -> t.id != id }) }
    }

    // ---------- cor ----------

    fun setColor(c: ColorSettings) { _project.value = _project.value.copy(color = c) }

    // ---------- áudio ----------

    fun setMusic(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val m = withContext(Dispatchers.IO) { MediaUtils.readAudio(ctx, uri) }
            if (m == null) _message.value = "Não consegui abrir esse áudio."
            else _project.value = _project.value.copy(music = m)
        }
    }

    fun setMusicVolume(v: Float) {
        _project.value = _project.value.let { p -> p.copy(music = p.music?.copy(volume = v)) }
    }

    fun removeMusic() { _project.value = _project.value.copy(music = null) }

    // ---------- projeto ----------

    fun closeProject() {
        exporter?.cancel()
        exporter = null
        _export.value = ExportState.Idle
        _project.value = Project()
        _selectedId.value = null
    }

    // ---------- exportar ----------

    fun startExport() {
        val p = _project.value
        if (p.clips.isEmpty() || _export.value is ExportState.Running) return
        val ctx = getApplication<Application>()
        val tmp = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val ex = Exporter(ctx)
        exporter = ex
        _export.value = ExportState.Running(0)
        try {
            ex.start(
                p, tmp,
                onDone = { saveToGallery(tmp) },
                onFail = { msg ->
                    tmp.delete()
                    _export.value = ExportState.Failed("Falha ao exportar: $msg")
                }
            )
        } catch (e: Exception) {
            _export.value = ExportState.Failed("Falha ao exportar: ${e.message}")
            return
        }
        viewModelScope.launch {
            while (_export.value is ExportState.Running) {
                val pr = ex.progress()
                if (pr >= 0) _export.value = ExportState.Running(pr)
                delay(300)
            }
        }
    }

    fun cancelExport() {
        exporter?.cancel()
        exporter = null
        _export.value = ExportState.Idle
    }

    fun dismissExport() { _export.value = ExportState.Idle }

    private fun saveToGallery(tmp: File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
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
            _export.value = if (ok) ExportState.Done("Vídeo salvo em Filmes/EditorVideo")
            else ExportState.Failed("Não consegui salvar o vídeo.")
        }
    }

    companion object {
        const val MIN_PART_MS = 300L
    }
}
