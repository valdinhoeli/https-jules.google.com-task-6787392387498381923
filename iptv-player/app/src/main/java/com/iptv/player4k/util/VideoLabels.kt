package com.iptv.player4k.util

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

object VideoLabels {

    /** Rótulo de resolução: 4K UHD, 2K, Full HD, HD, SD. */
    fun resolution(width: Int, height: Int): String {
        // independe da orientação (vídeo vertical / tela em retrato)
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return when {
            long >= 3800 || short >= 2100 -> "4K UHD"
            long >= 2500 || short >= 1400 -> "2K QHD"
            long >= 1880 || short >= 1060 -> "Full HD"
            long >= 1260 || short >= 700 -> "HD"
            long > 0 -> "SD"
            else -> ""
        }
    }

    fun isHdr(f: Format): Boolean {
        val t = f.colorInfo?.colorTransfer ?: return false
        return t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG ||
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
    }

    fun hdrName(f: Format): String? = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

    fun codec(mime: String?): String? = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3/MP2"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        null -> null
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }

    fun bitrate(bps: Int): String? =
        if (bps <= 0) null
        else if (bps >= 1_000_000) String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
        else "${bps / 1000} kbps"

    fun fps(f: Float): String? =
        if (f <= 0f) null
        else if (f % 1f == 0f) "${f.toInt()} fps"
        else String.format(Locale.US, "%.2f fps", f)

    /** Rótulo de uma faixa de vídeo para o seletor de qualidade. */
    fun videoTrack(f: Format): String {
        val parts = mutableListOf<String>()
        if (f.height > 0) parts += "${f.height}p"
        resolution(f.width, f.height).takeIf { it.isNotEmpty() }?.let { parts += it }
        hdrName(f)?.let { parts += it }
        bitrate(f.bitrate)?.let { parts += it }
        codec(f.sampleMimeType)?.let { parts += it }
        return parts.joinToString(" • ").ifEmpty { f.id ?: "Vídeo" }
    }

    fun language(f: Format): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
        val name = lang?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return listOfNotNull(f.label, name).distinct().joinToString(" - ").ifEmpty { lang ?: "Faixa" }
    }

    fun audioTrack(f: Format): String {
        val parts = mutableListOf(language(f))
        when (f.channelCount) {
            1 -> parts += "Mono"; 2 -> parts += "Estéreo"; 6 -> parts += "5.1"; 8 -> parts += "7.1"
            else -> if (f.channelCount > 0) parts += "${f.channelCount}ch"
        }
        codec(f.sampleMimeType)?.let { parts += it }
        return parts.joinToString(" • ")
    }
}
