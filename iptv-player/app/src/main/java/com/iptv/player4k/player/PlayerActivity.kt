package com.iptv.player4k.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Http
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.databinding.ActivityPlayerBinding
import com.iptv.player4k.util.VideoLabels
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var store: PlaylistStore
    private var player: ExoPlayer? = null

    private val channels: List<Channel> get() = PlaybackQueue.channels
    private var index = 0

    /** Tentativas de tipo de stream (ex.: progressivo -> HLS) para URLs sem extensão. */
    private var mimeCandidates: List<String?> = listOf(null)
    private var mimeAttempt = 0
    private var retryCount = 0
    private var consumedKey = -1
    /** Posição salva para retomar filmes/séries (VOD) ao voltar ao app. */
    private var resumePositionMs = C.TIME_UNSET

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { b.osd.isVisible = false }
    private val retryRunnable = Runnable {
        player?.let { it.prepare(); it.playWhenReady = true }
    }

    private val tvTitle by lazy { b.playerView.findViewById<TextView>(R.id.tvTitle) }
    private val tvInfo by lazy { b.playerView.findViewById<TextView>(R.id.tvInfo) }
    private val tvBadge by lazy { b.playerView.findViewById<TextView>(R.id.tvBadge) }
    private val timeRow by lazy { b.playerView.findViewById<View>(R.id.timeRow) }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeLabels = intArrayOf(R.string.resize_fit, R.string.resize_zoom, R.string.resize_fill)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = PlaylistStore(this)

        if (channels.isEmpty()) { finish(); return }
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, channels.lastIndex)

        hideSystemUi()
        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (channels.isEmpty()) return
        index = intent.getIntExtra(EXTRA_INDEX, index).coerceIn(0, channels.lastIndex)
        playCurrent()
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        if (player == null) {
            initPlayer()
            val resume = resumePositionMs
            playCurrent()
            if (resume != C.TIME_UNSET) player?.seekTo(resume)
            resumePositionMs = C.TIME_UNSET
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------
    // Player
    // ------------------------------------------------------------------

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val loadControl = if (store.largeBuffer) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(40_000, 120_000, 3_000, 6_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 50_000, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()

        // Sem limite pelo tamanho da tela: permite 4K quando a banda suporta.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearViewportSizeConstraints()
            .setForceHighestSupportedBitrate(store.forceMaxQuality)
            .setPreferredAudioLanguage(Locale.getDefault().language)
            .build()

        p.addListener(listener)
        b.playerView.player = p
        b.playerView.resizeMode = resizeModes[store.resizeMode.coerceIn(0, resizeModes.lastIndex)]
        player = p
    }

    private fun releasePlayer() {
        handler.removeCallbacks(retryRunnable)
        player?.let {
            resumePositionMs = if (!it.isCurrentMediaItemLive && it.currentPosition > 0) it.currentPosition else C.TIME_UNSET
            it.removeListener(listener)
            it.release()
        }
        player = null
        b.playerView.player = null
    }

    private fun playCurrent() {
        val p = player ?: return
        val ch = channels[index]
        handler.removeCallbacks(retryRunnable)
        retryCount = 0
        mimeAttempt = 0
        mimeCandidates = candidatesFor(ch.url)
        hideError()
        b.tvStatus.isVisible = false

        tvTitle.text = ch.name
        tvInfo.text = ch.group
        tvBadge.isVisible = false

        // limpa escolhas manuais de faixas do canal anterior
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        PlaybackQueue.playlist?.let { store.addRecent(it.id, ch.url) }
        showOsd(ch)
        startMedia(ch)
    }

    private fun startMedia(ch: Channel) {
        val p = player ?: return
        val mime = mimeCandidates.getOrNull(mimeAttempt)

        val headers = HashMap(ch.headers)
        val ua = headers.remove("User-Agent")
            ?: PlaybackQueue.playlist?.userAgent?.takeIf { it.isNotBlank() }
            ?: Http.DEFAULT_USER_AGENT

        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(this, http)

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
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build()
            )
            .build()

        val source = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(IptvLoadErrorPolicy())
            .createMediaSource(item)

        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    /** Ordem de tentativas do tipo de stream, conforme a URL. */
    private fun candidatesFor(url: String): List<String?> {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?')
        return when {
            lower.startsWith("rtsp://") -> listOf(MimeTypes.APPLICATION_RTSP)
            path.endsWith(".m3u8") || path.endsWith(".m3u") || "m3u8" in lower ->
                listOf(MimeTypes.APPLICATION_M3U8, null)
            path.endsWith(".mpd") || "format=mpd" in lower -> listOf(MimeTypes.APPLICATION_MPD, null)
            path.contains(".ism") -> listOf(MimeTypes.APPLICATION_SS, null)
            path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") ||
                path.endsWith(".avi") || path.endsWith(".mov") -> listOf(null, MimeTypes.APPLICATION_M3U8)
            // sem extensão: tenta detectar o container; se falhar, tenta HLS
            else -> listOf(null, MimeTypes.APPLICATION_M3U8)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retryCount = 0
                b.tvStatus.isVisible = false
                hideError()
                updateLiveUi()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateVideoInfo()
            if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
                runCatching { setPictureInPictureParams(pipParams()) }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateVideoInfo()
            val hasAudio = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO } > 1 ||
                tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 1 }
            val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
            b.playerView.findViewById<View>(R.id.btnAudio)?.isVisible = hasAudio
            b.playerView.findViewById<View>(R.id.btnSubtitle)?.isVisible = hasText
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        val ch = channels[index]
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> {
                if (mimeAttempt + 1 < mimeCandidates.size) {
                    mimeAttempt++
                    startMedia(ch)
                    return
                }
            }
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT -> {
                // 4xx (404, 403…) = canal fora do ar/sem acesso: não insiste.
                val permanent = (httpStatus(error) ?: 0) in 400..499
                if (!permanent && retryCount < MAX_RETRIES) {
                    retryCount++
                    b.tvStatus.text = getString(R.string.reconnecting, retryCount, MAX_RETRIES)
                    b.tvStatus.isVisible = true
                    handler.postDelayed(retryRunnable, 1_500L * retryCount)
                    return
                }
            }
        }
        b.tvStatus.isVisible = false
        showError(error)
    }

    private fun showError(error: PlaybackException) {
        val msg = when (error.errorCode) {
            in 2000..2999 -> if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                R.string.err_play_http else R.string.err_play_network
            in 3000..3999 -> R.string.err_play_format
            in 4000..4999 -> R.string.err_play_decoder
            in 6000..6999 -> R.string.err_play_drm
            else -> R.string.err_play_generic
        }
        b.tvError.setText(msg)
        b.tvErrorCode.text = httpStatus(error)?.let { "${error.errorCodeName} (HTTP $it)" } ?: error.errorCodeName
        b.playerView.hideController()
        b.errorView.isVisible = true
        b.btnRetry.requestFocus()
    }

    private fun httpStatus(error: PlaybackException): Int? {
        var c: Throwable? = error.cause
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return null
    }

    private fun hideError() {
        b.errorView.isVisible = false
    }

    private fun updateLiveUi() {
        val live = player?.isCurrentMediaItemLive == true
        timeRow?.isVisible = !live
    }

    private fun updateVideoInfo() {
        val p = player ?: return
        val ch = channels.getOrNull(index) ?: return
        // videoFormat pode ainda ser do canal anterior enquanto o novo carrega
        val f = if (p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) p.videoFormat else null
        if (f == null || f.width <= 0) {
            tvBadge.isVisible = false
            tvInfo.text = ch.group
            return
        }
        val res = VideoLabels.resolution(f.width, f.height)
        val hdr = VideoLabels.hdrName(f)
        tvBadge.text = listOfNotNull(res.replace(" UHD", "").replace(" QHD", ""), hdr).joinToString(" ")
        tvBadge.isVisible = true

        val info = listOfNotNull(
            "${f.width}x${f.height}",
            VideoLabels.codec(f.sampleMimeType),
            VideoLabels.fps(f.frameRate),
            VideoLabels.bitrate(f.bitrate),
            p.audioFormat?.let { VideoLabels.codec(it.sampleMimeType) },
        ).joinToString(" • ")
        tvInfo.text = "${ch.group}  |  $info"

        if (b.osd.isVisible) b.tvOsdInfo.text = listOfNotNull(res, hdr, "${f.width}x${f.height}").joinToString(" • ")

        if (store.matchDisplayMode && Build.VERSION.SDK_INT >= 23) {
            matchDisplayMode(f.width, f.height, f.frameRate)
        }
    }

    // ------------------------------------------------------------------
    // UI / controles
    // ------------------------------------------------------------------

    private fun setupControls() {
        val pv = b.playerView
        pv.setShowNextButton(false)
        pv.setShowPreviousButton(false)
        pv.setShowFastForwardButton(false)
        pv.setShowRewindButton(false)

        pv.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        pv.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { switchChannel(-1) }
        pv.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { switchChannel(+1) }
        pv.findViewById<ImageButton>(R.id.btnQuality).setOnClickListener { showTrackDialog(C.TRACK_TYPE_VIDEO) }
        pv.findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        pv.findViewById<ImageButton>(R.id.btnSubtitle).setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        pv.findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { cycleResize() }
        pv.findViewById<ImageButton>(R.id.btnPip).apply {
            isVisible = canPip()
            setOnClickListener { enterPip() }
        }
        pv.findViewById<View>(R.id.btnAudio).isVisible = false
        pv.findViewById<View>(R.id.btnSubtitle).isVisible = false

        b.btnRetry.setOnClickListener {
            hideError()
            retryCount = 0
            mimeAttempt = 0
            channels.getOrNull(index)?.let { startMedia(it) }
        }
        b.btnErrorNext.setOnClickListener { switchChannel(+1) }
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta).mod(channels.size)
        playCurrent()
    }

    private fun showOsd(ch: Channel) {
        b.tvOsdTitle.text = "${index + 1}  ${ch.name}"
        b.tvOsdInfo.text = ch.group
        if (ch.logo.isNullOrBlank()) b.ivOsdLogo.setImageResource(R.drawable.ic_channel_placeholder)
        else b.ivOsdLogo.load(ch.logo) { error(R.drawable.ic_channel_placeholder) }
        b.osd.isVisible = true
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 4_000)
    }

    private fun cycleResize() {
        val next = (store.resizeMode + 1).mod(resizeModes.size)
        store.resizeMode = next
        b.playerView.resizeMode = resizeModes[next]
        Toast.makeText(this, resizeLabels[next], Toast.LENGTH_SHORT).show()
    }

    /** Seletor de qualidade (vídeo), áudio ou legendas. */
    private fun showTrackDialog(type: Int) {
        val p = player ?: return
        data class Entry(val label: String, val selected: Boolean, val sortKey: Int, val apply: () -> Unit)

        val params = p.trackSelectionParameters
        val hasOverride = params.overrides.values.any { it.type == type }
        val disabled = params.disabledTrackTypes.contains(type)
        val tracks = mutableListOf<Entry>()

        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                // Inclui faixas "acima da capacidade" declarada: muitos aparelhos
                // reproduzem 4K mesmo assim. São marcadas com ⚠.
                if (!g.isTrackSupported(i, /* allowExceedsCapabilities= */ true)) continue
                val f = g.getTrackFormat(i)
                val base = when (type) {
                    C.TRACK_TYPE_VIDEO -> VideoLabels.videoTrack(f)
                    C.TRACK_TYPE_AUDIO -> VideoLabels.audioTrack(f)
                    else -> VideoLabels.language(f)
                }
                val label = if (g.isTrackSupported(i)) base else "$base ⚠"
                val sortKey = if (type == C.TRACK_TYPE_VIDEO) -(f.height * 10_000 + f.bitrate / 1000) else 0
                tracks += Entry(label, hasOverride && !disabled && g.isTrackSelected(i), sortKey) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.no_options, Toast.LENGTH_SHORT).show()
            return
        }
        tracks.sortBy { it.sortKey }

        val entries = mutableListOf<Entry>()
        val current = p.videoFormat
        val autoLabel = if (type == C.TRACK_TYPE_VIDEO && current != null && current.height > 0)
            "${getString(R.string.auto)} (${current.height}p)" else getString(R.string.auto)
        entries += Entry(autoLabel, !hasOverride && !disabled, 0) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .clearOverridesOfType(type)
                .build()
        }
        if (type == C.TRACK_TYPE_TEXT) {
            entries += Entry(getString(R.string.off), disabled, 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, true)
                    .build()
            }
        }
        entries += tracks

        val title = when (type) {
            C.TRACK_TYPE_VIDEO -> R.string.q_title
            C.TRACK_TYPE_AUDIO -> R.string.audio_title
            else -> R.string.sub_title
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                entries.map { it.label }.toTypedArray(),
                entries.indexOfFirst { it.selected }.coerceAtLeast(0)
            ) { d, which ->
                entries[which].apply()
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Controle remoto (Android TV / TV Box)
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == consumedKey) {
            consumedKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controllerHidden = !b.playerView.isControllerFullyVisible && !b.errorView.isVisible
            val delta = when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> +1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
                KeyEvent.KEYCODE_DPAD_UP -> if (controllerHidden) +1 else 0
                KeyEvent.KEYCODE_DPAD_DOWN -> if (controllerHidden) -1 else 0
                else -> 0
            }
            if (delta != 0) {
                consumedKey = code
                if (event.repeatCount == 0) switchChannel(delta)
                return true
            }
            if (code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_MENU) {
                b.playerView.showController()
                channels.getOrNull(index)?.let { showOsd(it) }
                consumedKey = code
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------
    // Picture-in-Picture
    // ------------------------------------------------------------------

    private fun canPip(): Boolean =
        Build.VERSION.SDK_INT >= 26 && store.pipEnabled &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @RequiresApi(26)
    private fun pipParams(): PictureInPictureParams {
        val f = player?.videoFormat
        var ratio = if (f != null && f.width > 0 && f.height > 0) {
            val w = (f.width * f.pixelWidthHeightRatio).roundToInt()
            Rational(w, f.height)
        } else Rational(16, 9)
        val v = ratio.toFloat()
        if (v > 2.39f) ratio = Rational(239, 100)
        if (v < 0.42f) ratio = Rational(42, 100)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).build()
    }

    private fun enterPip() {
        if (!canPip() || Build.VERSION.SDK_INT < 26) return
        runCatching {
            b.playerView.hideController()
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        b.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            b.osd.isVisible = false
            b.playerView.hideController()
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // janela PiP fechada pelo usuário
            finish()
        }
    }

    // ------------------------------------------------------------------
    // Troca de modo da TV para 4K
    // ------------------------------------------------------------------

    @RequiresApi(23)
    private fun matchDisplayMode(w: Int, h: Int, fps: Float) {
        if (w < 3200 && h < 2000) return
        @Suppress("DEPRECATION")
        val display: Display = windowManager.defaultDisplay
        val current = display.mode
        if (current.physicalWidth >= 3840) return
        val candidates = display.supportedModes.filter { it.physicalWidth >= 3840 }
        if (candidates.isEmpty()) return
        val best = candidates.minByOrNull { m ->
            if (fps > 0f) {
                val ratio = m.refreshRate / fps
                val r = ratio.roundToInt()
                if (r < 1) 1000f else abs(ratio - r) * 100f + abs(m.refreshRate - current.refreshRate) / 100f
            } else abs(m.refreshRate - current.refreshRate)
        } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    /** Não repete requisições que falharam com 4xx (canal inexistente/bloqueado). */
    private class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(info)
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val MAX_RETRIES = 4
    }
}
