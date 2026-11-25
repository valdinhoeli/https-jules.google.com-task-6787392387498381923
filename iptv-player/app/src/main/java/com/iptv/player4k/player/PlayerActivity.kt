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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Progress
import com.iptv.player4k.data.Session
import com.iptv.player4k.util.Fmt
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
    private lateinit var panel: ChannelPanel
    /** sinopse e "FILME • 2015 • ★ 7.8 • 1h30" de filmes/episódios (buscados no painel) */
    private val vodInfo = HashMap<String, Pair<String?, String?>>()
    private val isVod get() = channels.getOrNull(index)?.kind?.let { it != ContentKind.LIVE } == true
    private val tvEpg by lazy { b.playerView.findViewById<TextView>(R.id.tvEpg) }

    /** Salva o progresso de filmes/episódios a cada 10 s. */
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }
    private val epgTicker = object : Runnable {
        override fun run() {
            channels.getOrNull(index)?.let { bindEpg(it) }
            handler.postDelayed(this, 30_000)
        }
    }
    private val nextEpisode = Runnable { if (index < channels.lastIndex) switchChannel(+1) }

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
        com.iptv.player4k.ui.SecureScreen.apply(this)
        setupControls()
        panel = ChannelPanel(
            b,
            onPick = { list, pos ->
                // troca a fila para o grupo escolhido (CH+/CH- andam dentro dele)
                if (list !== PlaybackQueue.channels) PlaybackQueue.set(PlaybackQueue.playlist, list)
                index = pos.coerceIn(0, channels.lastIndex)
                playCurrent()
            },
            onExit = { finish() },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val live = channels.getOrNull(index)?.kind == ContentKind.LIVE
                when {
                    panel.isShowing -> panel.back()
                    b.playerView.isControllerFullyVisible -> b.playerView.hideController()
                    // canal ao vivo: voltar abre os grupos sem sair do canal
                    live && Session.live.isNotEmpty() -> openPanel(withGroups = true)
                    else -> finish()
                }
            }
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
            playCurrent()
        }
        handler.postDelayed(progressSaver, 10_000)
        handler.postDelayed(epgTicker, 30_000)
    }

    override fun onPause() {
        super.onPause()
        // a tela anterior volta (onResume) antes do onStop daqui: salva já
        saveProgress()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        if (pipReceiverOn) runCatching { unregisterReceiver(pipReceiver) }
        pipReceiverOn = false
        super.onDestroy()
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
        handler.removeCallbacks(progressSaver)
        handler.removeCallbacks(epgTicker)
        handler.removeCallbacks(nextEpisode)
        saveProgress()
        player?.let {
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
        handler.removeCallbacks(nextEpisode)
        retryCount = 0
        mimeAttempt = 0
        mimeCandidates = StreamFactory.candidatesFor(ch.url)
        hideError()
        b.tvStatus.isVisible = false

        tvTitle.text = ch.name
        tvInfo.text = ch.group
        tvBadge.isVisible = false
        techLabel = ""

        // limpa escolhas manuais de faixas do canal anterior
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        if (ch.kind == ContentKind.LIVE) PlaybackQueue.playlist?.let { store.addRecent(it.id, ch.url) }
        val single = channels.size <= 1
        b.playerView.findViewById<View>(R.id.btnMulti)?.isVisible = ch.kind == ContentKind.LIVE
        b.playerView.findViewById<View>(R.id.btnPrev)?.isVisible = !single
        b.playerView.findViewById<View>(R.id.btnNext)?.isVisible = !single
        bindEpg(ch)
        updateLiveUi()
        showOsd(ch)
        startMedia(ch)
        if (ch.kind != ContentKind.LIVE) {
            resumeSaved(ch)
            loadVodInfo(ch)
        }
    }

    /** Retoma filmes/episódios de onde parou. */
    private fun resumeSaved(ch: Channel) {
        val pid = PlaybackQueue.playlist?.id ?: return
        val saved = store.progress(pid, ch.url) ?: return
        if (!saved.inProgress) return
        player?.seekTo(saved.position)
        Toast.makeText(this, getString(R.string.resuming_at, Fmt.clock(saved.position)), Toast.LENGTH_SHORT).show()
    }

    private fun saveProgress() {
        val p = player ?: return
        val ch = channels.getOrNull(index) ?: return
        if (ch.kind == ContentKind.LIVE || p.isCurrentMediaItemLive) return
        val pid = PlaybackQueue.playlist?.id ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET || pos < 5_000) return
        val ended = p.playbackState == Player.STATE_ENDED
        store.saveProgress(
            pid,
            Progress(
                url = ch.url, position = if (ended) dur else pos, duration = dur,
                updatedAt = System.currentTimeMillis(), kind = ch.kind, refId = ch.refId, title = ch.name,
            )
        )
    }

    /** Resolução/codec do vídeo atual, mostrado no canto da faixa ("Full HD • HEVC"). */
    private var techLabel: String = ""

    private val ctlBanner by lazy {
        com.iptv.player4k.databinding.ViewEpgBannerBinding.bind(b.playerView.findViewById(R.id.bannerCtl))
    }

    /** Atualiza as duas faixas de baixo (a do controle e a da troca de canal). */
    private fun bindEpg(ch: Channel) {
        tvEpg.isVisible = false
        bindBanner(ctlBanner, ch)
        bindBanner(b.bannerOsd, ch)
    }

    private fun bindBanner(v: com.iptv.player4k.databinding.ViewEpgBannerBinding, ch: Channel) {
        val live = ch.kind == ContentKind.LIVE
        val epg = Session.epg
        val now = System.currentTimeMillis()

        val logo = ch.logo ?: epg?.logo(ch)
        val art = com.iptv.player4k.util.CoverArt.channel(ch.name)
        if (logo.isNullOrBlank()) v.ivBnLogo.setImageDrawable(art)
        else if (v.ivBnLogo.tag != logo) {
            v.ivBnLogo.tag = logo
            v.ivBnLogo.load(logo) { error(art) }
        }

        v.tvBnNumber.isVisible = live
        v.tvBnNumber.text = (index + 1).toString()
        v.tvBnChannel.text = if (live) ch.name else ch.group
        v.tvBnTech.text = techLabel
        v.tvBnTech.isVisible = false
        v.tvBnLive.isVisible = live

        val lp = v.ivBnLogo.layoutParams
        val d = resources.displayMetrics.density
        lp.width = ((if (live) 104 else 84) * d).toInt()
        lp.height = ((if (live) 70 else 126) * d).toInt()
        v.ivBnLogo.layoutParams = lp
        if (!live) {
            // filme/episódio: capa, "FILME • ano • nota • duração", título e sinopse
            val info = vodInfo[ch.url]
            v.tvBnChannel.text = info?.second ?: getString(if (ch.kind == ContentKind.MOVIE) R.string.kind_movie else R.string.kind_series)
            v.tvBnTitle.text = ch.name
            v.bnTimeRow.isVisible = false
            v.tvBnNext.isVisible = false
            v.tvBnDesc.maxLines = 5
            v.tvBnDesc.text = info?.first ?: ch.description ?: getString(R.string.no_plot)
            v.tvBnDesc.isVisible = true
            return
        }
        v.tvBnDesc.maxLines = 2
        val cur = epg?.now(ch, now)
        if (cur == null) {
            v.tvBnTitle.text = if (live) getString(R.string.epg_no_info) else ch.name
            v.bnTimeRow.isVisible = false
            v.tvBnNext.isVisible = false
            v.tvBnDesc.text = if (live) null else ch.description
            v.tvBnDesc.isVisible = !v.tvBnDesc.text.isNullOrBlank()
            return
        }
        v.tvBnTitle.text = cur.title
        v.bnTimeRow.isVisible = true
        v.tvBnStart.text = Fmt.time(cur.start)
        v.tvBnEnd.text = Fmt.time(cur.stop)
        v.bnProgress.setProgressCompat((cur.progress(now) * 1000).toInt(), false)
        v.tvBnRemaining.text = getString(R.string.epg_remaining, ((cur.stop - now) / 60_000L).toInt().coerceAtLeast(1))
        v.tvBnDesc.text = cur.desc
        v.tvBnDesc.isVisible = !cur.desc.isNullOrBlank()
        val next = epg?.next(ch, now)
        v.tvBnNext.isVisible = next != null
        v.tvBnNext.text = next?.let { "${getString(R.string.epg_next)}  ${Fmt.time(it.start)} · ${it.title}" }
    }

    private fun openPanel(withGroups: Boolean = false) {
        if (channels.isEmpty()) return
        b.playerView.hideController()
        handler.removeCallbacks(hideOsd)
        b.osd.isVisible = false
        panel.show(channels, index, withGroups)
    }

    /** Sinopse e ficha do filme/série (lista ou painel Xtream), mostradas ao apertar OK. */
    private fun loadVodInfo(ch: Channel) {
        if (vodInfo.containsKey(ch.url)) return
        val pl = PlaybackQueue.playlist ?: return
        val lib = Session.library
        val repo = com.iptv.player4k.data.LibraryRepository(this)
        lifecycleScope.launch {
            val info: Pair<String?, String?> = if (ch.kind == ContentKind.MOVIE) {
                val m = lib.movie(ch.refId)
                val d = m?.let { runCatching { repo.movieDetails(pl, it) }.getOrNull() }
                (ch.description ?: d?.plot ?: m?.plot) to listOfNotNull(
                    getString(R.string.kind_movie), d?.year ?: m?.year, Fmt.rating(d?.rating ?: m?.rating),
                    d?.durationSecs?.takeIf { it > 0 }?.let { Fmt.duration(it / 60) }, d?.genre ?: m?.category,
                ).joinToString("  •  ")
            } else {
                val s = lib.series(ch.refId)
                val d = s?.let { runCatching { repo.seriesDetails(pl, it) }.getOrNull() }
                val ep = d?.episodes?.firstOrNull { it.url == ch.url } ?: s?.episodes?.firstOrNull { it.url == ch.url }
                (ch.description ?: ep?.plot ?: d?.plot ?: s?.plot) to listOfNotNull(
                    getString(R.string.kind_series), s?.name, d?.year ?: s?.year, Fmt.rating(d?.rating ?: s?.rating),
                ).joinToString("  •  ")
            }
            vodInfo[ch.url] = info
            if (channels.getOrNull(index)?.url == ch.url) bindEpg(ch)
        }
    }

    private fun startMedia(ch: Channel) {
        val p = player ?: return
        p.setMediaSource(StreamFactory.create(this, ch, mimeCandidates.getOrNull(mimeAttempt)))
        p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retryCount = 0
                b.tvStatus.isVisible = false
                hideError()
                updateLiveUi()
            }
            if (state == Player.STATE_ENDED && isVod) {
                saveProgress()
                val ch = channels.getOrNull(index)
                if (ch?.kind == ContentKind.SERIES && index < channels.lastIndex) {
                    b.tvStatus.setText(R.string.next_episode_in)
                    b.tvStatus.isVisible = true
                    handler.postDelayed(nextEpisode, 4_000)
                }
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayLiveIcon()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // atualiza o ícone play/pausa da janela PiP
            if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
                runCatching { setPictureInPictureParams(pipParams()) }
            }
        }
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
                    b.tvStatus.isVisible = !(::panel.isInitialized && panel.isShowing)
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

    /** Canal ao vivo: sem barra de tempo e sem botões no meio da tela (a faixa do guia fica livre). */
    private fun updateLiveUi() {
        val ch = channels.getOrNull(index)
        val live = ch?.kind == ContentKind.LIVE || player?.isCurrentMediaItemLive == true
        timeRow?.isVisible = !live
        // centro livre também em filmes: play/pausa e anterior/próximo ficam na linha de baixo
        b.playerView.findViewById<View>(R.id.centerControls)?.isVisible = false
        b.playerView.findViewById<View>(R.id.liveNav)?.isVisible = true
        b.playerView.findViewById<View>(R.id.btnChannels)?.isVisible = live && channels.size > 1
        b.playerView.findViewById<View>(R.id.btnPrevLive)?.isVisible = channels.size > 1
        b.playerView.findViewById<View>(R.id.btnNextLive)?.isVisible = channels.size > 1
        updatePlayLiveIcon()
    }

    private fun updatePlayLiveIcon() {
        b.playerView.findViewById<ImageButton>(R.id.btnPlayLive)?.setImageResource(
            if (player?.playWhenReady == false) R.drawable.ic_pip_play else R.drawable.ic_pip_pause
        )
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
        tvBadge.isVisible = false // visual limpo: qualidade só no botão HQ

        val info = listOfNotNull(
            "${f.width}x${f.height}",
            VideoLabels.codec(f.sampleMimeType),
            VideoLabels.fps(f.frameRate),
            VideoLabels.bitrate(f.bitrate),
            p.audioFormat?.let { VideoLabels.codec(it.sampleMimeType) },
        ).joinToString(" • ")
        tvInfo.text = "${ch.group}  |  $info"

        techLabel = listOfNotNull(res, hdr, VideoLabels.codec(f.sampleMimeType)).joinToString(" • ")


        if (store.matchDisplayMode && Build.VERSION.SDK_INT >= 23) {
            matchDisplayMode(f.width, f.height, f.frameRate)
        }
    }

    // ------------------------------------------------------------------
    // UI / controles
    // ------------------------------------------------------------------

    private fun setupControls() {
        val pv = b.playerView
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                handler.removeCallbacks(hideOsd)
                b.osd.isVisible = false
                channels.getOrNull(index)?.let { bindBanner(ctlBanner, it) }
            }
        })
        pv.setShowNextButton(false)
        pv.setShowPreviousButton(false)
        pv.setShowFastForwardButton(false)
        pv.setShowRewindButton(false)

        pv.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        pv.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { switchChannel(-1) }
        pv.findViewById<ImageButton>(R.id.btnPrevLive).setOnClickListener { switchChannel(-1) }
        pv.findViewById<ImageButton>(R.id.btnChannels).setOnClickListener { openPanel() }
        pv.findViewById<ImageButton>(R.id.btnNextLive).setOnClickListener { switchChannel(+1) }
        pv.findViewById<ImageButton>(R.id.btnPlayLive).setOnClickListener {
            player?.let { it.playWhenReady = !it.playWhenReady }
            updatePlayLiveIcon()
        }
        pv.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { switchChannel(+1) }
        pv.findViewById<ImageButton>(R.id.btnQuality).setOnClickListener { showTrackDialog(C.TRACK_TYPE_VIDEO) }
        pv.findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        pv.findViewById<ImageButton>(R.id.btnSubtitle).setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        pv.findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { cycleResize() }
        pv.findViewById<ImageButton>(R.id.btnMulti).setOnClickListener {
            val ch = channels.getOrNull(index) ?: return@setOnClickListener
            startActivity(
                Intent(this, com.iptv.player4k.ui.multiview.MultiViewActivity::class.java)
                    .putExtra(com.iptv.player4k.ui.multiview.MultiViewActivity.EXTRA_URL, ch.url)
            )
            finish()
        }
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

    /** Faixa de informações embaixo ao trocar de canal (some sozinha). */
    private fun showOsd(ch: Channel) {
        bindBanner(b.bannerOsd, ch)
        if (b.playerView.isControllerFullyVisible) return // o controle já mostra a mesma faixa
        b.osd.isVisible = true
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 6_000)
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
        // lista de canais aberta: as setas e o OK são da lista
        if (::panel.isInitialized && panel.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) panel.poke()
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controllerHidden = !b.playerView.isControllerFullyVisible && !b.errorView.isVisible
            // OK num canal ao vivo em tela cheia: abre a lista de canais
            val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isOk && controllerHidden && channels.getOrNull(index)?.kind == ContentKind.LIVE) {
                consumedKey = code
                if (event.repeatCount == 0) openPanel()
                return true
            }
            // OK num filme/episódio: mostra a sinopse e os controles (mesmo carregando)
            if (isOk && controllerHidden && channels.getOrNull(index)?.kind != ContentKind.LIVE) {
                consumedKey = code
                if (event.repeatCount == 0) {
                    channels.getOrNull(index)?.let { bindBanner(ctlBanner, it) }
                    b.playerView.showController()
                }
                return true
            }
            val delta = when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> +1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
                KeyEvent.KEYCODE_DPAD_UP -> if (controllerHidden && !isVod) +1 else 0
                KeyEvent.KEYCODE_DPAD_DOWN -> if (controllerHidden && !isVod) -1 else 0
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
        return PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(pipActions()).build()
    }

    /** Botões dentro da janela PiP: canal anterior, play/pausa, próximo canal. */
    @RequiresApi(26)
    private fun pipActions(): List<android.app.RemoteAction> {
        fun action(icon: Int, title: Int, cmd: Int) = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, icon), getString(title), getString(title),
            android.app.PendingIntent.getBroadcast(
                this, cmd, Intent(PIP_ACTION).setPackage(packageName).putExtra("cmd", cmd),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val playing = player?.isPlaying == true
        val play = action(if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, R.string.pip_play, 2)
        return if (channels.size > 1) listOf(
            action(R.drawable.ic_pip_prev, R.string.pip_prev, 1), play, action(R.drawable.ic_pip_next, R.string.pip_next, 3),
        ) else listOf(play)
    }

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.getIntExtra("cmd", 0)) {
                1 -> switchChannel(-1)
                2 -> player?.let { it.playWhenReady = !it.playWhenReady }
                3 -> switchChannel(+1)
            }
            if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(pipParams()) }
        }
    }
    private var pipReceiverOn = false

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
        if (isInPictureInPictureMode && !pipReceiverOn) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, pipReceiver, android.content.IntentFilter(PIP_ACTION),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pipReceiverOn = true
        } else if (!isInPictureInPictureMode && pipReceiverOn) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverOn = false
        }
        if (isInPictureInPictureMode) {
            if (::panel.isInitialized) panel.hide()
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

    companion object {
        const val EXTRA_INDEX = "index"
        private const val PIP_ACTION = "com.iptv.player4k.PIP_CONTROL"
        private const val MAX_RETRIES = 4
    }
}
