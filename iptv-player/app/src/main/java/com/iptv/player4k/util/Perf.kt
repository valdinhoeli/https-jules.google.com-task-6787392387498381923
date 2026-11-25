package com.iptv.player4k.util

import android.os.SystemClock
import com.iptv.player4k.BuildConfig

/** Marcas de tempo de carregamento (só na versão de depuração). */
object Perf {
    private val t0 = if (android.os.Build.VERSION.SDK_INT >= 24) android.os.Process.getStartElapsedRealtime()
    else SystemClock.elapsedRealtime()
    fun mark(what: String) {
        if (BuildConfig.DEBUG) android.util.Log.w("IPTVPERF", "${SystemClock.elapsedRealtime() - t0} ms  $what")
    }
}
