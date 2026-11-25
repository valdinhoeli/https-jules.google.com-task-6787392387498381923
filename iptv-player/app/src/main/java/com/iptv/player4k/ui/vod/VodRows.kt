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
import com.iptv.player4k.security.ParentalRules
import com.iptv.player4k.ui.GroupItem
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
    val age: Int? = null,
) {
    val key get() = movie?.let { "m:${it.id}" } ?: "s:${series!!.id}"
}

sealed class HomeRow {
    /** [badges]: rótulo de cada destaque ("DICA DO DIA", "NOVIDADE"…) */
    class Hero(val items: List<PosterItem>, val newest: Boolean, val badges: List<String> = emptyList()) : HomeRow()
    class Row(val title: String, val items: List<PosterItem>) : HomeRow()
}

object VodRows {
    const val GROUP_HOME = "\u0000home"
    const val GROUP_MYLIST = "\u0000mylist"

    fun poster(m: VodItem, progress: Float? = null) = PosterItem(
        m.name, m.poster, m.backdrop, m.rating, m.year, m.category, m.plot, m.added, progress, movie = m,
        age = m.age ?: if (com.iptv.player4k.security.AgeRating.isAdult(m.category)) 18 else null,
    )

    fun poster(s: SeriesItem, progress: Float? = null) = PosterItem(
        s.name, s.poster, s.backdrop, s.rating, s.year, s.category, s.plot, s.added, progress, series = s,
        age = s.age ?: if (com.iptv.player4k.security.AgeRating.isAdult(s.category)) 18 else null,
    )

    /** Todos os itens visíveis (controle dos pais aplicado). */
    fun visible(lib: Library, kind: ContentKind, rules: ParentalRules): List<PosterItem> =
        if (kind == ContentKind.MOVIE) lib.movies.filter { !rules.isBlocked(it.age, it.category) }.map { poster(it) }
        else lib.series.filter { !rules.isBlocked(it.age, it.category) }.map { poster(it) }

    /** Grupos para navegação: Início, Minha lista e cada categoria com contagem. */
    fun groups(ctx: Context, lib: Library, kind: ContentKind, rules: ParentalRules, myList: Set<String>): List<GroupItem> {
        val items = visible(lib, kind, rules)
        val counts = items.groupingBy { it.category }.eachCount()
        val cats = if (kind == ContentKind.MOVIE) lib.movieCategories else lib.seriesCategories
        val out = ArrayList<GroupItem>()
        out += GroupItem(GROUP_HOME, ctx.getString(R.string.group_home), items.size)
        out += GroupItem(GROUP_MYLIST, ctx.getString(R.string.row_mylist), items.count { it.key in myList })
        cats.forEach { c -> counts[c]?.let { out += GroupItem(c, c, it) } }
        return out
    }

    fun groupItems(lib: Library, kind: ContentKind, group: String, rules: ParentalRules, myList: Set<String>): List<PosterItem> {
        val items = visible(lib, kind, rules)
        return when (group) {
            GROUP_MYLIST -> items.filter { it.key in myList }
            else -> items.filter { it.category == group }
        }
    }

    fun build(
        ctx: Context, lib: Library, kind: ContentKind,
        progress: Map<String, Progress>, myList: Set<String>,
        rules: ParentalRules = ParentalRules.OPEN,
    ): List<HomeRow> {
        val all = visible(lib, kind, rules)
        if (all.isEmpty()) return emptyList()
        val byKey = all.associateBy { it.key }
        val prefix = if (kind == ContentKind.MOVIE) "m:" else "s:"
        val watched = progress.values.filter { it.kind == kind }.sortedByDescending { it.updatedAt }
        val cont = watched.filter { it.inProgress }.distinctBy { it.refId }
            .mapNotNull { p -> byKey[prefix + p.refId]?.let { it0 -> copyWithProgress(it0, p.fraction) } }
        val mine = all.filter { it.key in myList }
        val byCat = all.groupBy { it.category }
        val cats = if (kind == ContentKind.MOVIE) lib.movieCategories else lib.seriesCategories
        val categories = cats.mapNotNull { c -> byCat[c]?.let { c to it } }

        val rows = ArrayList<HomeRow>()
        val recent = if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(40) else emptyList()
        val top = all.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating }.take(40)
        val seen = watched.mapNotNull { it.refId }.map { prefix + it }.toSet()

