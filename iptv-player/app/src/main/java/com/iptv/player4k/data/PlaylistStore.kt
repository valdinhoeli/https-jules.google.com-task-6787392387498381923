package com.iptv.player4k.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Posição salva de um filme/episódio. */
class Progress(
    val url: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val kind: ContentKind,
    val refId: String?,
    val title: String,
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val inProgress: Boolean get() = position > 15_000 && fraction < 0.95f
}

/** Persistência simples (SharedPreferences) de listas, favoritos, recentes e ajustes. */
class PlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_store", Context.MODE_PRIVATE)

    fun playlists(): List<Playlist> {
        val arr = runCatching { JSONArray(prefs.getString(K_PLAYLISTS, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { Playlist.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun save(p: Playlist) {
        val list = playlists().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        write(list)
    }

    fun delete(id: String) {
        write(playlists().filterNot { it.id == id })
        prefs.edit().remove("fav_$id").remove("rec_$id").remove("prog_$id").remove("my_$id").apply()
        if (selectedId == id) selectedId = null
    }

    private fun write(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_PLAYLISTS, arr.toString()).apply()
    }

    var selectedId: String?
        get() = prefs.getString(K_SELECTED, null)
        set(v) = prefs.edit().putString(K_SELECTED, v).apply()

    fun selected(): Playlist? {
        val all = playlists()
        return all.firstOrNull { it.id == selectedId } ?: all.firstOrNull()
    }

    // ---- Favoritos ----
    fun favorites(playlistId: String): Set<String> =
        prefs.getStringSet("fav_$playlistId", emptySet())?.toSet() ?: emptySet()

    /** @return true se passou a ser favorito. */
    fun toggleFavorite(playlistId: String, url: String): Boolean {
        val set = favorites(playlistId).toMutableSet()
        val added = if (url in set) { set.remove(url); false } else { set.add(url); true }
        prefs.edit().putStringSet("fav_$playlistId", set).apply()
        return added
    }

    // ---- Recentes ----
    fun recents(playlistId: String): List<String> {
        val arr = runCatching { JSONArray(prefs.getString("rec_$playlistId", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addRecent(playlistId: String, url: String) {
        val list = recents(playlistId).toMutableList()
        list.remove(url)
        list.add(0, url)
        val arr = JSONArray()
        list.take(MAX_RECENTS).forEach { arr.put(it) }
        prefs.edit().putString("rec_$playlistId", arr.toString()).apply()
    }

    // ---- Progresso de filmes/séries ----
    fun progressMap(playlistId: String): Map<String, Progress> {
        val o = runCatching { JSONObject(prefs.getString("prog_$playlistId", "{}")!!) }.getOrElse { JSONObject() }
        val out = HashMap<String, Progress>()
        o.keys().forEach { url ->
            val e = o.optJSONObject(url) ?: return@forEach
            out[url] = Progress(
                url, e.optLong("p"), e.optLong("d"), e.optLong("t"),
                runCatching { ContentKind.valueOf(e.optString("k")) }.getOrDefault(ContentKind.MOVIE),
                e.optString("r").ifEmpty { null }, e.optString("n"),
            )
        }
        return out
    }

    fun progress(playlistId: String, url: String): Progress? = progressMap(playlistId)[url]

    fun saveProgress(playlistId: String, p: Progress) {
        val all = progressMap(playlistId).toMutableMap()
        all[p.url] = p
        val keep = all.values.sortedByDescending { it.updatedAt }.take(MAX_PROGRESS)
        val o = JSONObject()
        keep.forEach {
            o.put(it.url, JSONObject().put("p", it.position).put("d", it.duration).put("t", it.updatedAt)
                .put("k", it.kind.name).put("r", it.refId ?: "").put("n", it.title))
        }
        prefs.edit().putString("prog_$playlistId", o.toString()).apply()
    }

    fun removeProgress(playlistId: String, url: String) {
        val o = runCatching { JSONObject(prefs.getString("prog_$playlistId", "{}")!!) }.getOrElse { JSONObject() }
        o.remove(url)
        prefs.edit().putString("prog_$playlistId", o.toString()).apply()
    }

    // ---- Minha lista (filmes "m:<id>" e séries "s:<id>") ----
    fun myList(playlistId: String): Set<String> =
        prefs.getStringSet("my_$playlistId", emptySet())?.toSet() ?: emptySet()

    fun toggleMyList(playlistId: String, key: String): Boolean {
        val set = myList(playlistId).toMutableSet()
        val added = if (key in set) { set.remove(key); false } else { set.add(key); true }
        prefs.edit().putStringSet("my_$playlistId", set).apply()
        return added
    }

    // ---- Ajustes do player ----
    var forceMaxQuality: Boolean
        get() = prefs.getBoolean("set_force_max", false)
        set(v) = prefs.edit().putBoolean("set_force_max", v).apply()

    var largeBuffer: Boolean
        get() = prefs.getBoolean("set_large_buffer", false)
        set(v) = prefs.edit().putBoolean("set_large_buffer", v).apply()

    var matchDisplayMode: Boolean
        get() = prefs.getBoolean("set_match_display", false)
        set(v) = prefs.edit().putBoolean("set_match_display", v).apply()

    var pipEnabled: Boolean
        get() = prefs.getBoolean("set_pip", true)
        set(v) = prefs.edit().putBoolean("set_pip", v).apply()

    var resizeMode: Int
        get() = prefs.getInt("set_resize", 0)
        set(v) = prefs.edit().putInt("set_resize", v).apply()

    companion object {
        private const val K_PLAYLISTS = "playlists"
        private const val K_SELECTED = "selected"
        private const val MAX_RECENTS = 40
        private const val MAX_PROGRESS = 200
    }
}
