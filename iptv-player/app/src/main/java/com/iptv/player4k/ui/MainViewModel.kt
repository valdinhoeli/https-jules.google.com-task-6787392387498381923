package com.iptv.player4k.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Library
import com.iptv.player4k.data.LibraryRepository
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistRepository
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Session
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

    /** Incrementa quando progresso/"minha lista" mudam (outras telas recalculam). */
    private val _contentVersion = MutableStateFlow(0)
    val contentVersion: StateFlow<Int> = _contentVersion

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    var currentTab = 0

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

    fun start() {
        if (started) return
        started = true
        val p = store.selected()
        _state.update { it.copy(playlist = p, hasPlaylists = p != null) }
        if (p != null) load(p, forceRefresh = false)
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
    }

    private fun load(p: Playlist, forceRefresh: Boolean) {
        loadJob?.cancel()
        if (p.id != loadedId) {
            clearData()
            _state.update { it.copy(groups = emptyList(), channels = emptyList(), totalCount = 0, allCount = 0) }
        }
        Session.playlist = p
        _state.update { it.copy(playlist = p, hasPlaylists = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repo.load(p, forceRefresh)
                val saved = if (forceRefresh) p.copy(updatedAt = System.currentTimeMillis()).also { store.save(it) } else p
                all = result.channels
                liveAll = all.filter { it.kind == ContentKind.LIVE }
                applyParental()
                loadedId = p.id
                _state.update { it.copy(loading = false, error = null, totalCount = live.size, allCount = all.size) }
                rebuild()
                loadLibrary(saved, forceRefresh)
                loadEpg(saved, result.tvgUrls, forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, error = msg) }
                _messages.tryEmit(getApplication<Application>().getString(R.string.err_load, msg))
            }
        }
    }

    private fun loadLibrary(p: Playlist, force: Boolean) {
        libJob?.cancel()
        _library.update { it.copy(loading = true, error = null) }
        val channels = all
        libJob = viewModelScope.launch {
            try {
                val lib = libraryRepo.load(p, channels, force)
                Session.library = lib
                _library.value = LibraryState(loading = false, library = lib)
                applyParental()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _library.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun loadEpg(p: Playlist, tvgUrls: List<String>, force: Boolean) {
        epgJob?.cancel()
        _epg.update { it.copy(loading = true, error = null, noSource = false) }
        val channels = liveAll
        epgJob = viewModelScope.launch {
            try {
                val data = epgRepo.load(p, tvgUrls, channels, force)
                Session.epg = data
                _epg.value = EpgState(loading = false, data = data, noSource = data == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _epg.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
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
        Session.allGroups = (liveAll.map { it.group } + all.filter { it.kind != ContentKind.LIVE }.map { it.group } +
            _library.value.library.movieCategories + _library.value.library.seriesCategories).distinct()
        _state.update { it.copy(totalCount = live.size) }
    }

    /** Chamado após PIN/configurações dos pais mudarem. */
    fun parentalChanged() {
        applyParental()
        rebuild()
        contentChanged()
    }

    fun addRecent(ch: Channel) {
        state.value.playlist?.let { store.addRecent(it.id, ch.url) }
    }

    /** Recalcula grupos/contagens (favoritos e recentes mudam fora da lista). */
    fun rebuild() {
        val p = state.value.playlist ?: return
        val ctx = getApplication<Application>()
        val favs = store.favorites(p.id)
        val recents = store.recents(p.id).count { it in byUrl }
        val counts = LinkedHashMap<String, Int>()
        live.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val groups = ArrayList<GroupItem>(counts.size + 3)
        groups += GroupItem(GROUP_ALL, ctx.getString(R.string.group_all), live.size)
        groups += GroupItem(GROUP_FAV, ctx.getString(R.string.group_favorites), live.count { it.url in favs })
        groups += GroupItem(GROUP_RECENT, ctx.getString(R.string.group_recents), recents)
        counts.forEach { (g, c) -> groups += GroupItem(g, g, c) }
        val sel = state.value.selectedGroup.takeIf { s -> groups.any { it.key == s } } ?: GROUP_ALL
        _state.update { it.copy(groups = groups, favorites = favs, selectedGroup = sel) }
        applyFilter()
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
        const val GROUP_ALL = "\u0000all"
        const val GROUP_FAV = "\u0000fav"
        const val GROUP_RECENT = "\u0000recent"
    }
}
