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

    // ---- Perfis ----
    /** Todos os perfis; o principal sempre existe e vem primeiro. */
    fun profiles(): List<Profile> {
        val saved = Profile.listFromJson(getSecure(K_PROFILES))
        val main = saved.firstOrNull { it.id == Profile.MAIN_ID }
            ?: Profile(Profile.MAIN_ID, "Principal", Profile.COLORS[0], false)
        return listOf(main) + saved.filter { it.id != Profile.MAIN_ID }
    }

    fun saveProfile(p: Profile) {
        val list = profiles().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        putSecure(K_PROFILES, Profile.listToJson(list))
    }

    /** Apaga o perfil e os dados dele (favoritos, recentes, progresso, minha lista). */
    fun deleteProfile(id: String) {
        if (id == Profile.MAIN_ID) return
        putSecure(K_PROFILES, Profile.listToJson(profiles().filter { it.id != id }))
        val e = prefs.edit()
        prefs.all.keys.filter { it.endsWith("~$id") }.forEach { e.remove(it) }
        if (currentProfileId == id) e.putString(K_PROFILE, Profile.MAIN_ID)
        e.apply()
    }

    var currentProfileId: String
        get() = prefs.getString(K_PROFILE, Profile.MAIN_ID).orEmpty()
            .takeIf { id -> id == Profile.MAIN_ID || profiles().any { it.id == id } } ?: Profile.MAIN_ID
        set(v) = prefs.edit().putString(K_PROFILE, v).apply()

    val currentProfile: Profile
        get() = currentProfileId.let { id -> profiles().firstOrNull { it.id == id } ?: profiles().first() }

    /** Dados por perfil: o principal mantém as chaves antigas; os outros ganham "~id". */
    private fun scoped(key: String): String =
        prefs.getString(K_PROFILE, Profile.MAIN_ID).orEmpty().let { if (it.isEmpty()) key else "$key~$it" }

    // ---- Favoritos ----
    fun favorites(playlistId: String): Set<String> = readList(scoped("fav_$playlistId")).toSet()

    /** @return true se passou a ser favorito. */
    fun toggleFavorite(playlistId: String, url: String): Boolean {
        val set = favorites(playlistId).toMutableSet()
        val added = if (url in set) { set.remove(url); false } else { set.add(url); true }
        writeList(scoped("fav_$playlistId"), set.toList())
        return added
    }

    // ---- Recentes ----
    fun recents(playlistId: String): List<String> = readList(scoped("rec_$playlistId"))

    fun addRecent(playlistId: String, url: String) {
        val list = recents(playlistId).toMutableList()
        list.remove(url)
        list.add(0, url)
        writeList(scoped("rec_$playlistId"), list.take(MAX_RECENTS))
    }

    // ---- Progresso de filmes/séries ----
    fun progressMap(playlistId: String): Map<String, Progress> {
        val o = runCatching { JSONObject(getSecure(scoped("prog_$playlistId")) ?: "{}") }.getOrElse { JSONObject() }
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
        putSecure(scoped("prog_$playlistId"), o.toString())
    }

    fun removeProgress(playlistId: String, url: String) {
        val o = runCatching { JSONObject(getSecure(scoped("prog_$playlistId")) ?: "{}") }.getOrElse { JSONObject() }
        o.remove(url)
        putSecure(scoped("prog_$playlistId"), o.toString())
    }

    // ---- Minha lista (filmes "m:<id>" e séries "s:<id>") ----
    fun myList(playlistId: String): Set<String> =
        prefs.getStringSet(scoped("my_$playlistId"), emptySet())?.toSet() ?: emptySet()

    fun toggleMyList(playlistId: String, key: String): Boolean {
        val set = myList(playlistId).toMutableSet()
        val added = if (key in set) { set.remove(key); false } else { set.add(key); true }
        prefs.edit().putStringSet(scoped("my_$playlistId"), set).apply()
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

    /** Tela inicial: rodapé "Eventos esportivos" (ao vivo e próximos). */
    var homeShowSports: Boolean
        get() = prefs.getBoolean("set_home_sports", true)
        set(v) = prefs.edit().putBoolean("set_home_sports", v).apply()

    /** Tela inicial: fileira "Adicionados recentemente" (filmes e séries). */
    var homeShowRecent: Boolean
        get() = prefs.getBoolean("set_home_recent", true)
        set(v) = prefs.edit().putBoolean("set_home_recent", v).apply()

    /** Ao abrir o app, começa a tocar o último canal ao vivo assistido. */
    var startLastChannel: Boolean
        get() = prefs.getBoolean("set_start_last", false)
        set(v) = prefs.edit().putBoolean("set_start_last", v).apply()

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
    // O hash do PIN (com sal) fica cifrado com a chave do Keystore: copiar o arquivo do app
    // para outro aparelho não permite testar PINs fora dele. "pin" = formato antigo (só hash).
    val hasPin: Boolean get() = prefs.contains(K_PIN_SECURE) || !prefs.getString("pin", null).isNullOrEmpty()

    /**
     * Confere o PIN. Depois de [PIN_FREE_TRIES] erros seguidos, bloqueia por 30 s, dobrando a cada
     * novo erro (até 15 min) — inclusive se o app for fechado e aberto de novo.
     */
    fun checkPin(pin: String): Boolean {
        val stored = if (prefs.contains(K_PIN_SECURE)) getSecure(K_PIN_SECURE) else prefs.getString("pin", null)
        val ok = verifyWithLockout(pin, stored, "")
        // migra o formato antigo para o cifrado
        if (ok && !prefs.contains(K_PIN_SECURE)) setPin(pin)
        return ok
    }

    /** Tempo que falta do bloqueio por erros (0 = pode tentar). Resiste a reinício e a mudar o relógio. */
    fun pinLockRemainingMs(): Long = lockRemaining("")

    /** Confere [pin] contra [stored]; erros seguidos bloqueiam (contadores separados por [tag]). */
    private fun verifyWithLockout(pin: String, stored: String?, tag: String): Boolean {
        if (lockRemaining(tag) > 0) return false
        val ok = Pin.verify(pin, stored)
        val fk = K_PIN_FAILS + tag; val lk = K_PIN_LOCK + tag; val wk = K_PIN_LOCK_WALL + tag
        if (ok) {
            prefs.edit().remove(fk).remove(lk).remove(wk).apply()
        } else {
            val fails = prefs.getInt(fk, 0) + 1
            val e = prefs.edit().putInt(fk, fails)
            if (fails >= PIN_FREE_TRIES) {
                val wait = (30_000L shl (fails - PIN_FREE_TRIES).coerceAtMost(5)).coerceAtMost(15 * 60_000L)
                e.putLong(lk, android.os.SystemClock.elapsedRealtime() + wait)
                e.putLong(wk, System.currentTimeMillis() + wait)
            }
            e.apply()
        }
        return ok
    }

    private fun lockRemaining(tag: String): Long {
        val lockMono = prefs.getLong(K_PIN_LOCK + tag, 0L)
        if (lockMono == 0L) return 0L
        val max = 15 * 60_000L
        val el = android.os.SystemClock.elapsedRealtime()
        // mesmo boot (o relógio monotônico só cresce): ignora mudanças no relógio do aparelho
        if (el >= lockMono - max) return (lockMono - el).coerceIn(0L, max)
        // o aparelho reiniciou: usa o relógio de parede
        return (prefs.getLong(K_PIN_LOCK_WALL + tag, 0L) - System.currentTimeMillis()).coerceIn(0L, max)
    }

    // ---- Senha de cada perfil ("Bloquear perfil") ----
    private fun profilePinKey(id: String) = keyFor("ppin", id)

    fun profileLocked(id: String): Boolean = prefs.contains(profilePinKey(id))

    fun setProfilePin(id: String, pin: String?) {
        val tag = "~p~$id"
        val e = prefs.edit().remove(K_PIN_FAILS + tag).remove(K_PIN_LOCK + tag).remove(K_PIN_LOCK_WALL + tag)
        if (pin == null) e.remove(profilePinKey(id)).apply()
        else { e.apply(); putSecure(profilePinKey(id), Pin.hash(pin)) }
    }

    fun checkProfilePin(id: String, pin: String): Boolean =
        verifyWithLockout(pin, getSecure(profilePinKey(id)), "~p~$id")

    fun profilePinLockRemainingMs(id: String): Long = lockRemaining("~p~$id")

    /** Chave de um ajuste de um perfil: o principal usa a chave antiga, os outros ganham "~id". */
    private fun keyFor(base: String, profileId: String) = if (profileId.isEmpty()) base else "$base~$profileId"

    fun setPin(pin: String?) {
        val e = prefs.edit().remove("pin").remove(K_PIN_FAILS).remove(K_PIN_LOCK).remove(K_PIN_LOCK_WALL)
        if (pin == null) e.remove(K_PIN_SECURE).apply()
        else { e.apply(); putSecure(K_PIN_SECURE, Pin.hash(pin)) }
    }

    /** Idade máxima permitida (0 = Livre … 18 = sem limite). */
    var maxAge: Int
        get() = maxAgeOf(currentProfileId)
        set(v) = setMaxAgeOf(currentProfileId, v)

    var autoLockAdult: Boolean
        get() = adultLockOf(currentProfileId)
        set(v) = setAdultLockOf(currentProfileId, v)

    // Controle dos pais de cada perfil (classificação, adulto e grupos)
    fun maxAgeOf(profileId: String): Int = prefs.getInt(keyFor("par_max_age", profileId), 18)
    fun setMaxAgeOf(profileId: String, v: Int) = prefs.edit().putInt(keyFor("par_max_age", profileId), v).apply()
    fun adultLockOf(profileId: String): Boolean = prefs.getBoolean(keyFor("par_adult", profileId), true)
    fun setAdultLockOf(profileId: String, v: Boolean) = prefs.edit().putBoolean(keyFor("par_adult", profileId), v).apply()
    fun lockedGroupsOf(profileId: String, playlistId: String): Set<String> =
        prefs.getStringSet(keyFor("lock_$playlistId", profileId), emptySet())?.toSet() ?: emptySet()
    fun setLockedGroupsOf(profileId: String, playlistId: String, groups: Set<String>) =
        prefs.edit().putStringSet(keyFor("lock_$playlistId", profileId), groups).apply()

    /** Pede o PIN para adicionar/editar/excluir listas (protege servidor, usuário e senha). */
    var protectLists: Boolean
        get() = prefs.getBoolean("par_protect_lists", true)
        set(v) = prefs.edit().putBoolean("par_protect_lists", v).apply()

    fun lockedGroups(playlistId: String): Set<String> = lockedGroupsOf(currentProfileId, playlistId)

    fun setLockedGroups(playlistId: String, groups: Set<String>) = setLockedGroupsOf(currentProfileId, playlistId, groups)

    // ---- Multi-tela ----
    fun multiViewSlots(): List<String> =
        (getSecure("mv_slots") ?: "").split('\n').map { it.trim() }.let { l -> List(4) { l.getOrElse(it) { "" } } }

    fun setMultiViewSlots(urls: List<String>) =
        putSecure("mv_slots", urls.joinToString("\n"))

    var resizeMode: Int
        get() = prefs.getInt("set_resize", 0)
        set(v) = prefs.edit().putInt("set_resize", v).apply()

    companion object {
        private const val K_PIN_SECURE = "pin_s"
        private const val K_PIN_FAILS = "pin_fails"
        private const val K_PIN_LOCK = "pin_lock"
        private const val K_PIN_LOCK_WALL = "pin_lock_wall"
        const val PIN_FREE_TRIES = 5
        private const val K_PLAYLISTS = "playlists"
        private const val K_PROFILES = "profiles"
        private const val K_PROFILE = "profile_cur"
        private const val K_SELECTED = "selected"
        private const val MAX_RECENTS = 40
        private const val MAX_PROGRESS = 200
    }
}
