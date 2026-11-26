package com.iptv.player4k

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.iptv.player4k.data.Http
import okhttp3.Call

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Http.configure(this)
        // versão final: o player não escreve no log do sistema (os links das listas
        // podem conter usuário e senha do servidor)
        if (!BuildConfig.DEBUG) androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        // "Tela segura" vale para todas as telas (player, detalhes, ajustes…), não só a inicial
        registerActivityLifecycleCallbacks(com.iptv.player4k.security.SecureScreen)
        com.iptv.player4k.data.Preloader.start(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // sempre usa o cliente atual (respeita o DNS seguro escolhido)
            .callFactory(Call.Factory { request -> Http.client.newCall(request) })
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
