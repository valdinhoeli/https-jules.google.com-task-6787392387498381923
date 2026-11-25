package com.iptv.player4k.data

import java.net.URI
import java.net.URLDecoder

/** Servidor/usuário/senha de um painel Xtream Codes. */
data class XtreamCreds(val server: String, val username: String, val password: String) {

    companion object {
        /** Lista do tipo Xtream ou link "…/get.php?username=…&password=…". */
        fun fromPlaylist(p: Playlist): XtreamCreds? {
            if (p.type == PlaylistType.XTREAM) {
                if (p.server.isBlank() || p.username.isBlank()) return null
                return XtreamCreds(Playlist.normalizeServer(p.server), p.username.trim(), p.password.trim())
            }
            if (p.type != PlaylistType.URL) return null
            return fromUrl(p.url)
        }

        fun fromUrl(raw: String): XtreamCreds? {
            val u = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (u.scheme == null || u.host == null) return null
            val path = u.rawPath ?: ""
            if (!path.endsWith("get.php") && !path.endsWith("player_api.php") && !path.endsWith("xmltv.php")) return null
            val q = (u.rawQuery ?: "").split('&').associate {
                it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            }
            val user = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
            val pass = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (u.port > 0) ":${u.port}" else ""
            val dir = path.substringBeforeLast('/')
            return XtreamCreds("${u.scheme}://${u.host}$port$dir", user, pass)
        }

        private val typed = Regex("""^(https?://[^/?#]+)/(?:live|movie|series)/([^/?#]+)/([^/?#]+)/\d+(?:\.\w+)?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
        private val short = Regex("""^(https?://[^/?#]+)/([^/?#]+)/([^/?#]+)/\d+(?:\.\w+)?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

        /**
         * Descobre o painel pelos links dos canais, no padrão Xtream
         * "http://servidor:porta/(live|movie|series)/usuário/senha/123.ts" (ou sem o tipo).
         * Só aceita se a maioria dos links aponta para o mesmo usuário/senha.
         */
        fun fromChannels(channels: List<Channel>): XtreamCreds? {
            if (channels.isEmpty()) return null
            val sample = if (channels.size > 3000) channels.filterIndexed { i, _ -> i % (channels.size / 3000 + 1) == 0 } else channels
            val counts = HashMap<XtreamCreds, Int>()
            var typedHits = 0
            for (ch in sample) {
                val url = ch.url.substringBefore('|').trim()
                val m = typed.find(url)?.also { typedHits++ } ?: short.find(url) ?: continue
                val c = XtreamCreds(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                counts[c] = (counts[c] ?: 0) + 1
            }
            val best = counts.maxByOrNull { it.value } ?: return null
            // com /live/, /movie/ ou /series/ basta alguns links; sem o tipo, exige a maioria
            val enough = if (typedHits > 0) best.value >= 3 || best.value == sample.size else best.value * 2 >= sample.size
            if (!enough) return null
            if (best.key.username in setOf("live", "movie", "series", "hls", "stream")) return null
            return best.key
        }
    }
}
