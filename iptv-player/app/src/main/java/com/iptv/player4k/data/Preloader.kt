package com.iptv.player4k.data

import android.content.Context
import com.iptv.player4k.epg.EpgRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Começa a ler a lista salva assim que o app abre (antes da tela aparecer),
 * deixando canais, catálogo e guia prontos na memória. Não usa a internet.
 */
object Preloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            runCatching {
                val p = PlaylistStore(ctx).selected() ?: return@launch
                val repo = PlaylistRepository(ctx)
                val result = repo.loadCached(p) ?: return@launch
                com.iptv.player4k.util.Perf.mark("pré-carga: lista")
                val creds = XtreamCreds.fromPlaylist(p) ?: XtreamCreds.fromChannels(result.channels)
                LibraryRepository(ctx).load(p, result.channels, false, creds, repo.fingerprint(p), offline = true)
                com.iptv.player4k.util.Perf.mark("pré-carga: catálogo")
                EpgRepository(ctx).loadCached(p, result.tvgUrls, creds)
                com.iptv.player4k.util.Perf.mark("pré-carga: guia")
            }
        }
    }
}
