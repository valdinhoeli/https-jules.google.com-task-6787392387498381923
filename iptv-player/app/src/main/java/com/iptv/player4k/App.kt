package com.iptv.player4k

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.iptv.player4k.data.Http
import okhttp3.Call

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Http.configure(this)
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
