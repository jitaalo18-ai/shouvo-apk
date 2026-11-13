package com.videoeditor.app.data

import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * The clips inside a compound clip are stored as JSON in one column of the compound's row.
 * Nested compounds are stored inside their parent the same way. Unknown or broken data gives
 * an empty list rather than a crash (the compound then simply plays nothing).
 */
object ClipJson {
    fun encode(clips: List<Clip>): String {
        val arr = JSONArray()
        for (c in clips) arr.put(toJson(c))
        return arr.toString()
    }

    fun decode(json: String?): List<Clip> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(c: Clip): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("track", c.track.name)
        put("kind", c.kind.name)
        put("sourceUri", c.sourceUri)
        put("mimeType", c.mimeType ?: JSONObject.NULL)
        put("name", c.name)
        put("libraryItemId", c.libraryItemId ?: JSONObject.NULL)
        put("sourceDurationMs", c.sourceDurationMs)
        put("sourceStartMs", c.sourceStartMs)
        put("sourceEndMs", c.sourceEndMs)
        put("timelineStartMs", c.timelineStartMs)
        put("hasAudio", c.hasAudio)
        put("aspect", c.aspect.toDouble())
        put("layer", c.layer)
        put("posX", c.posX.toDouble())
        put("posY", c.posY.toDouble())
        put("scale", c.scale.toDouble())
        put("rotation", c.rotation.toDouble())
        put("stretchX", c.stretchX.toDouble())
        put("stretchY", c.stretchY.toDouble())
        if (c.text.isNotEmpty()) put("text", c.text)
        if (c.style.isNotEmpty()) put("style", c.style)
        if (c.children.isNotEmpty()) {
            val arr = JSONArray()
            for (child in c.children) arr.put(toJson(child))
            put("children", arr)
        }
    }

    private fun fromJson(o: JSONObject): Clip {
        val children = o.optJSONArray("children")?.let { arr -> (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) } }
        return Clip(
            id = o.getString("id"),
            track = Track.valueOf(o.optString("track", Track.MAIN.name)),
            kind = MediaKind.valueOf(o.getString("kind")),
            sourceUri = o.optString("sourceUri", ""),
            mimeType = if (o.isNull("mimeType")) null else o.optString("mimeType"),
            name = o.optString("name", ""),
            libraryItemId = if (o.isNull("libraryItemId") || !o.has("libraryItemId")) null else o.getLong("libraryItemId"),
            sourceDurationMs = o.getLong("sourceDurationMs"),
            sourceStartMs = o.getLong("sourceStartMs"),
            sourceEndMs = o.getLong("sourceEndMs"),
            timelineStartMs = o.getLong("timelineStartMs"),
            hasAudio = o.optBoolean("hasAudio", true),
            aspect = o.optDouble("aspect", 1.0).toFloat(),
            layer = o.optInt("layer", 0),
            posX = o.optDouble("posX", 0.5).toFloat(),
            posY = o.optDouble("posY", 0.5).toFloat(),
            scale = o.optDouble("scale", 0.4).toFloat(),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
            stretchX = o.optDouble("stretchX", 1.0).toFloat(),
            stretchY = o.optDouble("stretchY", 1.0).toFloat(),
            children = children ?: emptyList(),
            text = o.optString("text", ""),
            style = o.optString("style", ""),
        )
    }
}
