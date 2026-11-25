package com.iptv.player4k.player

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.iptv.player4k.data.Channel

/** Pré-visualização pequena de um canal (guia de programação). Limitada a 720p para ser leve. */
@OptIn(UnstableApi::class)
class PreviewPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val loading: View,
    private val placeholder: View,
) {
    private var player: ExoPlayer? = null
    var current: Channel? = null
        private set
    private var mimes: List<String?> = emptyList()
    private var attempt = 0

    fun play(ch: Channel) {
        if (current?.url == ch.url && player != null) return
        current = ch
        val p = player ?: ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 20_000, 1_000, 2_000).build())
            .build().also { pl ->
                pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loading.isVisible = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) placeholder.isVisible = false
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        attempt++
                        if (attempt < mimes.size) load() else loading.isVisible = false
                    }
                })
                view.player = pl
                player = pl
            }
        p.stop()
        placeholder.isVisible = true
        mimes = StreamFactory.candidatesFor(ch.url)
        attempt = 0
        load()
    }

    private fun load() {
        val p = player ?: return
        val ch = current ?: return
        p.setMediaSource(StreamFactory.create(context, ch, mimes.getOrNull(attempt)))
        p.prepare()
        p.playWhenReady = true
    }

    fun release() {
        player?.release()
        player = null
        view.player = null
        current = null
        loading.isVisible = false
        placeholder.isVisible = true
    }
}
