@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.usagi.editorvideo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.min

private val TABS = listOf("Clipes", "Texto", "Cor", "Áudio")

// ---------- helpers de linha do tempo ----------

private fun clipItem(c: Clip): MediaItem = MediaItem.Builder()
    .setUri(c.uri)
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(c.trimStartMs)
            .setEndPositionMs(c.trimEndMs)
            .build()
    )
    .build()

private fun clipStartMs(p: Project, index: Int): Long =
    p.clips.take(index).sumOf { it.lengthMs }

private fun seekTimeline(player: Player, p: Project, ms: Long) {
    var acc = 0L
    for ((i, c) in p.clips.withIndex()) {
        if (ms < acc + c.lengthMs || i == p.clips.lastIndex) {
            player.seekTo(i, (ms - acc).coerceIn(0L, c.lengthMs))
            return
        }
        acc += c.lengthMs
    }
}

private fun timelineOf(player: Player, p: Project): Long {
    val idx = min(player.currentMediaItemIndex, p.clips.size)
    return clipStartMs(p, idx) + player.currentPosition.coerceAtLeast(0L)
}

// ---------- tela ----------

@Composable
fun EditorScreen(vm: VideoViewModel) {
    val ctx = LocalContext.current
    val project by vm.project.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val exportState by vm.export.collectAsState()
    val loading by vm.loading.collectAsState()
    val projectNow = rememberUpdatedState(project)

    val player = remember { ExoPlayer.Builder(ctx).build() }
    val music = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release(); music.release() } }

    var timelineMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    val total = project.totalMs

    // playlist do player acompanha os clipes (e cortes)
    val clipsKey = project.clips.map { listOf(it.id, it.trimStartMs, it.trimEndMs) }
    LaunchedEffect(clipsKey) {
        val p = projectNow.value
        val keep = min(timelineMs, max(0L, p.totalMs - 1))
        player.setMediaItems(p.clips.map { clipItem(it) })
        player.prepare()
        seekTimeline(player, p, keep)
    }

    LaunchedEffect(project.color) { player.setVideoEffects(colorEffects(project.color)) }

    // música (apenas pré-visualização)
    LaunchedEffect(project.music?.uri) {
        val m = projectNow.value.music
        if (m == null) {
            music.clearMediaItems()
        } else {
            music.setMediaItem(MediaItem.fromUri(m.uri))
            music.prepare()
            music.seekTo(timelineMs)
            if (playing) music.play()
        }
    }
    LaunchedEffect(project.music?.volume) { music.volume = project.music?.volume ?: 0f }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (projectNow.value.music != null) {
                    if (isPlaying) {
                        music.seekTo(timelineOf(player, projectNow.value))
                        music.play()
                    } else music.pause()
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // posição atual na linha do tempo + volume por clipe
    LaunchedEffect(Unit) {
        while (true) {
            val p = projectNow.value
            if (!scrubbing && p.clips.isNotEmpty() && player.mediaItemCount == p.clips.size) {
                timelineMs = timelineOf(player, p)
                p.clips.getOrNull(player.currentMediaItemIndex)?.let {
                    player.volume = if (it.muted) 0f else 1f
                }
            }
            kotlinx.coroutines.delay(40)
        }
    }

    LaunchedEffect(exportState) {
        if (exportState is ExportState.Running) { player.pause(); music.pause() }
    }

    val addClipsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addClips(uris) }

    var confirmClose by remember { mutableStateOf(false) }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Fechar projeto?") },
            text = { Text("O projeto salvo será apagado. Vídeos já exportados não são afetados.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.closeProject() }) { Text("Apagar e fechar") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancelar") } }
        )
    }

    // a notificação de progresso é opcional: exporta mesmo se a permissão for negada
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startExport() }

    var showExportOptions by remember { mutableStateOf(false) }
    if (showExportOptions) {
        ExportOptionsDialog(
            project = project,
            vm = vm,
            onConfirm = {
                showExportOptions = false
                val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startExport()
            },
            onDismiss = { showExportOptions = false }
        )
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        // barra superior
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { confirmClose = true }) { Text("Fechar") }
            Button(
                onClick = { showExportOptions = true },
                enabled = exportState !is ExportState.Running && !loading
            ) {
                Text("Exportar")
            }
        }

        // pré-visualização
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val aspect = project.outputWidth.toFloat() / project.outputHeight
            BoxWithConstraints(Modifier.aspectRatio(aspect)) {
                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        PlayerView(c).also { v ->
                            v.useController = false
                            v.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            v.player = player
                        }
                    },
                    update = { v ->
                        v.resizeMode = if (project.fillFrame) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                )
                project.texts.filter { timelineMs in it.startMs until it.endMs }.forEach { t ->
                    val sizePx = t.size.px * project.textScale / project.outputHeight.toFloat() * heightPx
                    Text(
                        t.text,
                        color = Color(t.colorArgb),
                        fontSize = with(LocalDensity.current) { sizePx.toSp() },
                        modifier = Modifier.align(BiasAlignment(0f, -t.position.anchorY))
                    )
                }
            }
        }

        // controles de reprodução
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (playing) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) seekTimeline(player, project, 0L)
                    player.play()
                }
            }) { Text(if (playing) "Pausar" else "Tocar") }
            Slider(
                modifier = Modifier.weight(1f),
                value = timelineMs.toFloat().coerceIn(0f, max(1f, total.toFloat())),
                valueRange = 0f..max(1f, total.toFloat()),
                onValueChange = {
                    scrubbing = true
                    timelineMs = it.toLong()
                    seekTimeline(player, project, timelineMs)
                },
                onValueChangeFinished = {
                    scrubbing = false
                    if (project.music != null) music.seekTo(timelineMs)
                },
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
            )
            Text("${formatTime(timelineMs)} / ${formatTime(total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // linha do tempo de clipes
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(project.clips, key = { _, c -> c.id }) { i, c ->
                val selected = c.id == selectedId
                Box(
                    Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable {
                            vm.select(c.id)
                            timelineMs = clipStartMs(project, i)
                            seekTimeline(player, project, timelineMs)
                        }
                ) {
                    c.thumb?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        (if (c.muted) "🔇 " else "") + formatTime(c.lengthMs),
                        fontSize = 11.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0x99000000), RoundedCornerShape(topStart = 6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            item {
                Box(
                    Modifier.size(width = 64.dp, height = 64.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            addClipsLauncher.launch(arrayOf("video/*"))
                        },
                    contentAlignment = Alignment.Center
                ) { Text("+", fontSize = 28.sp) }
            }
        }

        // abas
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.fillMaxWidth().height(230.dp).background(MaterialTheme.colorScheme.surface)) {
            when (tab) {
                0 -> ClipsTab(vm, project, selectedId, timelineMs)
                1 -> TextTab(vm, project, timelineMs)
                2 -> ColorTab(vm, project)
                3 -> AudioTab(vm, project)
            }
        }
    }

}

