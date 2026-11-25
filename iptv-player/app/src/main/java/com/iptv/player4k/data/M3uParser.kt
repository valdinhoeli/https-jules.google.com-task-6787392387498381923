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
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")

    fun parse(reader: BufferedReader, defaultGroup: String = "Sem grupo"): List<Channel> {
        val out = ArrayList<Channel>(2048)
        var title: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()

        reader.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
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
                        )
                    }
                    title = null
                    attrs = emptyMap()
                    extGroup = null
                    headers.clear()
                }
            }
        }
        return out
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
