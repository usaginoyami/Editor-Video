@file:OptIn(UnstableApi::class)

package com.usagi.editorvideo

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.min

/** Efeitos de cor, usados tanto na pré-visualização quanto na exportação. */
fun colorEffects(c: ColorSettings): List<Effect> {
    val list = mutableListOf<Effect>()
    if (c.brightness != 0f) list += Brightness(c.brightness)
    if (c.contrast != 0f) list += Contrast(c.contrast)
    if (c.saturation != 0f) list += HslAdjustment.Builder().adjustSaturation(c.saturation * 100f).build()
    if (c.temperature != 0f) {
        list += RgbAdjustment.Builder()
            .setRedScale(1f + c.temperature * 0.25f)
            .setBlueScale(1f - c.temperature * 0.25f)
            .build()
    }
    return list
}

/** Texto que só aparece entre startMs e endMs (tempo da linha do tempo). */
private class TimedTextOverlay(private val item: TextItem) : TextOverlay() {
    private fun visible(timeUs: Long): Boolean = timeUs / 1000 in item.startMs until item.endMs

    override fun getText(presentationTimeUs: Long): SpannableString {
        val s = SpannableString(if (visible(presentationTimeUs)) item.text else " ")
        s.setSpan(ForegroundColorSpan(item.colorArgb), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(item.size.px), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return s
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, item.position.anchorY)
            .setAlphaScale(if (visible(presentationTimeUs)) 1f else 0f)
            .build()
}

class Exporter(private val context: Context) {

    private var transformer: Transformer? = null

    fun start(
        project: Project,
        outFile: File,
        onDone: () -> Unit,
        onFail: (String) -> Unit
    ) {
        val items = project.clips.map { c ->
            val mi = MediaItem.Builder()
                .setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.trimStartMs)
                        .setEndPositionMs(c.trimEndMs)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(mi).setRemoveAudio(c.muted).build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(items).build())

        project.music?.let { m ->
            val end = min(m.durationMs, project.totalMs)
            val mi = MediaItem.Builder()
                .setUri(m.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(end).build()
                )
                .build()
            val mixer = ChannelMixingAudioProcessor()
            for (ch in 1..2) {
                mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(m.volume))
            }
            val music = EditedMediaItem.Builder(mi)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(mixer), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(music).build()
        }

        val videoEffects = mutableListOf<Effect>()
        videoEffects += Presentation.createForWidthAndHeight(
            project.outputWidth, project.outputHeight, Presentation.LAYOUT_SCALE_TO_FIT
        )
        videoEffects += colorEffects(project.color)
        if (project.texts.isNotEmpty()) {
            videoEffects += OverlayEffect(project.texts.map { TimedTextOverlay(it) })
        }

        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .experimentalSetForceAudioTrack(true)
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onFail(exportException.message ?: "Erro desconhecido")
                }
            })
            .build()
        transformer = t
        t.start(composition, outFile.absolutePath)
    }

    /** Progresso de 0 a 100, ou -1 se ainda não disponível. Chamar na thread principal. */
    fun progress(): Int {
        val t = transformer ?: return -1
        val holder = ProgressHolder()
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }
}