/** Diálogo de exportação. Fica fora do editor para aparecer em qualquer tela. */
@Composable
fun ExportDialog(vm: VideoViewModel) {
    val exportState by vm.export.collectAsState()
    when (val s = exportState) {
        is ExportState.Idle -> Unit
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exportando…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${s.percent}%")
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelExport() }) { Text("Cancelar") } }
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Pronto!") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissExport() }) { Text("OK") } }
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Ops") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissExport() }) { Text("OK") } }
        )
    }
}

// ---------- opções de exportação ----------

@Composable
private fun ExportOptionsDialog(project: Project, vm: VideoViewModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar vídeo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Proporção", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AspectMode.entries.forEach { a ->
                        FilterChip(selected = project.aspect == a, onClick = { vm.setAspect(a) }, label = { Text(a.label, fontSize = 12.sp) })
                    }
                }
                Text("Qualidade", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Quality.entries.forEach { q ->
                        FilterChip(selected = project.quality == q, onClick = { vm.setQuality(q) }, label = { Text(q.label, fontSize = 12.sp) })
                    }
                }
                Text("Ajuste do vídeo no quadro", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !project.fillFrame, onClick = { vm.setFillFrame(false) }, label = { Text("Caber (com bordas)", fontSize = 12.sp) })
                    FilterChip(selected = project.fillFrame, onClick = { vm.setFillFrame(true) }, label = { Text("Preencher (corta)", fontSize = 12.sp) })
                }
                Spacer(Modifier.height(4.dp))
                Text("Saída: ${project.outputWidth}x${project.outputHeight} · MP4", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Exportar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------- aba Clipes ----------

@Composable
private fun ClipsTab(vm: VideoViewModel, project: Project, selectedId: Long?, timelineMs: Long) {
    val ctx = LocalContext.current
    val clip = project.clips.firstOrNull { it.id == selectedId }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (clip == null) {
            Text("Toque num clipe na linha do tempo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        var range by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
            mutableStateOf(clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat())
        }
        Text(
            "Cortar: ${formatTime(range.start.toLong())} – ${formatTime(range.endInclusive.toLong())}  (${formatTime((range.endInclusive - range.start).toLong())})",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..clip.durationMs.toFloat(),
            onValueChangeFinished = {
                var s = range.start.toLong()
                var e = range.endInclusive.toLong()
                if (e - s < 500) e = min(clip.durationMs, s + 500).also { if (it - s < 500) s = it - 500 }
                vm.trim(clip.id, s, e)
            }
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (!vm.splitAt(timelineMs)) Toast.makeText(ctx, "Posicione o cursor no meio de um clipe.", Toast.LENGTH_SHORT).show()
            }) { Text("Dividir aqui") }
            OutlinedButton(onClick = { vm.moveClip(clip.id, -1) }) { Text("◀") }
            OutlinedButton(onClick = { vm.moveClip(clip.id, 1) }) { Text("▶") }
            FilterChip(selected = clip.muted, onClick = { vm.toggleMute(clip.id) }, label = { Text("Sem áudio") })
            OutlinedButton(onClick = { vm.deleteClip(clip.id) }) { Text("Excluir") }
        }
    }
}

