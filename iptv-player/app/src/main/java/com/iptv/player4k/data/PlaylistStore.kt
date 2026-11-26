package com.iptv.player4k.data

import android.content.Context
import com.iptv.player4k.security.Crypto
import com.iptv.player4k.security.Pin
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
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("iptv_store", Context.MODE_PRIVATE)

    /** Listas (com servidor, usuário e senha) ficam cifradas com a chave do Keystore. */
    fun playlists(): List<Playlist> {
        val raw = prefs.getString(K_PLAYLISTS, null) ?: return emptyList()
        val json = runCatching { Crypto.decryptString(ctx, raw) }.getOrElse { return emptyList() }
        val arr = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { runCatching { Playlist.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        // migração: dados da versão anterior estavam sem criptografia
        if (!Crypto.isEncrypted(raw) && list.isNotEmpty()) write(list)
        return list
    }

    fun save(p: Playlist) {
        val list = playlists().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        write(list)
    }

    fun delete(id: String) {
        write(playlists().filterNot { it.id == id })
        prefs.edit().remove("fav_$id").remove("rec_$id").remove("prog_$id").remove("my_$id").remove("lock_$id").remove("acc_$id").apply()
        if (selectedId == id) selectedId = null
    }

    private fun write(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_PLAYLISTS, Crypto.encryptString(ctx, arr.toString())).apply()
    }

    var selectedId: String?
        get() = prefs.getString(K_SELECTED, null)
        set(v) = prefs.edit().putString(K_SELECTED, v).apply()

    fun selected(): Playlist? {
        val all = playlists()
        return all.firstOrNull { it.id == selectedId } ?: all.firstOrNull()
    }

    // ---- validade da assinatura (último valor lido do painel) ----
    fun account(id: String): AccountInfo? = getSecure("acc_$id")?.let { AccountInfo.fromJson(it) }
    fun saveAccount(id: String, info: AccountInfo) = putSecure("acc_$id", info.toJson())

    // ---- valores cifrados (contêm URLs com usuário/senha) ----
    private fun getSecure(key: String): String? {
        val raw = runCatching { prefs.getString(key, null) }.getOrNull() ?: return null
        return runCatching { Crypto.decryptString(ctx, raw) }.getOrNull()
    }

    private fun putSecure(key: String, value: String) =
        prefs.edit().putString(key, Crypto.encryptString(ctx, value)).apply()

    private fun readList(key: String): List<String> {
        // versões anteriores guardavam favoritos como StringSet sem criptografia
        val legacy = runCatching { prefs.getStringSet(key, null) }.getOrNull()
        if (legacy != null) {
            prefs.edit().remove(key).apply()
            writeList(key, legacy.toList())
            return legacy.toList()
        }
        val arr = runCatching { JSONArray(getSecure(key) ?: "[]") }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun writeList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        putSecure(key, arr.toString())
    }

    // ---- Favoritos ----
    fun favorites(playlistId: String): Set<String> = readList("fav_$playlistId").toSet()

    /** @return true se passou a ser favorito. */
    fun toggleFavorite(playlistId: String, url: String): Boolean {
        val set = favorites(playlistId).toMutableSet()
        val added = if (url in set) { set.remove(url); false } else { set.add(url); true }
        writeList("fav_$playlistId", set.toList())
        return added
    }

    // ---- Recentes ----
    fun recents(playlistId: String): List<String> = readList("rec_$playlistId")

    fun addRecent(playlistId: String, url: String) {
        val list = recents(playlistId).toMutableList()
        list.remove(url)
        list.add(0, url)
        writeList("rec_$playlistId", list.take(MAX_RECENTS))
    }

    // ---- Progresso de filmes/séries ----
    fun progressMap(playlistId: String): Map<String, Progress> {
        val o = runCatching { JSONObject(getSecure("prog_$playlistId") ?: "{}") }.getOrElse { JSONObject() }
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
        putSecure("prog_$playlistId", o.toString())
    }

    fun removeProgress(playlistId: String, url: String) {
        val o = runCatching { JSONObject(getSecure("prog_$playlistId") ?: "{}") }.getOrElse { JSONObject() }
        o.remove(url)
        putSecure("prog_$playlistId", o.toString())
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

    /** 0 = DNS da operadora; 1.. = provedores de DNS seguro (ver Http.providers). */
    var dnsMode: Int
        get() = prefs.getInt("set_dns", 0)
        set(v) = prefs.edit().putInt("set_dns", v).apply()

    /** Impede capturas/gravação de tela do app (FLAG_SECURE). */
    var secureScreen: Boolean
        get() = prefs.getBoolean("set_secure_screen", false)
        set(v) = prefs.edit().putBoolean("set_secure_screen", v).apply()

    // ---- Controle dos pais ----
    val hasPin: Boolean get() = !prefs.getString("pin", null).isNullOrEmpty()

    fun checkPin(pin: String) = Pin.verify(pin, prefs.getString("pin", null))

    fun setPin(pin: String?) {
        if (pin == null) prefs.edit().remove("pin").apply()
        else prefs.edit().putString("pin", Pin.hash(pin)).apply()
    }

    /** Idade máxima permitida (0 = Livre … 18 = sem limite). */
    var maxAge: Int
        get() = prefs.getInt("par_max_age", 18)
        set(v) = prefs.edit().putInt("par_max_age", v).apply()

    var autoLockAdult: Boolean
        get() = prefs.getBoolean("par_adult", true)
        set(v) = prefs.edit().putBoolean("par_adult", v).apply()

    /** Pede o PIN para adicionar/editar/excluir listas (protege servidor, usuário e senha). */
    var protectLists: Boolean
        get() = prefs.getBoolean("par_protect_lists", true)
        set(v) = prefs.edit().putBoolean("par_protect_lists", v).apply()

    fun lockedGroups(playlistId: String): Set<String> =
        prefs.getStringSet("lock_$playlistId", emptySet())?.toSet() ?: emptySet()

    fun setLockedGroups(playlistId: String, groups: Set<String>) =
        prefs.edit().putStringSet("lock_$playlistId", groups).apply()

    // ---- Multi-tela ----
    fun multiViewSlots(): List<String> =
        (getSecure("mv_slots") ?: "").split('\n').map { it.trim() }.let { l -> List(4) { l.getOrElse(it) { "" } } }

    fun setMultiViewSlots(urls: List<String>) =
        putSecure("mv_slots", urls.joinToString("\n"))

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
