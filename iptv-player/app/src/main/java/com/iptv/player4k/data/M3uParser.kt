package com.iptv.player4k.data

import org.json.JSONObject
import java.io.BufferedReader
import java.net.URLDecoder

/**
 * Parser tolerante de listas M3U / M3U8 estendidas (formato IPTV).
 *
 * Suporta: atributos do #EXTINF (tvg-id, tvg-name, tvg-logo, group-title…),
 * #EXTGRP, #EXTVLCOPT (http-user-agent / http-referrer / http-origin),
 * #EXTHTTP (JSON de cabeçalhos) e cabeçalhos no estilo Kodi após "|" na URL.
 */
class M3uResult(val channels: List<Channel>, val tvgUrls: List<String>)

object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
    private val liveWords = listOf("canais", "canal", "24h", "24 h", "24 horas", "ao vivo", " live ")
    private val seriesWords = listOf("serie", "series", "temporada", "season")
    private val movieWords = listOf("filme", "movie", "vod", "cinema", "lancamento")
    private val vodExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v")

    fun parse(reader: BufferedReader, defaultGroup: String = "Sem grupo"): M3uResult {
        val out = ArrayList<Channel>(2048)
        val tvgUrls = LinkedHashSet<String>()
        var title: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()

        reader.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    attrRegex.findAll(line).forEach { m ->
                        if (m.groupValues[1].lowercase() in setOf("url-tvg", "x-tvg-url", "tvg-url", "url-epg", "epg-url", "x-epg-url")) {
                            m.groupValues[2].split(',', ' ', ';', '|').map { it.trim() }
                                .filter { it.startsWith("http", true) }.forEach { tvgUrls += it }
                        }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (t, a) = parseExtInf(line)
                    title = t
                    attrs = a
                    (a["user-agent"] ?: a["http-user-agent"])?.let { headers["User-Agent"] = it }
                    (a["http-referrer"] ?: a["referrer"] ?: a["http-referer"])?.let { headers["Referer"] = it }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGroup = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':')
                    val key = kv.substringBefore('=').trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) when (key) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-origin" -> headers["Origin"] = value
                    }
                }
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> runCatching {
                    val o = JSONObject(line.substringAfter(':'))
                    o.keys().forEach { k -> headers[canonicalHeader(k)] = o.getString(k) }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val (url, urlHeaders) = splitUrl(line)
                    if (url.isNotEmpty() && looksLikeUrl(url)) {
                        headers.putAll(urlHeaders)
                        val name = title?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: nameFromUrl(url)
                        val group = attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() }
                            ?: extGroup ?: defaultGroup
                        out += Channel(
                            name = name.trim(),
                            url = url,
                            logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.takeIf { it.isNotEmpty() },
                            group = group,
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            headers = if (headers.isEmpty()) emptyMap() else HashMap(headers),
                            kind = classify(url, group, name),
                            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                            trailer = com.iptv.player4k.util.Trailer.youtubeId(
                                attrs["trailer"] ?: attrs["tvg-trailer"] ?: attrs["youtube-trailer"] ?: attrs["youtube_trailer"]
                            ),
                            description = (attrs["plot"] ?: attrs["description"] ?: attrs["tvg-description"]
                                ?: attrs["tvg-plot"] ?: attrs["desc"] ?: attrs["synopsis"] ?: attrs["sinopse"])
                                ?.trim()?.takeIf { it.length > 3 },
                            age = com.iptv.player4k.security.AgeRating.parse(
                                attrs["tvg-rating"] ?: attrs["rating"] ?: attrs["parental-rating"] ?: attrs["age"]
                            ),
                        )
                    }
                    title = null
                    attrs = emptyMap()
                    extGroup = null
                    headers.clear()
                }
            }
        }
        return M3uResult(out, tvgUrls.toList())
    }

    /**
     * Ao vivo, filme ou série. Usa o padrão de URL do Xtream (/movie/, /series/, /live/),
     * o nome (S01E02) e palavras do grupo ("Filmes", "Séries"…).
     */
    fun classify(url: String, group: String, name: String): ContentKind {
        val u = url.lowercase()
        if ("/series/" in u) return ContentKind.SERIES
        if ("/movie/" in u || "/movies/" in u) return ContentKind.MOVIE
        if ("/live/" in u) return ContentKind.LIVE
        if (LibraryBuilder.hasStrongEpisodeMarker(name)) return ContentKind.SERIES
        val g = " " + com.iptv.player4k.util.Text.normalize(group) + " "
        if (liveWords.any { it in g }) return ContentKind.LIVE
        if (seriesWords.any { it in g }) return ContentKind.SERIES
        // "Episódio 3", "Capítulo 12" num grupo que não é de canais ao vivo
        if (LibraryBuilder.hasEpisodeWord(name)) return ContentKind.SERIES
        if (movieWords.any { it in g }) return ContentKind.MOVIE
        val path = u.substringBefore('?').substringBefore('|')
        if (vodExt.any { path.endsWith(it) }) return ContentKind.MOVIE
        return ContentKind.LIVE
    }

    /** Separa os atributos e o título do #EXTINF, respeitando vírgulas entre aspas. */
    internal fun parseExtInf(line: String): Pair<String, Map<String, String>> {
        val body = line.substring(line.indexOf(':') + 1)
        var inQuotes = false
        var comma = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { comma = i; break }
        }
        val meta = if (comma >= 0) body.substring(0, comma) else body
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        val attrs = HashMap<String, String>()
        attrRegex.findAll(meta).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        return title to attrs
    }

    /** "http://x/y.m3u8|User-Agent=abc&Referer=def" -> (url, headers) */
    internal fun splitUrl(line: String): Pair<String, Map<String, String>> {
        val pipe = line.indexOf('|')
        if (pipe < 0) return line.trim() to emptyMap()
        val url = line.substring(0, pipe).trim()
        val h = HashMap<String, String>()
        line.substring(pipe + 1).split('&').forEach { part ->
            val k = part.substringBefore('=').trim()
            val v = part.substringAfter('=', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                h[canonicalHeader(k)] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
        }
        return url to h
    }

    private fun canonicalHeader(k: String): String = when (k.lowercase()) {
        "user-agent", "useragent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k
    }

    private fun looksLikeUrl(s: String): Boolean {
        val i = s.indexOf("://")
        return i in 2..12 || s.startsWith("/")
    }

    private fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { url }
}
