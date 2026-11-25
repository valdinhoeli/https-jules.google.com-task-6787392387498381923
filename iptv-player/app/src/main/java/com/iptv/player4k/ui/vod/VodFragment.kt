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
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.ui.details.DetailsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aba de Filmes ou Séries no estilo dos apps de streaming. */
class VodFragment : Fragment(R.layout.fragment_vod) {
    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentVodBinding? = null
    private lateinit var homeAdapter: VodHomeAdapter
    private lateinit var searchAdapter: PosterAdapter
    private var buildJob: Job? = null
    private var lastLibrary: Library? = null

    private val kind: ContentKind
        get() = ContentKind.valueOf(requireArguments().getString(ARG_KIND, ContentKind.MOVIE.name))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentVodBinding.bind(view).also { this.b = it }
        homeAdapter = VodHomeAdapter(onOpen = ::open, onPlay = ::play)
        b.rvHome.layoutManager = LinearLayoutManager(requireContext())
        b.rvHome.adapter = homeAdapter
        b.rvHome.itemAnimator = null
        b.rvHome.setItemViewCacheSize(6)

        val density = resources.displayMetrics.density
        val widthPx = resources.displayMetrics.widthPixels - (20 * density).toInt()
        val cell = resources.getDimensionPixelSize(R.dimen.poster_w) + (10 * density).toInt()
        val span = (widthPx / cell).coerceAtLeast(2)
        val itemW = widthPx / span - (10 * density).toInt()
        searchAdapter = PosterAdapter(::open, fixedWidth = itemW)
        b.rvSearch.layoutManager = GridLayoutManager(requireContext(), span)
        b.rvSearch.adapter = searchAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    vm.library,
                    vm.state.map { it.query }.distinctUntilChanged(),
                    vm.contentVersion,
                ) { lib, q, _ -> lib to q }.collect { (lib, q) -> render(lib, q) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b = null
    }

    private fun render(st: com.iptv.player4k.ui.LibraryState, query: String) {
        val b = b ?: return
        val lib = st.library
        val empty = if (kind == ContentKind.MOVIE) lib.movies.isEmpty() else lib.series.isEmpty()
        b.vodEmpty.isVisible = empty
        b.vodLoading.isVisible = empty && st.loading
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
        val searching = query.isNotBlank() && !empty
        b.searchBox.isVisible = searching
        b.rvHome.isVisible = !searching && !empty

        val pid = vm.state.value.playlist?.id ?: return
        buildJob?.cancel()
        buildJob = viewLifecycleOwner.lifecycleScope.launch {
            if (searching) {
                val res = withContext(Dispatchers.Default) { VodRows.search(lib, kind, query) }
                searchAdapter.submit(res)
                b.tvSearchTitle.text = if (res.isEmpty()) getString(R.string.no_results) else getString(R.string.search_results)
            } else if (!empty) {
                val progress = vm.store.progressMap(pid)
                val mine = vm.store.myList(pid)
                val ctx = requireContext().applicationContext
                val rows = withContext(Dispatchers.Default) { VodRows.build(ctx, lib, kind, progress, mine) }
                val newLib = lib !== lastLibrary
                lastLibrary = lib
                homeAdapter.submit(rows)
                if (newLib) b.rvHome.scrollToPosition(0)
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
        fun newInstance(kind: ContentKind) = VodFragment().apply { arguments = bundleOf(ARG_KIND to kind.name) }
    }
}
