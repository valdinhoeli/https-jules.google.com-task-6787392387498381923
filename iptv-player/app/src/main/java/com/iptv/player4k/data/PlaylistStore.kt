package com.iptv.player4k.data

import android.content.Context
import org.json.JSONArray

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
        prefs.edit().remove("fav_$id").remove("rec_$id").apply()
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
    }
}
