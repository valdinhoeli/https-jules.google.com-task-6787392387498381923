package com.iptv.player4k.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Http
import com.iptv.player4k.data.PlaybackQueue
import java.util.Locale

/** Cria as fontes de mídia (cabeçalhos, User-Agent, TS/HLS/DASH/RTSP) — usado pelo player e pela multi-tela. */
@OptIn(UnstableApi::class)
object StreamFactory {

    /** Ordem de tentativas do tipo de stream, conforme a URL. */
    fun candidatesFor(url: String): List<String?> {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?')
        return when {
            lower.startsWith("rtsp://") -> listOf(MimeTypes.APPLICATION_RTSP)
            path.endsWith(".m3u8") || path.endsWith(".m3u") || "m3u8" in lower ->
                listOf(MimeTypes.APPLICATION_M3U8, null)
            path.endsWith(".mpd") || "format=mpd" in lower -> listOf(MimeTypes.APPLICATION_MPD, null)
            path.contains(".ism") -> listOf(MimeTypes.APPLICATION_SS, null)
            // com ou sem extensão: tenta detectar o container; se falhar, tenta HLS
            else -> listOf(null, MimeTypes.APPLICATION_M3U8)
        }
    }

    fun create(context: Context, ch: Channel, mime: String?): MediaSource {
        val headers = HashMap(ch.headers)
        val ua = headers.remove("User-Agent")
            ?: PlaybackQueue.playlist?.userAgent?.takeIf { it.isNotBlank() }
            ?: Http.DEFAULT_USER_AGENT

        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(context, http)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
            .build()

        return DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(IptvLoadErrorPolicy())
            .createMediaSource(item)
    }

    /** Não repete requisições que falharam com 4xx (canal inexistente/bloqueado). */
    class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
            return super.getRetryDelayMsFor(info)
        }
    }
}
