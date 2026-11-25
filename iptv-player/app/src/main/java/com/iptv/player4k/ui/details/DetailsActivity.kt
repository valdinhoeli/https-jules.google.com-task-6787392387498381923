package com.iptv.player4k.ui.details

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.chip.Chip
import com.iptv.player4k.R
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Episode
import com.iptv.player4k.data.LibraryRepository
import com.iptv.player4k.data.MediaDetails
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.SeriesItem
import com.iptv.player4k.data.Session
import com.iptv.player4k.data.VodItem
import com.iptv.player4k.databinding.ActivityDetailsBinding
import com.iptv.player4k.ui.vod.VodRows
import com.iptv.player4k.util.Fmt
import kotlinx.coroutines.launch

/** Detalhes de um filme ou série: fundo, sinopse, elenco, temporadas e episódios. */
class DetailsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDetailsBinding
    private lateinit var store: PlaylistStore
    private lateinit var repo: LibraryRepository
    private var movie: VodItem? = null
    private var series: SeriesItem? = null
    private var details: MediaDetails? = null
    private var episodes: List<Episode> = emptyList()
    private var season = -1
    private lateinit var epAdapter: EpisodeAdapter

    private val pid get() = Session.playlist?.id.orEmpty()
    private val myKey get() = movie?.let { "m:${it.id}" } ?: "s:${series?.id}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)
        com.iptv.player4k.ui.SecureScreen.apply(this)
        store = PlaylistStore(this)
        repo = LibraryRepository(this)

        val kind = intent.getStringExtra(EXTRA_KIND)
        val id = intent.getStringExtra(EXTRA_ID)
        val lib = Session.library
        if (kind == ContentKind.MOVIE.name) movie = lib.movie(id) else series = lib.series(id)
        if (movie == null && series == null) { finish(); return }

        ViewCompat.setOnApplyWindowInsetsListener(b.btnBack) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> { topMargin = top + (12 * resources.displayMetrics.density).toInt() }
            insets
        }
        b.btnBack.setOnClickListener { finish() }

        epAdapter = EpisodeAdapter({ series?.poster }) { i -> playEpisode(i) }
        b.rvEpisodes.layoutManager = LinearLayoutManager(this)
        b.rvEpisodes.adapter = epAdapter

        b.btnPlay.setOnClickListener { playMain() }
        b.btnMyList.setOnClickListener {
            val added = store.toggleMyList(pid, myKey)
            Toast.makeText(this, if (added) R.string.added_mylist else R.string.removed_mylist, Toast.LENGTH_SHORT).show()
            bindMyList()
        }

        bindBasic()
        series?.let { s ->
            b.seriesBox.isVisible = true
            setEpisodes(s.episodes)
        }
        loadDetails()
        if ((movie?.xtreamId ?: series?.xtreamId) == null || Session.creds == null) showNoPlotHint()
        b.btnPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        bindResume()
        if (series != null) showSeason(season)
    }

    private fun bindBasic() {
        val m = movie
        val s = series
        val title = m?.name ?: s!!.name
        val poster = m?.poster ?: s?.poster
        val backdrop = details?.backdrop ?: m?.backdrop ?: s?.backdrop
        b.tvKind.setText(if (m != null) R.string.kind_movie else R.string.kind_series)
        b.tvTitle.text = title
        val d = details
        b.tvMeta.text = listOfNotNull(
            d?.year ?: m?.year ?: s?.year,
            Fmt.rating(d?.rating ?: m?.rating ?: s?.rating),
            d?.durationSecs?.takeIf { it > 0 }?.let { Fmt.duration(it / 60) },
            (d?.genre ?: s?.genre ?: m?.category ?: s?.category)?.takeIf { it.isNotBlank() },
        ).joinToString("  •  ")
        b.tvPlot.text = d?.plot ?: m?.plot ?: s?.plot
        b.tvPlot.alpha = 1f
        b.tvPlot.isVisible = !b.tvPlot.text.isNullOrBlank()
        b.tvCredits.text = listOfNotNull(
            d?.cast?.let { getString(R.string.cast_label, it) },
            d?.director?.let { getString(R.string.director_label, it) },
        ).joinToString("\n")
        b.tvCredits.isVisible = !b.tvCredits.text.isNullOrBlank()

        val cover = com.iptv.player4k.util.CoverArt.cover(this, title, m == null)
        val posterUrl = d?.poster ?: poster
        if (posterUrl.isNullOrBlank()) b.ivPoster.setImageDrawable(cover)
        else b.ivPoster.load(posterUrl) { crossfade(true); error(cover) }
        val age = d?.age ?: m?.age ?: s?.age
            ?: if (com.iptv.player4k.security.AgeRating.isAdult(m?.category ?: s?.category)) 18 else null
        com.iptv.player4k.util.AgeBadge.bind(b.tvAge, age)
        b.ivBackdrop.load(backdrop ?: d?.poster ?: poster) { crossfade(true) }
        if (Build.VERSION.SDK_INT >= 31) {
            b.ivBackdrop.setRenderEffect(
                if (backdrop == null) RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP) else null
            )
        }
        bindMyList()
        bindResume()
    }

    /** Sem sinopse na lista nem no servidor: avisa em vez de deixar o espaço vazio. */
    private fun showNoPlotHint() {
        if (b.tvPlot.text.isNullOrBlank()) {
            b.tvPlot.setText(R.string.no_plot)
            b.tvPlot.alpha = 0.6f
            b.tvPlot.isVisible = true
        }
    }

    private fun bindMyList() {
        val inList = myKey in store.myList(pid)
        b.btnMyList.setText(if (inList) R.string.mylist_added else R.string.mylist_add)
        b.btnMyList.setIconResource(if (inList) R.drawable.ic_check else R.drawable.ic_add)
    }

    private fun bindResume() {
        val prog = store.progressMap(pid)
        val m = movie
        if (m != null) {
            val p = prog[m.url]?.takeIf { it.inProgress }
            b.btnPlay.text = if (p != null) getString(R.string.resume_from, Fmt.clock(p.position)) else getString(R.string.watch)
            b.pbResume.isVisible = p != null
            p?.let { b.pbResume.progress = (it.fraction * 1000).toInt() }
            return
        }
        val last = lastWatched()
        val ep = last?.let { l -> episodes.firstOrNull { it.url == l.url } }
        b.btnPlay.text = if (ep != null) getString(R.string.resume_episode, ep.season, ep.number) else getString(R.string.watch)
        b.pbResume.isVisible = ep != null && last.inProgress
        if (ep != null) b.pbResume.progress = (last.fraction * 1000).toInt()
        b.btnPlay.isEnabled = episodes.isNotEmpty()
    }

    private fun lastWatched() = store.progressMap(pid).values
        .filter { it.kind == ContentKind.SERIES && it.refId == series?.id }
        .maxByOrNull { it.updatedAt }

    private fun loadDetails() {
        val p = Session.playlist ?: return
        val needsFetch = (movie?.xtreamId ?: series?.xtreamId) != null
        if (!needsFetch) return
        b.loading.isVisible = true
        lifecycleScope.launch {
            val d = runCatching {
                movie?.let { repo.movieDetails(p, it) } ?: series?.let { repo.seriesDetails(p, it) }
            }.getOrNull()
            b.loading.isVisible = false
            if (d != null) {
                details = d
                bindBasic()
                if (series != null && d.episodes.isNotEmpty()) setEpisodes(d.episodes)
            }
            if (series != null && episodes.isEmpty()) b.tvNoEpisodes.isVisible = true
            showNoPlotHint()
        }
    }

    private fun setEpisodes(list: List<Episode>) {
        episodes = list
        val seasons = list.map { it.season }.distinct().sorted()
        b.chipSeasons.removeAllViews()
        // abre na temporada do último episódio assistido
        val lastSeason = lastWatched()?.let { l -> list.firstOrNull { it.url == l.url }?.season }
        val initial = lastSeason ?: seasons.firstOrNull() ?: 1
        seasons.forEach { s ->
            val chip = Chip(this).apply {
                id = 1000 + s
                text = getString(R.string.season_n, s)
                isCheckable = true
                isChecked = s == initial
                setOnClickListener { showSeason(s) }
            }
            b.chipSeasons.addView(chip)
        }
        b.tvNoEpisodes.isVisible = list.isEmpty()
        showSeason(initial)
        bindResume()
    }

    private fun showSeason(s: Int) {
        season = s
        val list = episodes.filter { it.season == s }
        epAdapter.submit(list, store.progressMap(pid))
    }

    private fun playMain() {
        movie?.let { VodRows.playMovie(this, it); return }
        val s = series ?: return
        if (episodes.isEmpty()) return
        val last = lastWatched()
        var ep = last?.let { l -> episodes.firstOrNull { it.url == l.url } }
        // episódio terminado: vai para o próximo
        if (ep != null && last != null && !last.inProgress && last.fraction >= 0.95f) {
            ep = episodes.getOrNull(episodes.indexOf(ep) + 1) ?: ep
        }
        val target = ep ?: episodes.first()
        val seasonList = episodes.filter { it.season == target.season }
        VodRows.playEpisodes(this, s, seasonList, seasonList.indexOf(target))
    }

    private fun playEpisode(i: Int) {
        val s = series ?: return
        val list = epAdapter.items
        if (i !in list.indices) return
        VodRows.playEpisodes(this, s, list, i)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
    }
}
