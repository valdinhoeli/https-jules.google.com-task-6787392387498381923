package com.iptv.player4k.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /** User-Agent padrão: amplamente aceito por servidores/painéis IPTV. */
    const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Cliente para baixar listas grandes (alguns servidores são lentos). */
    val playlistClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }
}
