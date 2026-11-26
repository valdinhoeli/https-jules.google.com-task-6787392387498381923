package com.iptv.player4k.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Filmes e séries: API Xtream (com cache local) ou a própria lista M3U. */
class LibraryRepository(private val context: Context) {

    /**
     * Com painel Xtream (lista Xtream, link get.php ou links de canais no padrão Xtream) usa a API:
     * catálogo completo com capas, sinopse, elenco, temporadas e episódios.
     * Sem painel (ou se a API falhar), monta filmes e séries a partir da própria lista M3U.
     */
    /**
     * [offline]: usa só o que já está no aparelho (abre na hora, mesmo com cache antigo);
     * devolve null se não houver nada salvo. A atualização pela internet é feita depois, em segundo plano.
     * [m3uFingerprint]: versão da lista M3U (para reaproveitar o retrato do catálogo).
     */
    suspend fun load(
        p: Playlist, channels: List<Channel>, force: Boolean, creds: XtreamCreds?,
        m3uFingerprint: String = "", offline: Boolean = false,
    ): Library? = withContext(Dispatchers.IO) {
        synchronized(lockFor(p.id)) {
            val fp = fingerprint(p, creds, m3uFingerprint)
            if (!force && fp != null) {
                memo[p.id]?.takeIf { it.first == fp }?.let { return@withContext it.second }
                Snapshot.readLibrary(context, p.id, fp)?.let { memo[p.id] = fp to it; return@withContext it }
            }
            if (offline && creds != null && fp == null) return@withContext null

            val fromM3u = { LibraryBuilder.fromChannels(channels) }
            val lib = if (creds == null) fromM3u() else try {
                val l = loadXtream(p, creds, force, network = !offline)
                if (l.isEmpty) fromM3u() else l
            } catch (e: Exception) {
                if (offline) return@withContext null
                val fallback = fromM3u()
                if (fallback.isEmpty && p.type == PlaylistType.XTREAM) throw e
                fallback
            }
            val newFp = fingerprint(p, creds, m3uFingerprint)
            if (newFp != null) {
                memo[p.id] = newFp to lib
                runCatching { Snapshot.writeLibrary(context, p.id, newFp, lib) }
            }
            lib
        }
    }

    private val actions = listOf("get_vod_categories", "get_vod_streams", "get_series_categories", "get_series")

    /** Versão dos dados de origem: arquivos JSON do painel ou a lista M3U. null = ainda não baixado. */
    private fun fingerprint(p: Playlist, creds: XtreamCreds?, m3uFp: String): String? {
        if (creds == null) return "m3u:$m3uFp"
        val files = actions.map { File(dir(p), "$it.json") }
        if (files.none { it.exists() }) return null
        return "xt:${creds.hashCode()}:" + files.joinToString(",") { "${it.length()}:${it.lastModified()}" }
    }

    /** Catálogo do painel com mais de 24 h (vale atualizar em segundo plano). */
    fun isStale(p: Playlist, creds: XtreamCreds?): Boolean {
        if (creds == null) return false
        val f = File(dir(p), "get_vod_streams.json")
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > TTL
    }

    private fun dir(p: Playlist) = File(context.filesDir, "library/${p.id}")

    private fun loadXtream(p: Playlist, creds: XtreamCreds, force: Boolean, network: Boolean = true): Library {
        val dir = dir(p).apply { mkdirs() }
        val api = XtreamApi(creds, p.userAgent)
        var lastError: Exception? = null
        fun file(action: String): File? {
            val f = File(dir, "$action.json")
            val stale = System.currentTimeMillis() - f.lastModified() > TTL
            if (network && (force || !f.exists() || f.length() == 0L || stale)) {
                try {
                    // catálogo do painel gravado cifrado (chave do Keystore)
                    api.download(action, f) { com.iptv.player4k.security.Crypto.encryptingStream(context, it) }
                } catch (e: Exception) {
                    lastError = e
                    if (!f.exists()) return null
                }
            }
            return f
        }

        // lê cifrado (arquivos antigos, sem cifra, também abrem)
        fun File.secureReader() = java.io.InputStreamReader(
            com.iptv.player4k.security.Crypto.decryptingStream(context, inputStream().buffered()), Charsets.UTF_8,
        )
        val vodCats = file("get_vod_categories")?.secureReader()?.use(XtreamApi::parseCategories) ?: emptyMap()
        val movies = file("get_vod_streams")?.secureReader()?.use { XtreamApi.parseVodStreams(it, vodCats, api) } ?: emptyList()
        val seriesCats = file("get_series_categories")?.secureReader()?.use(XtreamApi::parseCategories) ?: emptyMap()
        val series = file("get_series")?.secureReader()?.use { XtreamApi.parseSeries(it, seriesCats) } ?: emptyList()

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

    /** Sinopse/elenco do filme (API Xtream). */
    suspend fun movieDetails(p: Playlist, m: VodItem): MediaDetails? = withContext(Dispatchers.IO) {
        val id = m.xtreamId ?: return@withContext null
        val creds = Session.creds ?: return@withContext null
        details["m$id"] ?: XtreamApi.parseVodInfo(XtreamApi(creds, p.userAgent).fetchString("get_vod_info", "&vod_id=$id"))
            .also { details["m$id"] = it }
    }

    /** Temporadas/episódios e detalhes da série (API Xtream). */
    suspend fun seriesDetails(p: Playlist, s: SeriesItem): MediaDetails? = withContext(Dispatchers.IO) {
        val id = s.xtreamId ?: return@withContext null
        val creds = Session.creds ?: return@withContext null
        details["s$id"] ?: XtreamApi(creds, p.userAgent).let { api ->
            XtreamApi.parseSeriesInfo(api.fetchString("get_series_info", "&series_id=$id"), api)
        }.also { details["s$id"] = it }
    }

    /** Detalhes já carregados (para mostrar a sinopse no destaque sem nova consulta). */
    fun cached(key: String): MediaDetails? = details[key]

    fun deleteCache(p: Playlist) {
        dir(p).deleteRecursively()
        memo.remove(p.id)
    }

    companion object {
        private const val TTL = 24 * 60 * 60 * 1000L
        /** compartilhado entre telas: evita buscar a mesma sinopse duas vezes */
        private val details = ConcurrentHashMap<String, MediaDetails>()
        private val memo = ConcurrentHashMap<String, Pair<String, Library>>()
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(id: String) = locks.getOrPut(id) { Any() }
    }
}
