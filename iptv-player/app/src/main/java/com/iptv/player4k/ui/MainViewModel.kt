package com.iptv.player4k.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistRepository
import com.iptv.player4k.data.PlaylistStore
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
    val totalCount: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = PlaylistStore(app)
    private val repo = PlaylistRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var all: List<Channel> = emptyList()
    private var byUrl: Map<String, Channel> = emptyMap()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
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
        load(p, forceRefresh = true)
    }

    fun deletePlaylist(p: Playlist) {
        store.delete(p.id)
        repo.deleteCache(p)
        val next = store.selected()
        all = emptyList(); byUrl = emptyMap(); loadedId = null
        _state.value = UiState(playlist = next, hasPlaylists = next != null)
        if (next != null) load(next, forceRefresh = false)
    }

    private fun load(p: Playlist, forceRefresh: Boolean) {
        loadJob?.cancel()
        if (p.id != loadedId) {
            // outra lista: não mostrar canais da lista anterior
            all = emptyList(); byUrl = emptyMap(); loadedId = null
            _state.update { it.copy(groups = emptyList(), channels = emptyList(), totalCount = 0) }
        }
        _state.update { it.copy(playlist = p, hasPlaylists = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val list = repo.load(p, forceRefresh)
                if (forceRefresh) store.save(p.copy(updatedAt = System.currentTimeMillis()))
                all = list
                byUrl = list.associateBy { it.url }
                loadedId = p.id
                _state.update { it.copy(loading = false, error = null, totalCount = list.size) }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, error = msg) }
                _messages.tryEmit(getApplication<Application>().getString(R.string.err_load, msg))
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
        all.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val groups = ArrayList<GroupItem>(counts.size + 3)
        groups += GroupItem(GROUP_ALL, ctx.getString(R.string.group_all), all.size)
        groups += GroupItem(GROUP_FAV, ctx.getString(R.string.group_favorites), all.count { it.url in favs })
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
                    GROUP_ALL -> all
                    GROUP_FAV -> all.filter { it.url in s.favorites }
                    GROUP_RECENT -> store.recents(p.id).mapNotNull { byUrl[it] }
                    else -> all.filter { it.group == s.selectedGroup }
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
