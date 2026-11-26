package com.iptv.player4k.epg

import org.xmlpull.v1.XmlPullParser

/** Parser XMLTV em streaming: guarda só os canais pedidos e a janela de tempo pedida. */
object XmltvParser {

    class Builder {
        val byId = HashMap<String, ArrayList<Programme>>()
        val nameIndex = HashMap<String, String>()
        val keptIds = HashSet<String>()
        val icons = HashMap<String, String>()

        fun build(updatedAt: Long): EpgData {
            val clean = HashMap<String, List<Programme>>(byId.size)
            byId.forEach { (id, list) ->
                list.sortBy { it.start }
                // remove duplicados/sobreposições de fontes diferentes
                val out = ArrayList<Programme>(list.size)
                for (p in list) {
                    val last = out.lastOrNull()
                    if (last == null || p.start >= last.stop) out += p
                }
                if (out.isNotEmpty()) clean[id] = out
            }
            return EpgData(clean, nameIndex.filterValues { it in clean || it in icons }, updatedAt, icons)
        }
    }

    fun parse(
        xpp: XmlPullParser,
        wantIds: Set<String>,
        wantNames: Set<String>,
        from: Long,
        to: Long,
        into: Builder,
    ) {
        var ev = xpp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "channel" -> readChannel(xpp, wantIds, wantNames, into)
                    "programme" -> readProgramme(xpp, wantIds, from, to, into)
                }
            }
            ev = xpp.next()
        }
    }

    private fun readChannel(xpp: XmlPullParser, wantIds: Set<String>, wantNames: Set<String>, b: Builder) {
        val id = xpp.getAttributeValue(null, "id")?.trim()?.lowercase()
        val depth = xpp.depth
        val names = ArrayList<String>(2)
        var icon: String? = null
        while (true) {
            val ev = xpp.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && xpp.depth == depth) break
            if (ev == XmlPullParser.START_TAG && xpp.name == "display-name") {
                names += xpp.nextText().trim()
            } else if (ev == XmlPullParser.START_TAG && xpp.name == "icon" && icon == null) {
                icon = xpp.getAttributeValue(null, "src")?.trim()?.takeIf { it.startsWith("http") }
            }
        }
        if (id.isNullOrEmpty()) return
        val keys = names.map { EpgData.nameKey(it) }.filter { it.isNotEmpty() }
        if (id in wantIds || keys.any { it in wantNames }) {
            b.keptIds += id
            icon?.let { b.icons[id] = it }
            // Map.putIfAbsent exige API 24
            (keys + EpgData.nameKey(id)).forEach { k -> if (k !in b.nameIndex) b.nameIndex[k] = id }
        }
    }

    private fun readProgramme(xpp: XmlPullParser, wantIds: Set<String>, from: Long, to: Long, b: Builder) {
        val ch = xpp.getAttributeValue(null, "channel")?.trim()?.lowercase()
        val depth = xpp.depth
        val keep = ch != null && (ch in b.keptIds || ch in wantIds)
        val start = if (keep) parseTime(xpp.getAttributeValue(null, "start")) else -1L
        val stop = if (keep) parseTime(xpp.getAttributeValue(null, "stop")) else -1L
        var title: String? = null
        var desc: String? = null
        var flags = 0
        while (true) {
            val ev = xpp.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && xpp.depth == depth) break
            if (keep && ev == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "title" -> if (title == null) title = xpp.nextText().trim()
                    "desc" -> if (desc == null) desc = xpp.nextText().trim().take(600)
                    "live" -> flags = flags or Programme.LIVE
                    "previously-shown" -> flags = flags or Programme.RERUN
                    "premiere", "new" -> flags = flags or Programme.PREMIERE
                    "category" -> if (SportsEvents.isSportCategory(xpp.nextText())) flags = flags or Programme.SPORT
                }
            }
        }
        if (!keep || start < 0 || stop <= start || stop <= from || start >= to) return
        if (ch!! !in b.keptIds) b.keptIds += ch
        b.byId.getOrPut(ch) { ArrayList() } += Programme(start, stop, title ?: "Sem título", desc?.ifEmpty { null }, flags)
    }

    /** "20261125143000 +0300" -> epoch ms (sem SimpleDateFormat, para ser rápido). */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return -1
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val sec = if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
            val rest = s.substring(minOf(s.length, if (s.length >= 14 && s[12].isDigit()) 14 else 12)).trim()
            var offsetMin = 0
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val oh = rest.substring(1, 3).toInt()
                val om = rest.substring(3, 5).toInt()
                offsetMin = (oh * 60 + om) * if (rest[0] == '-') -1 else 1
            }
            val days = daysFromCivil(y, mo, d)
            ((days * 86400L) + h * 3600L + mi * 60L + sec - offsetMin * 60L) * 1000L
        } catch (e: Exception) {
            -1
        }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
