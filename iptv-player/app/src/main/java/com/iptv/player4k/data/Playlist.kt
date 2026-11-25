package com.iptv.player4k.data

import org.json.JSONObject
import java.net.URLEncoder

enum class PlaylistType { URL, XTREAM, FILE }

data class Playlist(
    val id: String,
    val name: String,
    val type: PlaylistType,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    /** "ts" ou "m3u8" (somente Xtream). */
    val output: String = "ts",
    /** content:// do arquivo escolhido (somente FILE). */
    val sourceUri: String = "",
    val userAgent: String = "",
    val updatedAt: Long = 0L,
) {
    /** URL efetiva da lista M3U. */
    fun resolvedUrl(): String = when (type) {
        PlaylistType.XTREAM -> {
            val base = normalizeServer(server)
            "$base/get.php?username=${enc(username)}&password=${enc(password)}" +
                "&type=m3u_plus&output=${enc(output)}"
        }
        PlaylistType.FILE -> sourceUri
        PlaylistType.URL -> url.trim()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("url", url).put("server", server).put("username", username)
        .put("password", password).put("output", output).put("sourceUri", sourceUri)
        .put("userAgent", userAgent).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { PlaylistType.valueOf(o.optString("type")) }.getOrDefault(PlaylistType.URL),
            url = o.optString("url"),
            server = o.optString("server"),
            username = o.optString("username"),
            password = o.optString("password"),
            output = o.optString("output", "ts"),
            sourceUri = o.optString("sourceUri"),
            userAgent = o.optString("userAgent"),
            updatedAt = o.optLong("updatedAt"),
        )

        fun normalizeServer(s: String): String {
            var v = s.trim().trimEnd('/')
            if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://$v"
            return v
        }

        private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    }
}
