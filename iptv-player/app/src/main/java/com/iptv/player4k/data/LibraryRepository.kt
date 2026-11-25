package com.iptv.player4k.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Filmes e séries: API Xtream (com cache local) ou a própria lista M3U. */
class LibraryRepository(private val context: Context) {
    private val details = ConcurrentHashMap<String, MediaDetails>()

    suspend fun load(p: Playlist, channels: List<Channel>, force: Boolean): Library = withContext(Dispatchers.IO) {
        if (p.type == PlaylistType.XTREAM) {
            try {
                loadXtream(p, force)
            } catch (e: Exception) {
                val fallback = LibraryBuilder.fromChannels(channels)
                if (fallback.isEmpty) throw e
                fallback
            }
        } else {
            LibraryBuilder.fromChannels(channels)
        }
    }

    private fun dir(p: Playlist) = File(context.filesDir, "library/${p.id}")

    private fun loadXtream(p: Playlist, force: Boolean): Library {
        val dir = dir(p).apply { mkdirs() }
        val api = XtreamApi(p)
        var lastError: Exception? = null
        fun file(action: String): File? {
            val f = File(dir, "$action.json")
            val stale = System.currentTimeMillis() - f.lastModified() > TTL
            if (force || !f.exists() || f.length() == 0L || stale) {
                try {
                    api.download(action, f)
                } catch (e: Exception) {
                    lastError = e
                    if (!f.exists()) return null
                }
            }
            return f
        }

        val vodCats = file("get_vod_categories")?.reader()?.use(XtreamApi::parseCategories) ?: emptyMap()
        val movies = file("get_vod_streams")?.reader()?.use { XtreamApi.parseVodStreams(it, vodCats, api) } ?: emptyList()
        val seriesCats = file("get_series_categories")?.reader()?.use(XtreamApi::parseCategories) ?: emptyMap()
        val series = file("get_series")?.reader()?.use { XtreamApi.parseSeries(it, seriesCats) } ?: emptyList()

        if (movies.isEmpty() && series.isEmpty()) lastError?.let { throw it }
        if (movies.isEmpty() && series.isEmpty() && lastError != null) throw IOException("API Xtream indisponível")

        fun order(cats: Map<String, String>, used: Set<String>) =
            (cats.values.filter { it in used } + used.filter { it !in cats.values }).distinct()

        return Library(
            movies = movies,
            movieCategories = order(vodCats, movies.mapTo(LinkedHashSet()) { it.category }),
            series = series,
            seriesCategories = order(seriesCats, series.mapTo(LinkedHashSet()) { it.category }),
        )
    }

    /** Sinopse/elenco do filme (Xtream). */
    suspend fun movieDetails(p: Playlist, m: VodItem): MediaDetails? = withContext(Dispatchers.IO) {
        val id = m.xtreamId ?: return@withContext null
        if (p.type != PlaylistType.XTREAM) return@withContext null
        details["m$id"] ?: XtreamApi.parseVodInfo(XtreamApi(p).fetchString("get_vod_info", "&vod_id=$id"))
            .also { details["m$id"] = it }
    }

    /** Temporadas/episódios e detalhes da série. */
    suspend fun seriesDetails(p: Playlist, s: SeriesItem): MediaDetails? = withContext(Dispatchers.IO) {
        val id = s.xtreamId ?: return@withContext null
        if (p.type != PlaylistType.XTREAM) return@withContext null
        details["s$id"] ?: XtreamApi(p).let { api ->
            XtreamApi.parseSeriesInfo(api.fetchString("get_series_info", "&series_id=$id"), api)
        }.also { details["s$id"] = it }
    }

    fun deleteCache(p: Playlist) {
        dir(p).deleteRecursively()
    }

    companion object {
        private const val TTL = 24 * 60 * 60 * 1000L
    }
}
