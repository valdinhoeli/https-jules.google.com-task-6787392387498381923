package com.iptv.player4k.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player4k.R
import com.iptv.player4k.data.AccountInfo
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.XtreamApi
import com.iptv.player4k.epg.SportEvent
import com.iptv.player4k.epg.SportsEvents
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Library
import com.iptv.player4k.data.LibraryRepository
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistRepository
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Session
import com.iptv.player4k.data.XtreamCreds
import com.iptv.player4k.epg.EpgData
import com.iptv.player4k.epg.EpgRepository
import com.iptv.player4k.security.ParentalRules
import com.iptv.player4k.security.ParentalSession
import com.iptv.player4k.util.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class GroupItem(val key: String, val label: String, val count: Int)

data class UiState(
    val playlist: Playlist? = null,
    val hasPlaylists: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val groups: List<GroupItem> = emptyList(),
    val selectedGroup: String = MainViewModel.GROUP_ALL,
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val query: String = "",
    /** canais ao vivo */
    val totalCount: Int = 0,
    /** itens de todos os tipos na lista M3U */
    val allCount: Int = 0,
)

data class LibraryState(
    val loading: Boolean = false,
    val library: Library = Library.EMPTY,
    val error: String? = null,
)

/** Números e novidades da tela inicial (calculados fora da thread da tela). */
data class HomeSummary(
    val movies: Int = 0,
    val series: Int = 0,
    val recent: List<com.iptv.player4k.ui.vod.PosterItem> = emptyList(),
)

/** Validade da assinatura: [xtream] false = lista M3U sem painel (validade não informada). */
data class AccountState(val info: AccountInfo? = null, val xtream: Boolean = false, val loading: Boolean = false)

