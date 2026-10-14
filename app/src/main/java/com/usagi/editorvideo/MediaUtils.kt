package com.usagi.editorvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.max

object MediaUtils {

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val thumb: Bitmap?)

    fun readVideo(ctx: Context, uri: Uri): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumb = frame?.let {
                val s = 240f / max(it.width, it.height)
                if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), (it.height * s).toInt().coerceAtLeast(1), true) else it
            }
            VideoInfo(duration, w, h, thumb)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun readAudio(ctx: Context, uri: Uri): Music? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            Music(uri, displayName(ctx, uri) ?: "Música", duration)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }
}
