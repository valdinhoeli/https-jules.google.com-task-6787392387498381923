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
    /** URL(s) XMLTV do guia, separadas por vírgula (opcional). */
    val epgUrl: String = "",
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

    /**
     * Lista M3U que na verdade é de um painel Xtream (".../get.php?username=X&password=Y"):
     * o guia fica em ".../xmltv.php" com o mesmo usuário e senha.
     */
    fun derivedXmltvUrl(): String? {
        if (type == PlaylistType.XTREAM) return null
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        if (u.scheme == null || u.host == null) return null
        if (!(u.rawPath ?: "").endsWith("get.php")) return null
        val q = (u.rawQuery ?: "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val user = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        val dir = u.rawPath.substringBeforeLast('/')
        return "${u.scheme}://${u.host}$port$dir/xmltv.php?username=$user&password=$pass"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("url", url).put("server", server).put("username", username)
        .put("password", password).put("output", output).put("sourceUri", sourceUri)
        .put("userAgent", userAgent).put("updatedAt", updatedAt).put("epgUrl", epgUrl)

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
            epgUrl = o.optString("epgUrl"),
        )

        fun normalizeServer(s: String): String {
            var v = s.trim().trimEnd('/')
            if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://$v"
            return v
        }

        private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    }
}
