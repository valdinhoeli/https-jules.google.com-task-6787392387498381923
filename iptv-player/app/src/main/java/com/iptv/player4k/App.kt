package com.iptv.player4k

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.iptv.player4k.data.Http

class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
