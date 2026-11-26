package com.iptv.player4k.ui.kids

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.KidsContent
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.Session
import com.iptv.player4k.databinding.FragmentKidsBinding
import com.iptv.player4k.databinding.ItemKidsChannelBinding
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.ui.details.DetailsActivity
import com.iptv.player4k.ui.vod.HomeRow
import com.iptv.player4k.ui.vod.PosterItem
import com.iptv.player4k.ui.vod.VodHomeAdapter
import com.iptv.player4k.ui.vod.VodRows
import com.iptv.player4k.util.CoverArt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Aba KIDS: tudo que é infantil na lista — canais, filmes e séries. */
class KidsFragment : Fragment(R.layout.fragment_kids) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentKidsBinding? = null
    private val channelAdapter = ChannelsAdapter()
    private lateinit var vodAdapter: VodHomeAdapter
    private var shown: KidsContent? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentKidsBinding.bind(view).also { this.b = it }
        b.rvKidsChannels.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        b.rvKidsChannels.adapter = channelAdapter
        b.rvKidsChannels.itemAnimator = null
        vodAdapter = VodHomeAdapter(onOpen = ::open, onPlay = ::play)
        b.rvKidsVod.layoutManager = LinearLayoutManager(requireContext())
        b.rvKidsVod.adapter = vodAdapter
        b.rvKidsVod.itemAnimator = null
        b.tvKidsEmpty.setText(R.string.kids_empty)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.kids, vm.contentVersion, vm.epg) { k, _, _ -> k }.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        b = null
        shown = null
        super.onDestroyView()
    }

    private fun render(k: KidsContent) {
        val b = b ?: return
        val loading = vm.library.value.loading && k.isEmpty
        b.kidsEmpty.isVisible = k.isEmpty && !loading
        b.kidsContent.isVisible = !k.isEmpty
        if (k.isEmpty) return
        b.tvKidsChannels.isVisible = k.channels.isNotEmpty()
        b.rvKidsChannels.isVisible = k.channels.isNotEmpty()
        b.tvKidsChannels.text = getString(R.string.kids_channels) + "  ·  " + k.channels.size
        channelAdapter.submit(k.channels)
        if (shown === k) return
        shown = k
        vodAdapter.submit(rows(k))
    }

    private fun rows(k: KidsContent): List<HomeRow> {
        val movies = k.movies.map { VodRows.poster(it) }
        val series = k.series.map { VodRows.poster(it) }
        val rows = ArrayList<HomeRow>()
        // destaque: os mais novos (ou mais bem avaliados) entre filmes e séries
        val pool = (movies + series)
        val hero = (if (pool.any { it.added > 0 }) pool.sortedByDescending { it.added } else pool.sortedByDescending { it.rating ?: 0f }).take(5)
        if (hero.isNotEmpty()) rows += HomeRow.Hero(hero, newest = false, badges = hero.map { getString(R.string.kids_badge) })
        if (movies.isNotEmpty()) rows += HomeRow.Row(getString(R.string.kids_movies), movies.sortedByDescending { it.added })
        if (series.isNotEmpty()) rows += HomeRow.Row(getString(R.string.kids_series), series.sortedByDescending { it.added })
        // por categoria, quando a lista separa (Animação, Infantil, Família…)
        val byCat = (movies + series).groupBy { it.category }.filter { it.value.size >= 2 }
        if (byCat.size > 1) byCat.toSortedMap().forEach { (cat, items) -> rows += HomeRow.Row(cat, items) }
        return rows
    }

    private fun open(item: PosterItem) {
        startActivity(
            Intent(requireContext(), DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_KIND, if (item.movie != null) ContentKind.MOVIE.name else ContentKind.SERIES.name)
                .putExtra(DetailsActivity.EXTRA_ID, item.movie?.id ?: item.series!!.id)
        )
    }

    private fun play(item: PosterItem) {
        val m = item.movie
        if (m != null) VodRows.playMovie(requireContext(), m) else open(item)
    }

    private fun playChannel(list: List<Channel>, pos: Int) {
        // a fila do player são os canais infantis: ▲/▼ troca só entre eles
        PlaybackQueue.set(vm.state.value.playlist, list)
        vm.addRecent(list[pos])
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, pos))
    }

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelsAdapter.VH>() {
        private var items: List<Channel> = emptyList()

        fun submit(list: List<Channel>) {
            if (list == items) { notifyItemRangeChanged(0, items.size); return }
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemKidsChannelBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = ItemKidsChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val vh = VH(v)
            v.root.setOnClickListener { vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { playChannel(items, it) } }
            v.root.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.08f else 1f
                view.animate().scaleX(s).scaleY(s).translationZ(if (has) 10f else 0f).setDuration(120).start()
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            with(holder.v) {
                tvKcName.text = ch.name
                val now = Session.epg?.now(ch)
                tvKcNow.text = now?.title ?: ch.group
                val logo = ch.logo ?: Session.epg?.logo(ch)
                val art = CoverArt.channel(ch.name)
                if (logo.isNullOrBlank()) ivKcLogo.setImageDrawable(art) else ivKcLogo.load(logo) { error(art); placeholder(art) }
                root.contentDescription = ch.name
            }
        }
    }
}
