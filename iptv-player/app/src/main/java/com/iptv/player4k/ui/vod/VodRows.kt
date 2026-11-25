package com.iptv.player4k.ui.vod

import android.content.Context
import android.content.Intent
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Episode
import com.iptv.player4k.data.Library
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.Progress
import com.iptv.player4k.data.SeriesItem
import com.iptv.player4k.data.Session
import com.iptv.player4k.data.VodItem
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.util.Text

/** Um cartão de pôster (filme ou série). */
class PosterItem(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Float?,
    val year: String?,
    val category: String,
    val plot: String?,
    val added: Long,
    val progress: Float? = null,
    val movie: VodItem? = null,
    val series: SeriesItem? = null,
) {
    val key get() = movie?.let { "m:${it.id}" } ?: "s:${series!!.id}"
}

sealed class HomeRow {
    class Hero(val items: List<PosterItem>, val newest: Boolean) : HomeRow()
    class Row(val title: String, val items: List<PosterItem>) : HomeRow()
}

object VodRows {
    fun poster(m: VodItem, progress: Float? = null) = PosterItem(
        m.name, m.poster, m.backdrop, m.rating, m.year, m.category, m.plot, m.added, progress, movie = m,
    )

    fun poster(s: SeriesItem, progress: Float? = null) = PosterItem(
        s.name, s.poster, s.backdrop, s.rating, s.year, s.category, s.plot, s.added, progress, series = s,
    )

    fun build(
        ctx: Context, lib: Library, kind: ContentKind,
        progress: Map<String, Progress>, myList: Set<String>,
    ): List<HomeRow> {
        val all: List<PosterItem>
        val categories: List<Pair<String, List<PosterItem>>>
        val cont: List<PosterItem>
        val mine: List<PosterItem>
        if (kind == ContentKind.MOVIE) {
            all = lib.movies.map { poster(it) }
            cont = progress.values.filter { it.kind == ContentKind.MOVIE && it.inProgress }
                .sortedByDescending { it.updatedAt }
                .mapNotNull { p -> lib.movie(p.refId)?.let { poster(it, p.fraction) } }
            mine = lib.movies.filter { "m:${it.id}" in myList }.map { poster(it) }
            val byCat = all.groupBy { it.category }
            categories = lib.movieCategories.mapNotNull { c -> byCat[c]?.let { c to it } }
        } else {
            all = lib.series.map { poster(it) }
            cont = progress.values.filter { it.kind == ContentKind.SERIES && it.inProgress }
                .sortedByDescending { it.updatedAt }
                .distinctBy { it.refId }
                .mapNotNull { p -> lib.series(p.refId)?.let { poster(it, p.fraction) } }
            mine = lib.series.filter { "s:${it.id}" in myList }.map { poster(it) }
            val byCat = all.groupBy { it.category }
            categories = lib.seriesCategories.mapNotNull { c -> byCat[c]?.let { c to it } }
        }
        if (all.isEmpty()) return emptyList()

        val rows = ArrayList<HomeRow>()
        val recent = if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(40) else emptyList()
        val top = all.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating }.take(40)

        val heroPool = (recent.ifEmpty { top }.ifEmpty { all.shuffled() })
            .filter { it.poster != null || it.backdrop != null }.take(8)
            .ifEmpty { all.take(8) }
        rows += HomeRow.Hero(heroPool, newest = recent.isNotEmpty())

        if (cont.isNotEmpty()) rows += HomeRow.Row(ctx.getString(R.string.row_continue), cont)
        if (mine.isNotEmpty()) rows += HomeRow.Row(ctx.getString(R.string.row_mylist), mine)
        if (recent.size >= 5) rows += HomeRow.Row(ctx.getString(R.string.row_recent), recent)
        if (top.size >= 5) rows += HomeRow.Row(ctx.getString(R.string.row_top), top)
        categories.forEach { (c, items) -> rows += HomeRow.Row(c, items) }
        return rows
    }

    fun search(lib: Library, kind: ContentKind, query: String): List<PosterItem> {
        val q = Text.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return if (kind == ContentKind.MOVIE) lib.movies.filter { it.searchKey.contains(q) }.map { poster(it) }
        else lib.series.filter { it.searchKey.contains(q) }.map { poster(it) }
    }

    // ---------------- reprodução ----------------

    fun playMovie(ctx: Context, m: VodItem) {
        val ch = Channel(
            name = m.name, url = m.url, logo = m.poster, group = m.category,
            headers = m.headers, kind = ContentKind.MOVIE, refId = m.id,
        )
        PlaybackQueue.set(Session.playlist, listOf(ch))
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, 0))
    }

    fun playEpisodes(ctx: Context, s: SeriesItem, episodes: List<Episode>, index: Int) {
        if (episodes.isEmpty()) return
        val list = episodes.map { e ->
            Channel(
                name = ctx.getString(R.string.ep_label, e.season, e.number, e.title).let { "${s.name} · $it" },
                url = e.url, logo = e.image ?: s.poster, group = s.name,
                headers = e.headers, kind = ContentKind.SERIES, refId = s.id,
            )
        }
        PlaybackQueue.set(Session.playlist, list)
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, list.lastIndex))
        )
    }
}
