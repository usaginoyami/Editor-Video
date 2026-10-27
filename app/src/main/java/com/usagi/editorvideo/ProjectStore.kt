package com.usagi.editorvideo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Salva e reabre o projeto em JSON (um projeto por vez, em arquivos internos do app). */
object ProjectStore {

    data class Saved(val project: Project, val nextId: Long)

    private fun file(ctx: Context) = File(ctx.filesDir, "project.json")

    fun save(ctx: Context, p: Project, nextId: Long) {
        val json = JSONObject().apply {
            put("version", 1)
            put("nextId", nextId)
            put("clips", JSONArray().also { a ->
                p.clips.forEach { c ->
                    a.put(JSONObject().apply {
                        put("id", c.id)
                        put("uri", c.uri.toString())
                        put("durationMs", c.durationMs)
                        put("width", c.width)
                        put("height", c.height)
                        put("trimStartMs", c.trimStartMs)
                        put("trimEndMs", c.trimEndMs)
                        put("muted", c.muted)
                    })
                }
            })
            put("texts", JSONArray().also { a ->
                p.texts.forEach { t ->
                    a.put(JSONObject().apply {
                        put("id", t.id)
                        put("text", t.text)
                        put("startMs", t.startMs)
                        put("endMs", t.endMs)
                        put("position", t.position.name)
                        put("size", t.size.name)
                        put("colorArgb", t.colorArgb)
                    })
                }
            })
            p.music?.let { m ->
                put("music", JSONObject().apply {
                    put("uri", m.uri.toString())
                    put("name", m.name)
                    put("durationMs", m.durationMs)
                    put("volume", m.volume.toDouble())
                })
            }
            put("color", JSONObject().apply {
                put("brightness", p.color.brightness.toDouble())
                put("contrast", p.color.contrast.toDouble())
                put("saturation", p.color.saturation.toDouble())
                put("temperature", p.color.temperature.toDouble())
            })
        }
        // grava num arquivo temporário e troca no final, para nunca deixar o projeto pela metade
        val target = file(ctx)
        val tmp = File(target.parentFile, "project.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** Devolve null se não há projeto salvo ou se o arquivo está ilegível. Clipes voltam sem miniatura. */
    fun load(ctx: Context): Saved? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val clips = o.getJSONArray("clips").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Clip(
                        id = c.getLong("id"),
                        uri = Uri.parse(c.getString("uri")),
                        durationMs = c.getLong("durationMs"),
                        width = c.getInt("width"),
                        height = c.getInt("height"),
                        trimStartMs = c.getLong("trimStartMs"),
                        trimEndMs = c.getLong("trimEndMs"),
                        muted = c.optBoolean("muted", false)
                    )
                }
            }
            val texts = o.optJSONArray("texts")?.let { a ->
                (0 until a.length()).map { i ->
                    val t = a.getJSONObject(i)
                    TextItem(
                        id = t.getLong("id"),
                        text = t.getString("text"),
                        startMs = t.getLong("startMs"),
                        endMs = t.getLong("endMs"),
                        position = runCatching { TextPos.valueOf(t.getString("position")) }.getOrDefault(TextPos.BOTTOM),
                        size = runCatching { TextSize.valueOf(t.getString("size")) }.getOrDefault(TextSize.MEDIUM),
                        colorArgb = t.optInt("colorArgb", TEXT_COLORS[0])
                    )
                }
            } ?: emptyList()
            val music = o.optJSONObject("music")?.let { m ->
                Music(
                    uri = Uri.parse(m.getString("uri")),
                    name = m.optString("name", "Música"),
                    durationMs = m.getLong("durationMs"),
                    volume = m.optDouble("volume", 0.8).toFloat()
                )
            }
            val color = o.optJSONObject("color")?.let { c ->
                ColorSettings(
                    brightness = c.optDouble("brightness", 0.0).toFloat(),
                    contrast = c.optDouble("contrast", 0.0).toFloat(),
                    saturation = c.optDouble("saturation", 0.0).toFloat(),
                    temperature = c.optDouble("temperature", 0.0).toFloat()
                )
            } ?: ColorSettings()
            Saved(Project(clips, texts, music, color), o.optLong("nextId", 1L))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
