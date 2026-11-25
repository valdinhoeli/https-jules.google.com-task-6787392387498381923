package com.iptv.player4k.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player4k.R
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.databinding.FragmentLiveBinding
import com.iptv.player4k.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Aba "Ao vivo": grupos + canais com o que está passando agora. */
class LiveFragment : Fragment(R.layout.fragment_live) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentLiveBinding? = null
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private var lastGroup: String? = null
    private var lastQuery: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentLiveBinding.bind(view).also { this.b = it }
        val wide = resources.getBoolean(R.bool.wide_layout)
        groupAdapter = GroupAdapter(vertical = wide) { vm.selectGroup(it) }
        b.rvGroups.layoutManager = LinearLayoutManager(
            requireContext(), if (wide) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL, false
        )
        b.rvGroups.adapter = groupAdapter
        b.rvGroups.itemAnimator = null

        channelAdapter = ChannelAdapter(
            onClick = ::play,
            onLongClick = { ch ->
                val added = vm.toggleFavorite(ch)
                Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
            },
        )
        val widthDp = resources.configuration.screenWidthDp
        val span = if (wide) ((widthDp - 260) / 420).coerceIn(1, 3) else 1
        b.rvChannels.layoutManager = GridLayoutManager(requireContext(), span)
        b.rvChannels.adapter = channelAdapter
        b.rvChannels.setHasFixedSize(true)
        b.rvChannels.itemAnimator = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch { vm.epg.collect { channelAdapter.setEpg(it.data) } }
                launch {
                    while (true) {
                        delay(30_000)
                        channelAdapter.tick()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b = null
    }

    private fun render(s: UiState) {
        val b = b ?: return
        groupAdapter.submit(s.groups, s.selectedGroup)
        channelAdapter.submit(s.channels, s.favorites)
        b.tvCount.text = resources.getQuantityString(R.plurals.channel_count, s.channels.size, s.channels.size)
        if (lastGroup != s.selectedGroup || lastQuery != s.query) {
            lastGroup = s.selectedGroup
            lastQuery = s.query
            b.rvChannels.scrollToPosition(0)
        }
    }

    private fun play(position: Int) {
        val list = channelAdapter.items
        if (position !in list.indices) return
        PlaybackQueue.set(vm.state.value.playlist, list)
        vm.addRecent(list[position])
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position))
    }
}
