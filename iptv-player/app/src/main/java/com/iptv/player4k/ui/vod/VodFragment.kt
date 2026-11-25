package com.iptv.player4k.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player4k.R
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Library
import com.iptv.player4k.databinding.FragmentVodBinding
import com.iptv.player4k.ui.GroupAdapter
import com.iptv.player4k.ui.LibraryState
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.ui.details.DetailsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aba de Filmes ou Séries: início no estilo streaming + navegação por grupo. */
class VodFragment : Fragment(R.layout.fragment_vod) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentVodBinding? = null
    private lateinit var homeAdapter: VodHomeAdapter
    private lateinit var gridAdapter: PosterAdapter
    private lateinit var groupAdapter: GroupAdapter
    private var buildJob: Job? = null
    private var lastLibrary: Library? = null
    private val group = MutableStateFlow(VodRows.GROUP_HOME)

    private val kind: ContentKind
        get() = ContentKind.valueOf(requireArguments().getString(ARG_KIND, ContentKind.MOVIE.name))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentVodBinding.bind(view).also { this.b = it }
        savedInstanceState?.getString(KEY_GROUP)?.let { group.value = it }
        homeAdapter = VodHomeAdapter(onOpen = ::open, onPlay = ::play)
        b.rvHome.layoutManager = LinearLayoutManager(requireContext())
        b.rvHome.adapter = homeAdapter
        b.rvHome.itemAnimator = null
        b.rvHome.setItemViewCacheSize(6)

        // barra lateral só em telas largas (TV/tablet); no celular, fileira no topo
        val wide = resources.configuration.screenWidthDp >= 900
        groupAdapter = GroupAdapter(vertical = wide) { group.value = it }
        b.rvVodGroups.layoutManager = LinearLayoutManager(
            requireContext(), if (wide) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL, false
        )
        b.rvVodGroups.adapter = groupAdapter
        b.rvVodGroups.itemAnimator = null

        val density = resources.displayMetrics.density
        val contentW = resources.displayMetrics.widthPixels - (if (wide) 230 * density else 0f).toInt() - (20 * density).toInt()
        val cell = resources.getDimensionPixelSize(R.dimen.poster_w) + (10 * density).toInt()
        val span = (contentW / cell).coerceAtLeast(2)
        val itemW = contentW / span - (10 * density).toInt()
        gridAdapter = PosterAdapter(::open, fixedWidth = itemW)
        b.rvSearch.layoutManager = GridLayoutManager(requireContext(), span)
        b.rvSearch.adapter = gridAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    vm.library,
                    vm.state.map { it.query }.distinctUntilChanged(),
                    vm.contentVersion,
                    group,
                ) { lib, q, _, g -> Triple(lib, q, g) }.collect { (lib, q, g) -> render(lib, q, g) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_GROUP, group.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b = null
    }

    private fun render(st: LibraryState, query: String, selected: String) {
        val b = b ?: return
        val lib = st.library
        val empty = if (kind == ContentKind.MOVIE) lib.movies.isEmpty() else lib.series.isEmpty()
        b.vodEmpty.isVisible = empty
        b.vodLoading.isVisible = empty && st.loading
        b.rvVodGroups.isVisible = !empty
        when {
            !empty -> Unit
            st.loading -> {
                b.tvVodEmptyTitle.setText(R.string.loading_library)
                b.tvVodEmptyText.text = ""
            }
            else -> {
                b.tvVodEmptyTitle.setText(if (kind == ContentKind.MOVIE) R.string.no_movies else R.string.no_series)
                b.tvVodEmptyText.text = st.error?.let { getString(R.string.err_load, it) } ?: getString(R.string.no_vod_hint)
            }
        }
        if (empty) {
            b.searchBox.isVisible = false
            b.rvHome.isVisible = false
            return
        }

        val pid = vm.state.value.playlist?.id ?: return
        val rules = vm.rules()
        val searching = query.isNotBlank()
        val home = !searching && selected == VodRows.GROUP_HOME
        b.searchBox.isVisible = !home
        b.rvHome.isVisible = home

        buildJob?.cancel()
        buildJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val progress = vm.store.progressMap(pid)
            val mine = vm.store.myList(pid)
            val groups = withContext(Dispatchers.Default) { VodRows.groups(ctx, lib, kind, rules, mine) }
            val sel = selected.takeIf { s -> groups.any { it.key == s } } ?: VodRows.GROUP_HOME
            groupAdapter.submit(groups, sel)
            when {
                searching -> {
                    val res = withContext(Dispatchers.Default) { VodRows.search(lib, kind, query, rules) }
                    gridAdapter.submit(res)
                    b.tvSearchTitle.text = if (res.isEmpty()) getString(R.string.no_results) else getString(R.string.search_results)
                }
                sel != VodRows.GROUP_HOME -> {
                    val items = withContext(Dispatchers.Default) { VodRows.groupItems(lib, kind, sel, rules, mine) }
                    gridAdapter.submit(items)
                    val label = groups.firstOrNull { it.key == sel }?.label ?: sel
                    b.tvSearchTitle.text = getString(R.string.group_items_count, label, items.size)
                    b.rvSearch.scrollToPosition(0)
                }
                else -> {
                    val rows = withContext(Dispatchers.Default) { VodRows.build(ctx, lib, kind, progress, mine, rules) }
                    val newLib = lib !== lastLibrary
                    lastLibrary = lib
                    homeAdapter.submit(rows)
                    if (newLib) b.rvHome.scrollToPosition(0)
                }
            }
        }
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

    companion object {
        private const val ARG_KIND = "kind"
        private const val KEY_GROUP = "group"
        fun newInstance(kind: ContentKind) = VodFragment().apply { arguments = bundleOf(ARG_KIND to kind.name) }
    }
}
