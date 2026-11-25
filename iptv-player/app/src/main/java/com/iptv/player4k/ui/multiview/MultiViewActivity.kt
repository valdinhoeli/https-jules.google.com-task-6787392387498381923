package com.iptv.player4k.ui.multiview

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Session
import com.iptv.player4k.databinding.ActivityMultiviewBinding
import com.iptv.player4k.databinding.DialogChannelPickerBinding
import com.iptv.player4k.databinding.ItemMvSlotBinding
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.player.StreamFactory
import com.iptv.player4k.ui.ChannelAdapter
import com.iptv.player4k.ui.SecureScreen
import com.iptv.player4k.util.Fmt
import com.iptv.player4k.util.Text

/**
 * Multi-tela: 2 ou 4 canais ao vivo ao mesmo tempo.
 * O quadro selecionado toca o áudio; os outros ficam mudos.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : AppCompatActivity() {

    private class Slot(val b: ItemMvSlotBinding) {
        var channel: Channel? = null
        var player: ExoPlayer? = null
        var mimes: List<String?> = emptyList()
        var attempt = 0
    }

    private lateinit var b: ActivityMultiviewBinding
    private lateinit var store: PlaylistStore
    private lateinit var slots: List<Slot>
    private var mode = 4
    private var active = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideHelp = Runnable { b.tvMvHelp.isVisible = false; b.mvBar.alpha = 0.35f }
    private val epgTicker = object : Runnable {
        override fun run() {
            slots.forEach { bindLabel(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        SecureScreen.apply(this)
        store = PlaylistStore(this)
        hideSystemUi()

        slots = listOf(b.slot0, b.slot1, b.slot2, b.slot3).map { Slot(it) }
        val byUrl = Session.live.associateBy { it.url }
        store.multiViewSlots().forEachIndexed { i, url -> slots[i].channel = byUrl[url] }
        intent.getStringExtra(EXTRA_URL)?.let { url ->
            byUrl[url]?.let { ch ->
                val i = slots.indexOfFirst { it.channel?.url == url }.takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.channel == null }.takeIf { it >= 0 } ?: 0
                slots[i].channel = ch
                active = i
            }
        }
        mode = getSharedPreferences("mv", MODE_PRIVATE).getInt("mode", 4)

        slots.forEachIndexed { i, s ->
            s.b.root.setOnClickListener { onSlotClick(i) }
            s.b.root.setOnLongClickListener { pick(i); true }
            s.b.root.setOnFocusChangeListener { _, has -> if (has) showHelp() }
            bindLabel(s)
        }
        b.btnMode2.setOnClickListener { setMode(2) }
        b.btnMode4.setOnClickListener { setMode(4) }
        b.btnMvClose.setOnClickListener { finish() }
        applyMode()
        showHelp()
        if (Session.live.isEmpty()) Toast.makeText(this, R.string.mv_no_channels, Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        slots.forEachIndexed { i, s -> if (i < mode) start(s) }
        setActive(active)
        slots[active].b.root.requestFocus()
        handler.postDelayed(epgTicker, 30_000)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(epgTicker)
        slots.forEach { release(it) }
        store.setMultiViewSlots(slots.map { it.channel?.url.orEmpty() })
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

    private fun showHelp() {
        b.tvMvHelp.isVisible = true
        b.mvBar.alpha = 1f
        handler.removeCallbacks(hideHelp)
        handler.postDelayed(hideHelp, 5_000)
    }

    // ---------------- modo 2/4 ----------------

    private fun setMode(m: Int) {
        if (m == mode) return
        mode = m
        getSharedPreferences("mv", MODE_PRIVATE).edit().putInt("mode", m).apply()
        applyMode()
        slots.forEachIndexed { i, s -> if (i < mode) start(s) else release(s) }
        if (active >= mode) setActive(0)
    }

    private fun applyMode() {
        b.row2.isVisible = mode == 4
        b.btnMode2.alpha = if (mode == 2) 1f else 0.6f
        b.btnMode4.alpha = if (mode == 4) 1f else 0.6f
    }

    // ---------------- quadros ----------------

    private fun onSlotClick(i: Int) {
        val s = slots[i]
        when {
            s.channel == null -> pick(i)
            active == i -> fullscreen(i)
            else -> setActive(i)
        }
        showHelp()
    }

    private fun setActive(i: Int) {
        active = i
        slots.forEachIndexed { idx, s ->
            s.player?.volume = if (idx == i) 1f else 0f
            s.b.root.isSelected = idx == i
            s.b.ivAudio.isVisible = idx == i
        }
    }

    private fun fullscreen(i: Int) {
        val ch = slots[i].channel ?: return
        val list = Session.live.ifEmpty { listOf(ch) }
        PlaybackQueue.set(Session.playlist, list)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, list.indexOfFirst { it.url == ch.url }.coerceAtLeast(0))
        )
    }

    private fun start(s: Slot) {
        val ch = s.channel ?: return
        release(s)
        val maxH = if (mode == 4) 720 else 1080
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 25_000, 1_500, 3_000)
                    .setPrioritizeTimeOverSizeThresholds(true).build()
            )
            .build()
        // vários vídeos ao mesmo tempo: limita a resolução para não sobrecarregar o aparelho
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(maxH * 16 / 9, maxH)
            .build()
        p.volume = if (slots.indexOf(s) == active) 1f else 0f
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // tenta o próximo formato (ex.: HLS sem extensão) e depois reconecta
                s.attempt++
                handler.postDelayed({ if (s.player === p) load(s) }, if (s.attempt < s.mimes.size) 0L else 3_000L)
            }
        })
        s.b.pv.player = p
        s.player = p
        s.mimes = StreamFactory.candidatesFor(ch.url)
        s.attempt = 0
        load(s)
        bindLabel(s)
    }

    private fun load(s: Slot) {
        val p = s.player ?: return
        val ch = s.channel ?: return
        val mime = s.mimes.getOrNull(s.attempt % s.mimes.size.coerceAtLeast(1))
        p.setMediaSource(StreamFactory.create(this, ch, mime))
        p.prepare()
        p.playWhenReady = true
    }

    private fun release(s: Slot) {
        s.player?.release()
        s.player = null
        s.b.pv.player = null
    }

    private fun bindLabel(s: Slot) {
        val ch = s.channel
        s.b.emptyHint.isVisible = ch == null
        s.b.label.isVisible = ch != null
        if (ch == null) return
        s.b.tvName.text = ch.name
        val now = Session.epg?.now(ch)
        s.b.tvNow.isVisible = now != null
        s.b.tvNow.text = now?.let { "● ${it.title}  ${Fmt.range(it.start, it.stop)}" }
    }

    /** Escolha de canal com busca. */
    private fun pick(i: Int) {
        val channels = Session.live
        if (channels.isEmpty()) {
            Toast.makeText(this, R.string.mv_no_channels, Toast.LENGTH_SHORT).show()
            return
        }
        val db = DialogChannelPickerBinding.inflate(layoutInflater)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val pickAdapter = ChannelAdapter(
            onClick = { pos ->
                val list = (db.rvPick.adapter as ChannelAdapter).items
                list.getOrNull(pos)?.let { ch ->
                    slots[i].channel = ch
                    if (i < mode) start(slots[i])
                    setActive(i)
                }
                dialog?.dismiss()
            },
            onLongClick = {},
        )
        pickAdapter.setEpg(Session.epg)
        pickAdapter.submit(channels, emptySet())
        db.rvPick.layoutManager = LinearLayoutManager(this)
        db.rvPick.adapter = pickAdapter
        db.rvPick.layoutParams.height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        db.etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                val q = Text.normalize(e?.toString().orEmpty().trim())
                pickAdapter.submit(if (q.isEmpty()) channels else channels.filter { it.searchKey.contains(q) }, emptySet())
            }
        })
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mv_choose_title, i + 1))
            .setView(db.root)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val cur = slots[i].channel?.let { c -> channels.indexOfFirst { it.url == c.url } } ?: -1
        if (cur >= 0) db.rvPick.scrollToPosition(cur)
        db.rvPick.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            showHelp()
            if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
                val focused = slots.indexOfFirst { it.b.root.hasFocus() }.takeIf { it >= 0 } ?: active
                pick(focused)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