data class EpgState(
    val loading: Boolean = false,
    val data: EpgData? = null,
    val error: String? = null,
    /** a lista não informa nenhuma fonte de guia */
    val noSource: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = PlaylistStore(app)
    private val repo = PlaylistRepository(app)
    val libraryRepo = LibraryRepository(app)
    private val epgRepo = EpgRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library

    private val _epg = MutableStateFlow(EpgState())
    val epg: StateFlow<EpgState> = _epg

    /** Conteúdo infantil da lista (aba KIDS), já com o controle dos pais. */
    private val _kids = MutableStateFlow(com.iptv.player4k.data.ThemeContent.EMPTY)
    val kids: StateFlow<com.iptv.player4k.data.ThemeContent> = _kids
    /** ESPORTES e NOVELAS (mesma ideia do KIDS) */
    private val _sportsTheme = MutableStateFlow(com.iptv.player4k.data.ThemeContent.EMPTY)
    val sportsTheme: StateFlow<com.iptv.player4k.data.ThemeContent> = _sportsTheme
    private val _novelas = MutableStateFlow(com.iptv.player4k.data.ThemeContent.EMPTY)
    val novelas: StateFlow<com.iptv.player4k.data.ThemeContent> = _novelas
    private var kidsJob: Job? = null

    private val _home = MutableStateFlow(HomeSummary())
    val home: StateFlow<HomeSummary> = _home
    private var homeJob: Job? = null

    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account

    /** Eventos esportivos do guia (ao vivo e próximos), para o rodapé da tela inicial. */
    private val _sports = MutableStateFlow<List<SportEvent>?>(null)
    val sports: StateFlow<List<SportEvent>?> = _sports
    private var sportsJob: Job? = null
    private var accountJob: Job? = null

    /** Incrementa quando progresso/"minha lista" mudam (outras telas recalculam). */
    private val _contentVersion = MutableStateFlow(0)
    val contentVersion: StateFlow<Int> = _contentVersion

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    var currentTab = 0
    /** "abrir no último canal" já foi feito nesta abertura do app */
    var autoStartDone = false
    /** seção aberta: 0 = tela inicial (ver MainActivity.SECTION_*) */
    var section = 0

    private var all: List<Channel> = emptyList()
    /** todos os canais ao vivo (sem filtro dos pais) */
    private var liveAll: List<Channel> = emptyList()
    /** canais ao vivo visíveis com as regras do controle dos pais */
    private var live: List<Channel> = emptyList()
    private var byUrl: Map<String, Channel> = emptyMap()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var libJob: Job? = null
    private var epgJob: Job? = null
    private var started = false
    private var loadedId: String? = null

    /** true quando já há algo para mostrar (a tela de abertura pode sair). */
    @Volatile var ready = false
        private set

    fun start() {
        if (started) return
        started = true
        val p = store.selected()
        _state.update { it.copy(playlist = p, hasPlaylists = p != null) }
        if (p != null) load(p, forceRefresh = false) else ready = true
    }

    fun selectPlaylist(p: Playlist) {
        store.selectedId = p.id
        _state.update { it.copy(selectedGroup = GROUP_ALL, query = "") }
        load(p, forceRefresh = false)
    }

    fun refresh() {
        state.value.playlist?.let { load(it, forceRefresh = true) }
    }

    fun savePlaylist(p: Playlist) {
        store.save(p)
        store.selectedId = p.id
        _state.update { it.copy(selectedGroup = GROUP_ALL, query = "") }
        loadedId = null
        load(p, forceRefresh = true)
    }

    fun deletePlaylist(p: Playlist) {
        store.delete(p.id)
        repo.deleteCache(p)
        libraryRepo.deleteCache(p)
        epgRepo.deleteCache(p)
        val next = store.selected()
        clearData()
        _state.value = UiState(playlist = next, hasPlaylists = next != null)
        if (next != null) load(next, forceRefresh = false)
    }

    private fun clearData() {
        all = emptyList(); liveAll = emptyList(); live = emptyList(); byUrl = emptyMap(); loadedId = null
        libJob?.cancel(); epgJob?.cancel()
        _library.value = LibraryState()
        _epg.value = EpgState()
        Session.library = Library.EMPTY
        Session.epg = null
        Session.creds = null
        accountJob?.cancel(); sportsJob?.cancel()
        _account.value = AccountState()
        _sports.value = null
        kidsJob?.cancel()
        _kids.value = com.iptv.player4k.data.ThemeContent.EMPTY
        _sportsTheme.value = com.iptv.player4k.data.ThemeContent.EMPTY
        _novelas.value = com.iptv.player4k.data.ThemeContent.EMPTY
        homeJob?.cancel()
        _home.value = HomeSummary()
    }

    /**
     * [quiet]: atualização em segundo plano — a lista atual continua na tela e,
     * se a internet falhar, nada muda (tenta de novo na próxima abertura).
     */
    private fun load(p: Playlist, forceRefresh: Boolean, quiet: Boolean = false) {
        loadJob?.cancel()
        if (p.id != loadedId) {
            clearData()
            _state.update { it.copy(groups = emptyList(), channels = emptyList(), totalCount = 0, allCount = 0) }
        }
        Session.playlist = p
        _state.update { it.copy(playlist = p, hasPlaylists = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                com.iptv.player4k.util.Perf.mark("lista: início")
                val result = repo.load(p, forceRefresh)
                ready = true
                com.iptv.player4k.util.Perf.mark("lista: ${result.channels.size} itens prontos")
                val saved = if (forceRefresh) p.copy(updatedAt = System.currentTimeMillis()).also { store.save(it) } else p
                all = result.channels
                liveAll = all.filter { it.kind == ContentKind.LIVE }
                applyParental()
                loadedId = p.id
                _state.update { it.copy(loading = false, error = null, totalCount = live.size, allCount = all.size) }
                rebuild()
                // painel Xtream por trás da lista (tipo Xtream, link get.php ou links dos canais)
                val creds = XtreamCreds.fromPlaylist(saved) ?: XtreamCreds.fromChannels(all)
                Session.creds = creds
                val extrasForce = forceRefresh && !quiet
                loadLibrary(saved, extrasForce, creds, repo.fingerprint(saved))
                loadEpg(saved, result.tvgUrls, extrasForce, creds)
                loadAccount(saved, creds, forceRefresh)
                // lista baixada há mais de 24 h: atualiza sozinha em segundo plano
                if (!forceRefresh && saved.type != PlaylistType.FILE &&
                    System.currentTimeMillis() - saved.updatedAt > AUTO_REFRESH_MS
                ) {
                    viewModelScope.launch { load(saved, forceRefresh = true, quiet = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ready = true
                if (quiet && all.isNotEmpty()) {
                    _state.update { it.copy(loading = false) }
                } else {
                    val msg = e.message ?: e.javaClass.simpleName
                    _state.update { it.copy(loading = false, error = msg) }
                    _messages.tryEmit(getApplication<Application>().getString(R.string.err_load, msg))
                }
            }
        }
    }

    private fun loadLibrary(p: Playlist, force: Boolean, creds: XtreamCreds?, m3uFp: String) {
        libJob?.cancel()
        if (_library.value.library.isEmpty) _library.update { it.copy(loading = true, error = null) }
        val channels = all
        libJob = viewModelScope.launch {
            try {
                // 1) o que já está salvo no aparelho: aparece na hora
                if (!force) {
                    libraryRepo.load(p, channels, false, creds, m3uFp, offline = true)?.let { cached ->
                        publishLibrary(cached)
                        com.iptv.player4k.util.Perf.mark("catálogo (cache): ${cached.movies.size} filmes, ${cached.series.size} séries")
                        if (!libraryRepo.isStale(p, creds)) return@launch
                    }
                }
                // 2) internet (primeira vez, atualização manual ou catálogo com mais de 24 h)
                val lib = libraryRepo.load(p, channels, force, creds, m3uFp) ?: return@launch
                com.iptv.player4k.util.Perf.mark("catálogo: ${lib.movies.size} filmes, ${lib.series.size} séries")
                publishLibrary(lib)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _library.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun publishLibrary(lib: Library) {
        Session.library = lib
        _library.value = LibraryState(loading = false, library = lib)
        applyParental()
    }

    /** Validade: mostra a última salva na hora e consulta o painel (no máx. a cada 6 h). */
    private fun loadAccount(p: Playlist, creds: XtreamCreds?, force: Boolean) {
        accountJob?.cancel()
        if (creds == null) { _account.value = AccountState(info = null, xtream = false); return }
        val cached = store.account(p.id)
        _account.value = AccountState(info = cached, xtream = true, loading = cached == null)
        if (!force && cached != null && System.currentTimeMillis() - cached.checkedAt < ACCOUNT_TTL_MS) return
        accountJob = viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { XtreamApi(creds, p.userAgent).accountInfo() }.getOrNull()
            }
            if (fresh != null) store.saveAccount(p.id, fresh)
            _account.value = AccountState(info = fresh ?: cached, xtream = true, loading = false)
        }
    }

    /** Recalcula os eventos esportivos (o guia mudou, ou passou o tempo e algum começou/acabou). */
    fun refreshSports() {
        val epg = _epg.value.data
        if (epg == null) {
            if (!_epg.value.loading) _sports.value = emptyList()
            return
        }
        val channels = live
        sportsJob?.cancel()
        sportsJob = viewModelScope.launch {
            _sports.value = withContext(Dispatchers.Default) { SportsEvents.find(channels, epg) }
        }
    }

    private fun loadEpg(p: Playlist, tvgUrls: List<String>, force: Boolean, creds: XtreamCreds?) {
        epgJob?.cancel()
        if (_epg.value.data == null) _epg.update { it.copy(loading = true, error = null, noSource = false) }
        val channels = liveAll
        epgJob = viewModelScope.launch {
            try {
                // guia salvo aparece na hora; só baixa se tiver mais de 12 h (ou atualização manual)
                if (!force) {
                    val cached = epgRepo.loadCached(p, tvgUrls, creds)
                    if (cached != null) {
                        Session.epg = cached
                        _epg.value = EpgState(loading = false, data = cached)
                        refreshSports()
                        com.iptv.player4k.util.Perf.mark("guia (cache)")
                        if (epgRepo.isFresh(cached)) return@launch
                    }
                }
                val data = epgRepo.load(p, tvgUrls, channels, force, creds)
                com.iptv.player4k.util.Perf.mark("guia pronto")
                Session.epg = data
                _epg.value = EpgState(loading = false, data = data, noSource = data == null)
                refreshSports()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _epg.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
                refreshSports()
            }
        }
    }

    fun selectGroup(key: String) {
        if (key == state.value.selectedGroup) return
        _state.update { it.copy(selectedGroup = key) }
        applyFilter()
    }

    fun setQuery(q: String) {
        if (q == state.value.query) return
        _state.update { it.copy(query = q) }
        applyFilter()
    }

    fun toggleFavorite(ch: Channel): Boolean {
        val p = state.value.playlist ?: return false
        val added = store.toggleFavorite(p.id, ch.url)
        rebuild()
        return added
    }

    fun toggleMyList(key: String): Boolean {
        val p = state.value.playlist ?: return false
        val added = store.toggleMyList(p.id, key)
        contentChanged()
        return added
    }

    fun contentChanged() {
        _contentVersion.value++
    }

    /** Regras atuais (desligadas sem PIN ou quando desbloqueado pelo PIN). */
    fun rules(): ParentalRules {
        if (!store.hasPin || ParentalSession.isUnlocked) return ParentalRules.OPEN
        val pid = state.value.playlist?.id.orEmpty()
        return ParentalRules(true, store.maxAge, store.autoLockAdult, store.lockedGroups(pid))
    }

    private fun applyParental() {
        val r = rules()
        live = liveAll.filter { !r.isBlocked(it.age, it.group) }
        byUrl = live.associateBy { it.url }
        Session.live = live
        Session.allGroups = LinkedHashSet<String>().apply {
            liveAll.forEach { add(it.group) }
            all.forEach { if (it.kind != ContentKind.LIVE) add(it.group) }
            addAll(_library.value.library.movieCategories); addAll(_library.value.library.seriesCategories)
        }.toList()
        _state.update { it.copy(totalCount = live.size) }
        recomputeKids()
    }

    private fun recomputeKids() {
        val channels = live
        val lib = _library.value.library
        val r = rules()
        kidsJob?.cancel()
        kidsJob = viewModelScope.launch {
            fun allowed(t: com.iptv.player4k.data.ThemeContent) = com.iptv.player4k.data.ThemeContent(
                t.channels,
                t.movies.filter { !r.isBlocked(it.age, it.category) },
                t.series.filter { !r.isBlocked(it.age, it.category) },
            )
            val (k, sp, nv) = withContext(Dispatchers.Default) {
                Triple(
                    allowed(com.iptv.player4k.data.Kids.extract(channels, lib)),
                    allowed(com.iptv.player4k.data.Sports.extract(channels, lib)),
                    allowed(com.iptv.player4k.data.Novelas.extract(channels, lib)),
                )
            }
            _kids.value = k; _sportsTheme.value = sp; _novelas.value = nv
        }
        recomputeHome()
    }

    /** Contagens e "adicionados recentemente" (ordenar o catálogo inteiro é pesado: fica em segundo plano). */
    fun recomputeHome() {
        val lib = _library.value.library
        val r = rules()
        val showRecent = store.homeShowRecent
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _home.value = withContext(Dispatchers.Default) {
                HomeSummary(
                    movies = lib.movies.count { !r.isBlocked(it.age, it.category) },
                    series = lib.series.count { !r.isBlocked(it.age, it.category) },
                    recent = if (showRecent) com.iptv.player4k.ui.vod.VodRows.recentlyAdded(lib, r) else emptyList(),
                )
            }
        }
    }

    private var lastRulesKey: String? = null
    private var lastShowRecent: Boolean? = null

    /**
     * Volta para a tela (do player, detalhes, ajustes…): refaz só o que mudou.
     * Regras dos pais iguais → só recentes/favoritos e progresso; refiltrar 50 mil canais e
     * recalcular KIDS/ESPORTES/NOVELAS a cada volta deixava o app pesado.
     */
    fun onScreenResumed() {
        val pid = state.value.playlist?.id.orEmpty()
        val key = listOf(store.hasPin, ParentalSession.isUnlocked, store.maxAge, store.autoLockAdult, store.lockedGroups(pid).hashCode())
            .joinToString("|")
        if (key != lastRulesKey) {
            val first = lastRulesKey == null
            lastRulesKey = key
            if (!first) { parentalChanged(); lastShowRecent = store.homeShowRecent; return }
        }
        rebuild()
        contentChanged()
        if (lastShowRecent != null && lastShowRecent != store.homeShowRecent) recomputeHome()
        lastShowRecent = store.homeShowRecent
    }

    /** Chamado após PIN/configurações dos pais mudarem. */
    fun parentalChanged() {
        applyParental()
        refreshSports()
        rebuild()
        contentChanged()
    }

    fun addRecent(ch: Channel) {
        state.value.playlist?.let { store.addRecent(it.id, ch.url) }
    }

    /** Recalcula grupos/contagens (favoritos e recentes mudam fora da lista). */
    private var rebuildJob: Job? = null

    fun rebuild() {
            val p = state.value.playlist ?: return
            val ctx = getApplication<Application>()
            val liveNow = live
            val byUrlNow = byUrl
            rebuildJob?.cancel()
            rebuildJob = viewModelScope.launch {
                // contagem por grupo de milhares de canais: fora da thread da tela
                val (groups, favs) = withContext(Dispatchers.Default) {
                    val favs = store.favorites(p.id)
                    val recents = store.recents(p.id).count { it in byUrlNow }
                    val counts = LinkedHashMap<String, Int>()
                    liveNow.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
                    val groups = ArrayList<GroupItem>(counts.size + 3)
                    groups += GroupItem(GROUP_ALL, ctx.getString(R.string.group_all), liveNow.size)
                    groups += GroupItem(GROUP_FAV, ctx.getString(R.string.group_favorites), liveNow.count { it.url in favs })
                    groups += GroupItem(GROUP_RECENT, ctx.getString(R.string.group_recents), recents)
                    counts.forEach { (g, c) -> groups += GroupItem(g, g, c) }
                    groups to favs
                }
                val sel = state.value.selectedGroup.takeIf { s -> groups.any { it.key == s } } ?: GROUP_ALL
                _state.update { it.copy(groups = groups, favorites = favs, selectedGroup = sel) }
                applyFilter()
            }
    }

    private fun applyFilter() {
        val s = state.value
        val p = s.playlist ?: return
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val base: List<Channel> = when (s.selectedGroup) {
                    GROUP_ALL -> live
                    GROUP_FAV -> live.filter { it.url in s.favorites }
                    GROUP_RECENT -> store.recents(p.id).mapNotNull { byUrl[it] }
                    else -> live.filter { it.group == s.selectedGroup }
                }
                val q = Text.normalize(s.query.trim())
                if (q.isEmpty()) base else base.filter { it.searchKey.contains(q) }
            }
            _state.update { it.copy(channels = result) }
        }
    }

    companion object {
        private const val ACCOUNT_TTL_MS = 6 * 60 * 60 * 1000L
        private const val AUTO_REFRESH_MS = 24 * 60 * 60 * 1000L
        const val GROUP_ALL = "\u0000all"
        const val GROUP_FAV = "\u0000fav"
        const val GROUP_RECENT = "\u0000recent"
    }
}
