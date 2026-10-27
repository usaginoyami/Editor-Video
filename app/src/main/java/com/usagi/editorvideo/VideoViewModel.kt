package com.usagi.editorvideo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val _project = MutableStateFlow(Project())
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    val export: StateFlow<ExportState> = ExportManager.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var nextId = 1L

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
        ExportManager.cancel()
        _project.value = Project()
        _selectedId.value = null
    }

    // ---------- exportar (feito pelo ExportManager, que sobrevive à tela) ----------

    fun startExport() = ExportManager.start(getApplication(), _project.value)

    fun cancelExport() = ExportManager.cancel()

    fun dismissExport() = ExportManager.dismiss()

    companion object {
        const val MIN_PART_MS = 300L
    }
}
