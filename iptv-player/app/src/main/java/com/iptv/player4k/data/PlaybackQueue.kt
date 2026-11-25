package com.iptv.player4k.data

/**
 * Lista de canais compartilhada entre a tela de lista e o player.
 * (Evita passar milhares de canais por Intent, que tem limite de tamanho.)
 */
object PlaybackQueue {
    var playlist: Playlist? = null
        private set
    var channels: List<Channel> = emptyList()
        private set

    fun set(playlist: Playlist?, channels: List<Channel>) {
        this.playlist = playlist
        this.channels = channels
    }
}
