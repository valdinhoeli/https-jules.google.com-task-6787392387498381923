package com.iptv.player4k.util

import java.text.Normalizer

object Text {
    private val marks = Regex("\\p{Mn}+")

    /** Minúsculas e sem acentos, para busca. */
    fun normalize(s: String): String =
        marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
}
