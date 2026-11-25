package com.iptv.player4k.epg

import com.iptv.player4k.data.Channel
import com.iptv.player4k.util.Text
import java.util.concurrent.ConcurrentHashMap

class Programme(val start: Long, val stop: Long, val title: String, val desc: String?) {
    fun isLive(t: Long = System.currentTimeMillis()) = t in start until stop
    fun progress(t: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((t - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
    val minutes get() = ((stop - start) / 60_000L).toInt()
}

/** Guia de programação já filtrado para os canais da lista. */
class EpgData(
    /** id XMLTV (minúsculo) -> programas ordenados */
    internal val byId: Map<String, List<Programme>>,
    /** nome normalizado -> id XMLTV */
    internal val nameIndex: Map<String, String>,
    val updatedAt: Long,
    /** id XMLTV -> logo do canal (<icon src>) */
    internal val icons: Map<String, String> = emptyMap(),
) {
    private val resolved = ConcurrentHashMap<String, List<Programme>>()

    val channelCount get() = byId.size
    val isEmpty get() = byId.isEmpty()

    fun programmes(ch: Channel): List<Programme> =
        resolved.getOrPut(ch.url) { resolve(ch) }

    private fun resolve(ch: Channel): List<Programme> =
        idFor(ch)?.let { byId[it] } ?: emptyList()

    private fun idFor(ch: Channel): String? {
        ch.tvgId?.lowercase()?.let { if (it in byId || it in icons) return it }
        for (n in listOfNotNull(ch.tvgId, ch.tvgName, ch.name)) {
            val id = nameIndex[nameKey(n)] ?: continue
            return id
        }
        return null
    }

    /** Logo do canal vindo do guia, para canais sem tvg-logo. */
    fun logo(ch: Channel): String? = idFor(ch)?.let { icons[it] }

    fun now(ch: Channel, t: Long = System.currentTimeMillis()): Programme? =
        programmes(ch).let { at(it, t)?.let { i -> it[i] } }

    fun next(ch: Channel, t: Long = System.currentTimeMillis()): Programme? {
        val list = programmes(ch)
        val i = firstEndingAfter(list, t)
        if (i >= list.size) return null
        return if (list[i].start <= t) list.getOrNull(i + 1) else list[i]
    }

    companion object {
        /** Índice do programa que contém [t], ou null. */
        fun at(list: List<Programme>, t: Long): Int? {
            val i = firstEndingAfter(list, t)
            return if (i < list.size && list[i].start <= t) i else null
        }

        /** Primeiro índice cujo término é posterior a [t] (busca binária). */
        fun firstEndingAfter(list: List<Programme>, t: Long): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].stop <= t) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private val brackets = Regex("""\[.*?]|\(.*?\)""")
        private val prefix = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val quality = Regex("""(?<![a-z0-9])(fhd|uhd|hd|sd|4k|hevc|h265|h264|1080p?|720p?|hdr|alt|backup|br)(?![a-z0-9])""")
        private val nonAlnum = Regex("[^a-z0-9]")

        /** "BR: Globo SP FHD [H265]" -> "globosp" */
        fun nameKey(s: String): String {
            var k = brackets.replace(Text.normalize(s), " ").trim()
            k = prefix.replace(k, "")
            k = quality.replace(k, " ")
            return nonAlnum.replace(k, "")
        }
    }
}
