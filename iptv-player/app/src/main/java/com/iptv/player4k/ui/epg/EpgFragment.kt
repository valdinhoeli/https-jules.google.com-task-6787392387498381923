package com.iptv.player4k.ui.epg

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.databinding.FragmentEpgBinding
import com.iptv.player4k.epg.Programme
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.player.PreviewPlayer
import com.iptv.player4k.ui.EpgState
import com.iptv.player4k.ui.GroupAdapter
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.util.CoverArt
import com.iptv.player4k.util.Fmt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Aba "Guia": pré-visualização ao vivo do canal selecionado + informações do programa em cima,
 * grade de programação embaixo.
 */
class EpgFragment : Fragment(R.layout.fragment_epg) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentEpgBinding? = null
    private lateinit var groupAdapter: GroupAdapter
    private var preview: PreviewPlayer? = null
    private var selected: Pair<Channel, Programme?>? = null
    private val handler = Handler(Looper.getMainLooper())

    /** espera o foco parar num canal antes de trocar a pré-visualização */
    private val startPreview = Runnable {
        val ch = selected?.first ?: return@Runnable
        if (!isHidden && isResumed) {
            preview?.play(ch)
            b?.epgGrid?.playingUrl = ch.url
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEpgBinding.bind(view).also { this.b = it }
        groupAdapter = GroupAdapter(vertical = false) { vm.selectGroup(it) }
        b.rvEpgGroups.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.rvEpgGroups.adapter = groupAdapter
        b.rvEpgGroups.itemAnimator = null
        preview = PreviewPlayer(requireContext(), b.preview, b.previewLoading, b.ivPreviewLogo)

        b.epgGrid.listener = object : EpgGridView.Listener {
            override fun onFocus(channel: Channel, programme: Programme?) = showHeader(channel, programme)
            override fun onActivate(channel: Channel, programme: Programme?) = activate(channel, programme)
        }
        b.previewBox.setOnClickListener { selected?.first?.let { play(it) } }
        b.btnEpgFav.setOnClickListener {
            val ch = selected?.first ?: return@setOnClickListener
            val added = vm.toggleFavorite(ch)
            Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
            bindFav(ch)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.state, vm.epg) { s, e -> s to e }.collect { (s, e) ->
                    // escondida: não refaz a grade de milhares de canais (refaz ao aparecer)
                    if (isHidden) { pendingRender = true; return@collect }
                    groupAdapter.submit(s.groups, s.selectedGroup)
                    b.epgGrid.setData(s.channels, e.data)
                    renderStatus(e)
                    if (s.channels.isEmpty()) clearHeader()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) schedulePreview(300)
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    private var pendingRender = false

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && pendingRender) {
            pendingRender = false
            val s = vm.state.value; val e = vm.epg.value
            b?.let { bb ->
                groupAdapter.submit(s.groups, s.selectedGroup)
                bb.epgGrid.setData(s.channels, e.data)
                renderStatus(e)
                if (s.channels.isEmpty()) clearHeader()
            }
        }
        if (hidden) stopPreview() else schedulePreview(300)
    }

    override fun onDestroyView() {
        stopPreview()
        preview = null
        super.onDestroyView()
        b = null
    }

    private fun schedulePreview(delay: Long) {
        handler.removeCallbacks(startPreview)
        handler.postDelayed(startPreview, delay)
    }

    private fun stopPreview() {
        handler.removeCallbacks(startPreview)
        preview?.release()
        b?.epgGrid?.playingUrl = null
    }

    private fun renderStatus(e: EpgState) {
        val b = b ?: return
        val msg = when {
            e.loading && e.data == null -> getString(R.string.epg_loading)
            e.noSource -> getString(R.string.epg_no_source)
            e.error != null && e.data == null -> getString(R.string.epg_error, e.error)
            e.data != null && e.data.isEmpty -> getString(R.string.epg_no_source)
            else -> null
        }
        b.tvEpgStatus.isVisible = msg != null
        b.tvEpgStatus.text = msg
    }

    private fun clearHeader() {
        val b = b ?: return
        b.tvEpgTitle.text = ""
        b.tvEpgChannel.text = ""
        b.tvEpgDesc.text = ""
        b.tvEpgTime.text = ""
        b.tvEpgRemain.text = ""
        b.epgHeaderProgress.isVisible = false
    }

    private fun bindFav(ch: Channel) {
        val fav = ch.url in vm.state.value.favorites
        b?.btnEpgFav?.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun showHeader(ch: Channel, p: Programme?) {
        val b = b ?: return
        val changedChannel = selected?.first?.url != ch.url
        selected = ch to p
        val now = System.currentTimeMillis()
        bindFav(ch)
        b.tvEpgChannel.text = "${ch.name}  ·  ${ch.group}"

        if (changedChannel) {
            val logo = ch.logo ?: vm.epg.value.data?.logo(ch)
            val art = CoverArt.channel(ch.name)
            if (logo.isNullOrBlank()) b.ivPreviewLogo.setImageDrawable(art)
            else b.ivPreviewLogo.load(logo) { error(art) }
            if (preview?.current?.url != ch.url) b.ivPreviewLogo.isVisible = true
            schedulePreview(900)
        }

        if (p == null) {
            b.tvEpgTitle.setText(R.string.epg_no_info)
            b.tvEpgTime.text = ""
            b.tvEpgRemain.text = ""
            b.tvEpgDesc.text = ""
            b.epgHeaderProgress.isVisible = false
            return
        }
        b.tvEpgTitle.text = p.title
        b.tvEpgTime.text = "${Fmt.time(p.start)} — ${Fmt.time(p.stop)}"
        b.tvEpgDesc.text = p.desc ?: ""
        val live = p.isLive(now)
        b.epgHeaderProgress.isVisible = live
        if (live) b.epgHeaderProgress.setProgressCompat((p.progress(now) * 1000).toInt(), false)
        b.tvEpgRemain.text = when {
            live -> getString(R.string.minutes, ((p.stop - now) / 60_000L).toInt().coerceAtLeast(1))
            p.start > now -> getString(R.string.epg_starts_in, Fmt.duration(((p.start - now) / 60_000L).toInt().coerceAtLeast(1)))
            else -> getString(R.string.epg_ended)
        }
    }

    private fun activate(ch: Channel, p: Programme?) {
        val now = System.currentTimeMillis()
        if (p == null || p.isLive(now)) {
            play(ch)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.title)
            .setMessage(
                listOfNotNull(
                    "${ch.name}\n${Fmt.day(p.start)} · ${Fmt.range(p.start, p.stop)} · ${Fmt.duration(p.minutes)}",
                    p.desc,
                ).joinToString("\n\n")
            )
            .setPositiveButton(R.string.epg_watch_channel) { _, _ -> play(ch) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun play(ch: Channel) {
        stopPreview()
        val list = vm.state.value.channels
        val index = list.indexOfFirst { it.url == ch.url }.coerceAtLeast(0)
        PlaybackQueue.set(vm.state.value.playlist, list.ifEmpty { listOf(ch) })
        vm.addRecent(ch)
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
    }
}
