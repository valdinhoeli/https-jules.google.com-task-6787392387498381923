package com.iptv.player4k.data

import com.iptv.player4k.util.Text

enum class ContentKind { LIVE, MOVIE, SERIES }

/** Um item reproduzível: canal ao vivo, filme ou episódio. */
class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "",
    val tvgId: String? = null,
    /** Cabeçalhos HTTP específicos do canal (User-Agent, Referer, Origin…). */
    val headers: Map<String, String> = emptyMap(),
    val kind: ContentKind = ContentKind.LIVE,
    val tvgName: String? = null,
    /** Para filmes/séries: id do item (progresso e "continuar assistindo"). */
    val refId: String? = null,
    /** Classificação indicativa (idade), quando a lista informa. */
    val age: Int? = null,
) {
    val searchKey: String = Text.normalize(name)
    val qualityTag: QualityTag? = QualityTag.fromName(name)

    override fun equals(other: Any?) = other is Channel && other.url == url && other.name == name
    override fun hashCode() = 31 * url.hashCode() + name.hashCode()
}

enum class QualityTag(val label: String) {
    UHD("4K"), FHD("FHD"), HD("HD"), SD("SD");

    companion object {
        private val uhd = Regex("""(?i)(?<![A-Z0-9])(4K|UHD|2160P?)(?![A-Z0-9])""")
        private val fhd = Regex("""(?i)(?<![A-Z0-9])(FHD|FULL ?HD|1080[PI]?)(?![A-Z0-9])""")
        private val hd = Regex("""(?i)(?<![A-Z0-9])(HD|720P?)(?![A-Z0-9])""")
        private val sd = Regex("""(?i)(?<![A-Z0-9])(SD|480P?|576P?)(?![A-Z0-9])""")

        fun fromName(name: String): QualityTag? = when {
            uhd.containsMatchIn(name) -> UHD
            fhd.containsMatchIn(name) -> FHD
            hd.containsMatchIn(name) -> HD
            sd.containsMatchIn(name) -> SD
            else -> null
        }
    }
}
