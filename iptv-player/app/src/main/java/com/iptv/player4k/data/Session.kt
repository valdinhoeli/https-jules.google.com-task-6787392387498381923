package com.iptv.player4k.data

import com.iptv.player4k.epg.EpgData

/** Dados carregados compartilhados entre telas (evita passar listas grandes por Intent). */
object Session {
    @Volatile var playlist: Playlist? = null
    @Volatile var library: Library = Library.EMPTY
    @Volatile var epg: EpgData? = null
}
