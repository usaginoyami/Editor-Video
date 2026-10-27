package com.usagi.editorvideo

import android.graphics.Bitmap
import android.net.Uri

data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val width: Int,          // já considerando a rotação do vídeo
    val height: Int,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = durationMs,
    val muted: Boolean = false,
    val thumb: Bitmap? = null
) {
    val lengthMs: Long get() = trimEndMs - trimStartMs
}

enum class TextPos(val label: String, val anchorY: Float) {
    TOP("Topo", 0.8f), CENTER("Centro", 0f), BOTTOM("Base", -0.8f)
}

enum class TextSize(val label: String, val px: Int) {
    SMALL("Pequeno", 56), MEDIUM("Médio", 84), LARGE("Grande", 120)
}

val TEXT_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFFE63946.toInt(), 0xFF000000.toInt()
)

data class TextItem(
    val id: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val position: TextPos = TextPos.BOTTOM,
    val size: TextSize = TextSize.MEDIUM,
    val colorArgb: Int = TEXT_COLORS[0]
)

data class Music(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val volume: Float = 0.8f
)

data class ColorSettings(
    val brightness: Float = 0f,   // -0.5..0.5
    val contrast: Float = 0f,     // -0.5..0.5
    val saturation: Float = 0f,   // -1..1
    val temperature: Float = 0f   // -1..1
)

val COLOR_PRESETS = listOf(
    "Normal" to ColorSettings(),
    "Vívido" to ColorSettings(contrast = 0.15f, saturation = 0.35f),
    "Quente" to ColorSettings(temperature = 0.45f, contrast = 0.05f),
    "Frio" to ColorSettings(temperature = -0.45f, contrast = 0.05f),
    "P&B" to ColorSettings(saturation = -1f, contrast = 0.15f),
    "Cinema" to ColorSettings(contrast = 0.25f, saturation = -0.15f, temperature = 0.15f, brightness = -0.05f)
)

/** Proporção da saída. AUTO segue o primeiro clipe. */
enum class AspectMode(val label: String) {
    AUTO("Auto"), LANDSCAPE("16:9"), PORTRAIT("9:16"), SQUARE("1:1")
}

/** Qualidade da saída: [shortSide] é o lado menor do quadro em pixels. */
enum class Quality(val label: String, val shortSide: Int, val videoBitrate: Int) {
    P720("720p", 720, 6_000_000),
    P1080("1080p", 1080, 10_000_000)
}

data class Project(
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val music: Music? = null,
    val color: ColorSettings = ColorSettings(),
    val aspect: AspectMode = AspectMode.AUTO,
    val quality: Quality = Quality.P720,
    /** false: o vídeo cabe inteiro (com bordas pretas); true: preenche o quadro cortando o excesso. */
    val fillFrame: Boolean = false
) {
    val totalMs: Long get() = clips.sumOf { it.lengthMs }

    private val isPortrait: Boolean
        get() = when (aspect) {
            AspectMode.LANDSCAPE, AspectMode.SQUARE -> false
            AspectMode.PORTRAIT -> true
            AspectMode.AUTO -> clips.firstOrNull()?.let { it.width < it.height } ?: false
        }

    private val longSide: Int get() = quality.shortSide * 16 / 9

    val outputWidth: Int
        get() = when {
            aspect == AspectMode.SQUARE -> quality.shortSide
            isPortrait -> quality.shortSide
            else -> longSide
        }
    val outputHeight: Int
        get() = when {
            aspect == AspectMode.SQUARE -> quality.shortSide
            isPortrait -> longSide
            else -> quality.shortSide
        }

    /** Os tamanhos de texto foram pensados para 720p; em 1080p crescem na mesma proporção. */
    val textScale: Float get() = quality.shortSide / 720f
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val message: String) : ExportState
    data class Failed(val message: String) : ExportState
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
