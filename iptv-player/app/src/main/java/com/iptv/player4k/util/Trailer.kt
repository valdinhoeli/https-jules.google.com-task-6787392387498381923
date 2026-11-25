package com.iptv.player4k.util

import java.net.URLEncoder

/** Trailers do YouTube: extrai o ID de qualquer formato que os painéis/listas usam. */
object Trailer {
    private val bareId = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val inUrl = Regex("""(?:v=|vi=|youtu\.be/|/embed/|/shorts/|/v/|/live/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")

    /** "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtu.be/dQw4w9WgXcQ"… → ID (ou null). */
    fun youtubeId(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "0" } ?: return null
        if (bareId.matches(s)) return s
        return inUrl.find(s)?.groupValues?.get(1)
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Sem trailer informado: busca "Nome (ano) trailer" no YouTube. */
    fun searchUrl(title: String, year: String?): String {
        val q = listOfNotNull(title, year, "trailer").joinToString(" ")
        return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
    }
}