        // ---- Dicas ----
        val tipOfDay = tipOfDay(top.ifEmpty { all }.filter { it.key !in seen && (it.poster != null || it.backdrop != null) })
        val recommended = recommend(all, watched.mapNotNull { byKey[prefix + it.refId] }, mine, seen)
        val lastWatched = watched.firstNotNullOfOrNull { byKey[prefix + it.refId] }
        val because = lastWatched?.let { lw ->
            byCat[lw.category].orEmpty().filter { it.key != lw.key && it.key !in seen }
                .sortedByDescending { it.rating ?: 0f }.take(20)
        }.orEmpty()

        val heroItems = ArrayList<PosterItem>()
        val heroBadges = ArrayList<String>()
        tipOfDay?.let { heroItems += it; heroBadges += ctx.getString(R.string.tip_of_day) }
        val pool = (recent.ifEmpty { top }.ifEmpty { all.shuffled() })
            .filter { (it.poster != null || it.backdrop != null) && it.key != tipOfDay?.key }.take(7)
        pool.forEach {
            heroItems += it
            heroBadges += ctx.getString(if (recent.isNotEmpty()) R.string.hero_new else R.string.hero_featured)
        }
        if (heroItems.isEmpty()) all.take(8).forEach { heroItems += it; heroBadges += ctx.getString(R.string.hero_featured) }
        rows += HomeRow.Hero(heroItems, newest = recent.isNotEmpty(), badges = heroBadges)

        if (cont.isNotEmpty()) rows += HomeRow.Row(ctx.getString(R.string.row_continue), cont)
        if (recommended.size >= 4) rows += HomeRow.Row(ctx.getString(R.string.row_recommended), recommended)
        if (mine.isNotEmpty()) rows += HomeRow.Row(ctx.getString(R.string.row_mylist), mine)
        if (because.size >= 3 && lastWatched != null) {
            rows += HomeRow.Row(ctx.getString(R.string.row_because, lastWatched.title), because)
        }
        if (recent.size >= 5) rows += HomeRow.Row(ctx.getString(R.string.row_recent), recent)
        if (top.size >= 5) rows += HomeRow.Row(ctx.getString(R.string.row_top), top)
        categories.forEach { (c, items) -> rows += HomeRow.Row(c, items) }
        return rows
    }

    private fun copyWithProgress(p: PosterItem, f: Float) = PosterItem(
        p.title, p.poster, p.backdrop, p.rating, p.year, p.category, p.plot, p.added, f, p.movie, p.series, p.age,
    )

    /** Um item por dia (muda à meia-noite), entre os bem avaliados ainda não vistos. */
    fun tipOfDay(pool: List<PosterItem>, day: Long = System.currentTimeMillis() / 86_400_000L): PosterItem? {
        if (pool.isEmpty()) return null
        val sorted = pool.sortedBy { it.key }
        return sorted[(day % sorted.size).toInt()]
    }

    /**
     * Recomendações: pontua as categorias pelo que a pessoa assistiu (peso 2) e salvou (peso 1)
     * e sugere os itens mais bem avaliados dessas categorias que ainda não foram vistos.
     */
    fun recommend(
        all: List<PosterItem>, watched: List<PosterItem>, mine: List<PosterItem>, seen: Set<String>,
    ): List<PosterItem> {
        val score = HashMap<String, Int>()
        watched.forEach { score[it.category] = (score[it.category] ?: 0) + 2 }
        mine.forEach { score[it.category] = (score[it.category] ?: 0) + 1 }
        if (score.isEmpty()) return emptyList()
        val topCats = score.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet()
        val mineKeys = mine.map { it.key }.toSet()
        return all.filter { it.category in topCats && it.key !in seen && it.key !in mineKeys }
            .sortedWith(compareByDescending<PosterItem> { score[it.category] ?: 0 }
                .thenByDescending { it.rating ?: 0f }.thenByDescending { it.added })
            .take(24)
    }

    fun search(lib: Library, kind: ContentKind, query: String, rules: ParentalRules = ParentalRules.OPEN): List<PosterItem> {
        val q = Text.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return visible(lib, kind, rules).filter { Text.normalize(it.title).contains(q) }
    }

    // ---------------- reprodução ----------------

    fun playMovie(ctx: Context, m: VodItem) {
        val ch = Channel(
            name = m.name, url = m.url, logo = m.poster, group = m.category,
            headers = m.headers, kind = ContentKind.MOVIE, refId = m.id, description = m.plot,
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
                headers = e.headers, kind = ContentKind.SERIES, refId = s.id, description = e.plot ?: s.plot,
            )
        }
        PlaybackQueue.set(Session.playlist, list)
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, list.lastIndex))
        )
    }
}