// ---------- aba Texto ----------

@Composable
private fun TextTab(vm: VideoViewModel, project: Project, timelineMs: Long) {
    var editing by remember { mutableStateOf<TextItem?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val start = min(timelineMs, max(0L, project.totalMs - 500))
            editing = TextItem(vm.newTextId(), "", start, min(project.totalMs, start + 3000))
        }) { Text("+ Adicionar texto") }
        project.texts.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).clickable { editing = t }) {
                    Text(t.text, maxLines = 1)
                    Text("${formatTime(t.startMs)} – ${formatTime(t.endMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.deleteText(t.id) }) { Text("Apagar") }
            }
        }
    }
    editing?.let { item ->
        TextDialog(item, project.totalMs, onSave = { vm.saveText(it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun TextDialog(initial: TextItem, totalMs: Long, onSave: (TextItem) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.text) }
    var range by remember { mutableStateOf(initial.startMs.toFloat()..initial.endMs.toFloat()) }
    var pos by remember { mutableStateOf(initial.position) }
    var size by remember { mutableStateOf(initial.size) }
    var color by remember { mutableIntStateOf(initial.colorArgb) }
    val maxMs = max(1000L, totalMs).toFloat()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Texto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { if (it.length <= 60) text = it }, singleLine = true, label = { Text("Texto") })
                Text("Aparece de ${formatTime(range.start.toLong())} até ${formatTime(range.endInclusive.toLong())}", fontSize = 12.sp)
                RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..maxMs)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextPos.entries.forEach { p -> FilterChip(selected = pos == p, onClick = { pos = p }, label = { Text(p.label, fontSize = 12.sp) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextSize.entries.forEach { s -> FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.label, fontSize = 12.sp) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLORS.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .border(if (color == c) 3.dp else 1.dp, if (color == c) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && range.endInclusive - range.start >= 300f,
                onClick = {
                    onSave(initial.copy(text = text.trim(), startMs = range.start.toLong(), endMs = range.endInclusive.toLong(), position = pos, size = size, colorArgb = color))
                }
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------- aba Cor ----------

@Composable
private fun ColorTab(vm: VideoViewModel, project: Project) {
    val c = project.color
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COLOR_PRESETS.forEach { (name, preset) ->
                FilterChip(selected = c == preset, onClick = { vm.setColor(preset) }, label = { Text(name) })
            }
        }
        LabeledSlider("Brilho", c.brightness, -0.5f..0.5f) { vm.setColor(c.copy(brightness = it)) }
        LabeledSlider("Contraste", c.contrast, -0.5f..0.5f) { vm.setColor(c.copy(contrast = it)) }
        LabeledSlider("Saturação", c.saturation, -1f..1f) { vm.setColor(c.copy(saturation = it)) }
        LabeledSlider("Temperatura", c.temperature, -1f..1f) { vm.setColor(c.copy(temperature = it)) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%+d".format((value / (range.endInclusive - range.start) * 200).toInt()), fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

// ---------- aba Áudio ----------

@Composable
private fun AudioTab(vm: VideoViewModel, project: Project) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setMusic(uri)
    }
    val m = project.music
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text(if (m == null) "+ Adicionar música" else "Trocar música") }
        if (m != null) {
            Text(m.name, maxLines = 1)
            Text("Volume da música: ${(m.volume * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = m.volume, onValueChange = { vm.setMusicVolume(it) }, valueRange = 0f..1f)
            OutlinedButton(onClick = { vm.removeMusic() }) { Text("Remover música") }
        }
        Text(
            "A música toca junto com o áudio original dos clipes. Para silenciar um clipe, use \"Sem áudio\" na aba Clipes.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
