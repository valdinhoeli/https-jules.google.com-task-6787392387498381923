package com.iptv.player4k.util

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/** Diagnóstico do que o aparelho suporta (4K, HDR, codecs). */
object DeviceInfo {

    @OptIn(UnstableApi::class)
    fun describe(activity: Activity): String {
        val sb = StringBuilder()
        sb.append("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        val size = Util.getCurrentDisplayModeSize(activity)
        sb.append("Saída de vídeo atual: ${maxOf(size.x, size.y)} x ${minOf(size.x, size.y)} (${VideoLabels.resolution(size.x, size.y)})\n")

        @Suppress("DEPRECATION")
        val display: Display = activity.windowManager.defaultDisplay
        if (Build.VERSION.SDK_INT >= 23) {
            val modes = display.supportedModes
            val max = modes.maxByOrNull { it.physicalWidth * it.physicalHeight }
            if (max != null) {
                val w = maxOf(max.physicalWidth, max.physicalHeight)
                val h = minOf(max.physicalWidth, max.physicalHeight)
                sb.append("Resolução máxima da tela/TV: $w x $h (${VideoLabels.resolution(w, h)})\n")
            }
            val rates = modes.map { Math.round(it.refreshRate * 100) / 100f }.distinct().sorted()
            sb.append("Taxas de atualização: ${rates.joinToString(", ") { "${it}Hz" }}\n")
        }
        if (Build.VERSION.SDK_INT >= 24) {
            val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
            val names = types.map {
                when (it) {
                    1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "HDR($it)"
                }
            }
            sb.append("HDR na tela: ${if (names.isEmpty()) "não" else names.joinToString(", ")}\n")
        }

        sb.append("\nDecodificadores de vídeo:\n")
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList()).filter { !it.isEncoder }
        listOf(
            "video/avc" to "H.264 (AVC)",
            "video/hevc" to "H.265 (HEVC)",
            "video/av01" to "AV1",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/dolby-vision" to "Dolby Vision",
        ).forEach { (mime, label) ->
            val found = codecs.filter { c -> c.supportedTypes.any { it.equals(mime, true) } }
            sb.append("• $label: ")
            if (found.isEmpty()) {
                sb.append("não suportado\n")
                return@forEach
            }
            val max = when {
                found.any { supports(it, mime, 3840, 2160) } -> "até 4K ✓"
                found.any { supports(it, mime, 1920, 1080) } -> "até Full HD"
                else -> "até HD"
            }
            val hw = found.any { isHardware(it) }
            sb.append(max).append(if (hw) " (hardware)" else " (software)").append('\n')
        }
        sb.append("\nPara assistir em 4K o aparelho precisa decodificar HEVC/H.264 em 4K e estar ligado a uma tela 4K.")
        return sb.toString()
    }

    private fun supports(info: MediaCodecInfo, mime: String, w: Int, h: Int): Boolean = runCatching {
        val type = info.supportedTypes.first { it.equals(mime, true) }
        info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(w, h) == true
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
}
