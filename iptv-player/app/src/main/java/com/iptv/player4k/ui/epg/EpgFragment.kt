package com.iptv.player4k.ui.epg

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.iptv.player4k.ui.EpgState
import com.iptv.player4k.ui.GroupAdapter
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.util.Fmt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Aba "Guia": grade de programação com destaque do programa selecionado. */
class EpgFragment : Fragment(R.layout.fragment_epg) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentEpgBinding? = null
    private lateinit var groupAdapter: GroupAdapter
    private var selected: Pair<Channel, Programme?>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEpgBinding.bind(view).also { this.b = it }
        groupAdapter = GroupAdapter(vertical = false) { vm.selectGroup(it) }
        b.rvEpgGroups.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.rvEpgGroups.adapter = groupAdapter
        b.rvEpgGroups.itemAnimator = null

        b.epgGrid.listener = object : EpgGridView.Listener {
            override fun onFocus(channel: Channel, programme: Programme?) = showHeader(channel, programme)
            override fun onActivate(channel: Channel, programme: Programme?) = activate(channel, programme)
        }
        b.btnEpgNow.setOnClickListener { b.epgGrid.goToNow() }
        b.btnEpgWatch.setOnClickListener { selected?.first?.let { play(it) } }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.state, vm.epg) { s, e -> s to e }.collect { (s, e) ->
                    groupAdapter.submit(s.groups, s.selectedGroup)
                    b.epgGrid.setData(s.channels, e.data)
                    renderStatus(e)
                    if (s.channels.isEmpty()) clearHeader()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b = null
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
        b.tvEpgBadge.isVisible = false
        b.epgHeaderProgress.isVisible = false
    }

    private fun showHeader(ch: Channel, p: Programme?) {
        val b = b ?: return
        selected = ch to p
        val now = System.currentTimeMillis()
        if (ch.logo.isNullOrBlank()) b.ivEpgLogo.setImageResource(R.drawable.ic_channel_placeholder)
        else b.ivEpgLogo.load(ch.logo) { error(R.drawable.ic_channel_placeholder) }
        b.tvEpgChannel.text = ch.name
        if (p == null) {
            b.tvEpgBadge.isVisible = false
            b.tvEpgTitle.text = ch.name
            b.tvEpgChannel.text = ch.group
            b.tvEpgTime.text = getString(R.string.epg_no_info)
            b.tvEpgDesc.text = ""
            b.epgHeaderProgress.isVisible = false
            return
        }
        val live = p.isLive(now)
        b.tvEpgBadge.isVisible = true
        b.tvEpgBadge.setText(
            when {
                live -> R.string.epg_live
                p.stop <= now -> R.string.epg_ended
                else -> R.string.epg_later
            }
        )
        b.tvEpgBadge.setBackgroundResource(if (live) R.drawable.bg_live_pill else R.drawable.bg_pill_outline)
        val extra = when {
            live -> getString(R.string.epg_remaining, ((p.stop - now) / 60_000L).toInt().coerceAtLeast(1))
            p.start > now -> getString(R.string.epg_starts_in, Fmt.duration(((p.start - now) / 60_000L).toInt().coerceAtLeast(1)))
            else -> Fmt.duration(p.minutes)
        }
        if (resources.getBoolean(R.bool.wide_layout)) {
            b.tvEpgTime.text = "${Fmt.range(p.start, p.stop)}  ·  $extra"
        } else {
            // tela estreita: horário curto e o "restam/começa em" na linha do canal
            b.tvEpgTime.text = Fmt.range(p.start, p.stop)
            b.tvEpgChannel.text = "${ch.name}  ·  $extra"
        }
        b.tvEpgTitle.text = p.title
        b.tvEpgDesc.text = p.desc ?: ""
        b.epgHeaderProgress.isVisible = live
        if (live) b.epgHeaderProgress.setProgressCompat((p.progress(now) * 1000).toInt(), true)
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
        val list = vm.state.value.channels
        val index = list.indexOfFirst { it.url == ch.url }.coerceAtLeast(0)
        PlaybackQueue.set(vm.state.value.playlist, list.ifEmpty { listOf(ch) })
        vm.addRecent(ch)
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
    }
}
