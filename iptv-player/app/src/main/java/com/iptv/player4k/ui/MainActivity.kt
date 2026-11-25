package com.iptv.player4k.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iptv.player4k.BuildConfig
import com.iptv.player4k.R
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.databinding.ActivityMainBinding
import com.iptv.player4k.databinding.DialogPlaylistBinding
import com.iptv.player4k.security.ParentalSession
import com.iptv.player4k.ui.epg.EpgFragment
import com.iptv.player4k.ui.multiview.MultiViewActivity
import com.iptv.player4k.ui.settings.SettingsActivity
import com.iptv.player4k.ui.vod.VodFragment
import com.iptv.player4k.util.DeviceInfo
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private var searchItem: MenuItem? = null

    // Estado do diálogo de lista (seleção de arquivo)
    private var dialogBinding: DialogPlaylistBinding? = null
    private var pickedUri: Uri? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            dialogBinding?.let { db ->
                val name = displayName(uri)
                db.tvFileName.text = name
                if (db.etName.text.isNullOrBlank()) db.etName.setText(name.substringBeforeLast('.'))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashStart = android.os.SystemClock.elapsedRealtime()
        // a tela de abertura espera a lista salva ficar pronta (no máximo 2 s)
        installSplashScreen().setKeepOnScreenCondition {
            !vm.ready && android.os.SystemClock.elapsedRealtime() - splashStart < 2_000
        }
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(b.toolbar)
        // voltar na tela principal: pergunta antes de sair
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchItem?.isActionViewExpanded == true) { searchItem?.collapseActionView(); return }
                // dentro de uma seção: volta para a tela inicial
                if (vm.section != SECTION_HOME) { showSection(SECTION_HOME); return }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.exit_title)
                    .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
        // marca: logo + "IPTV Player" com "4K" em destaque
        b.toolbar.setLogo(R.drawable.ic_brand)
        supportActionBar?.title = android.text.SpannableStringBuilder("IPTV Player  4K").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), length - 2, length, 0)
        }
        setupTabs(savedInstanceState?.getInt(KEY_TAB) ?: vm.currentTab)
        setupHome()
        showSection(savedInstanceState?.getInt(KEY_SECTION) ?: vm.section)

        b.empty.btnEmptyAction.setOnClickListener {
            if (vm.state.value.playlist == null) showPlaylistDialog(null) else vm.refresh()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch { kotlinx.coroutines.flow.combine(vm.state, vm.library, vm.contentVersion) { s, l, _ -> s to l }.collect { (s, l) -> renderHome(s, l) } }
                launch {
                    vm.messages.collect { msg -> Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
        vm.start()
    }

    override fun onResume() {
        super.onResume()
        SecureScreen.apply(this)
        // recentes/favoritos/progresso e controle dos pais podem ter mudado
        vm.parentalChanged()
        invalidateOptionsMenu()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, vm.currentTab)
        outState.putInt(KEY_SECTION, vm.section)
    }

    // ---------------- Abas ----------------

    private fun setupTabs(initial: Int) {
        val tabs = listOf(
            R.string.tab_live to R.drawable.ic_tv,
            R.string.tab_movies to R.drawable.ic_movie,
            R.string.tab_series to R.drawable.ic_series,
            R.string.tab_guide to R.drawable.ic_guide,
        )
        tabs.forEach { (t, i) -> b.tabs.addTab(b.tabs.newTab().setText(t).setIcon(i), false) }
        // A barra superior (AppBarLayout) bloqueia o foco das setas em aparelhos com tela de
        // toque — várias TV Box declaram ter uma. Libera para o controle remoto chegar nas abas.
        b.appBar.touchscreenBlocksFocus = false
        if (android.os.Build.VERSION.SDK_INT >= 26) b.appBar.isKeyboardNavigationCluster = false
        b.tabs.isFocusable = false
        // controle remoto: a aba abre ao receber o foco (sem precisar apertar OK)
        for (i in 0 until b.tabs.tabCount) {
            val tab = b.tabs.getTabAt(i) ?: continue
            tab.view.isFocusable = true
            tab.view.setOnFocusChangeListener { _, has -> if (has && !tab.isSelected) tab.select() }
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        b.tabs.getTabAt(initial.coerceIn(0, 3))?.select()
    }

    private fun showTab(pos: Int) {
        vm.currentTab = pos
        if (vm.section == SECTION_LIVE) b.tabs.post { hideVodTabs() }
        // no Guia em paisagem/TV, esconde o título para a grade ganhar espaço
        b.toolbar.isVisible = !(pos == 3 && resources.getBoolean(R.bool.wide_layout))
        val fm = supportFragmentManager
        val tag = "tab$pos"
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments.filter { it.tag?.startsWith("tab") == true && it.tag != tag }.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.container, createTab(pos), tag)
        tx.commitAllowingStateLoss()
    }

    /** ▲ no topo de qualquer lista leva para a aba selecionada (controle remoto). */
    // ---------------- Tela inicial: TV AO VIVO / FILMES / SÉRIES ----------------

    private val clockFmt = java.text.SimpleDateFormat("EEE, d 'de' MMM  ·  HH:mm", java.util.Locale("pt", "BR"))
    private val clockDay = java.text.SimpleDateFormat("EEE, d 'de' MMM", java.util.Locale("pt", "BR"))
    private val clockTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
    private val clockTick = object : Runnable {
        override fun run() {
            val now = java.util.Date()
            b.home.tvHomeClock.text = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                clockDay.format(now) + "\n" + clockTime.format(now) else clockFmt.format(now)
            b.root.postDelayed(this, 30_000)
        }
    }

    private fun setupHome() {
        val h = b.home
        h.tvHomeBrand.text = android.text.SpannableStringBuilder("IPTV Player  4K").apply {
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), length - 2, length, 0)
        }
        listOf(h.cardLive to SECTION_LIVE, h.cardMovies to SECTION_MOVIES, h.cardSeries to SECTION_SERIES).forEach { (card, sec) ->
            card.setOnClickListener {
                if (vm.state.value.playlist == null) showPlaylistDialog(null) else showSection(sec)
            }
            card.setOnFocusChangeListener { v, has ->
                val sc = if (has) 1.06f else 1f
                v.animate().scaleX(sc).scaleY(sc).translationZ(if (has) 16f else 0f).setDuration(150).start()
            }
        }
        h.btnHomeLists.setOnClickListener { guarded { showPlaylistPicker() } }
        h.btnHomeRefresh.setOnClickListener { vm.refresh() }
        h.btnHomeSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.root.post(clockTick)
    }

    private fun renderHome(s: UiState, lib: LibraryState) {
        val h = b.home
        h.tvHomeList.text = s.playlist?.name ?: ""
        if (s.playlist == null) {
            listOf(h.tvCountLive, h.tvCountMovies, h.tvCountSeries).forEach { it.setText(R.string.home_add_list) }
            return
        }
        h.tvCountLive.text = if (s.loading && s.totalCount == 0) getString(R.string.home_loading)
        else resources.getQuantityString(R.plurals.channel_count, s.totalCount, s.totalCount)
        val rules = vm.rules()
        val movies = lib.library.movies.count { !rules.isBlocked(it.age, it.category) }
        val series = lib.library.series.count { !rules.isBlocked(it.age, it.category) }
        val busy = lib.loading && lib.library.isEmpty
        h.tvCountMovies.text = if (busy) getString(R.string.home_loading) else resources.getQuantityString(R.plurals.home_movies_count, movies, movies)
        h.tvCountSeries.text = if (busy) getString(R.string.home_loading) else resources.getQuantityString(R.plurals.home_series_count, series, series)
    }

    /** Na seção Ao vivo só ficam as abas "Ao vivo" e "Guia". */
    private fun hideVodTabs() {
        b.tabs.getTabAt(1)?.view?.isVisible = false
        b.tabs.getTabAt(2)?.view?.isVisible = false
    }

    /** Mostra a tela inicial ou uma seção (Ao vivo + Guia, Filmes, Séries). */
    private fun showSection(sec: Int) {
        val previous = vm.section
        vm.section = sec
        val home = sec == SECTION_HOME
        b.home.homeRoot.isVisible = home
        b.appBar.isVisible = !home
        b.contentFrame.isVisible = !home
        if (home) {
            val card = when (previous) {
                SECTION_MOVIES -> b.home.cardMovies
                SECTION_SERIES -> b.home.cardSeries
                else -> b.home.cardLive
            }
            card.post { card.requestFocus() }
            return
        }
        supportActionBar?.title = getString(
            when (sec) { SECTION_MOVIES -> R.string.home_movies; SECTION_SERIES -> R.string.home_series; else -> R.string.home_live }
        )
        // Ao vivo: abas "Ao vivo" e "Guia"; Filmes/Séries: sem abas
        val live = sec == SECTION_LIVE
        b.tabs.isVisible = live
        val target = when (sec) {
            SECTION_MOVIES -> 1
            SECTION_SERIES -> 2
            else -> if (vm.currentTab == 3) 3 else 0
        }
        if (b.tabs.selectedTabPosition != target) b.tabs.getTabAt(target)?.select() else showTab(target)
        hideVodTabs()
        b.tabs.post { hideVodTabs() }
        invalidateOptionsMenu()
        b.container.post { b.container.requestFocus() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            val focused = currentFocus
            if (focused != null && b.tabs.isVisible && isInside(focused, b.container)) {
                val next = focused.focusSearch(android.view.View.FOCUS_UP)
                if (next == null || !isInside(next, b.container) || next === focused) {
                    b.tabs.getTabAt(b.tabs.selectedTabPosition)?.view?.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(v: android.view.View, parent: android.view.ViewGroup): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun createTab(pos: Int): Fragment = when (pos) {
        1 -> VodFragment.newInstance(ContentKind.MOVIE)
        2 -> VodFragment.newInstance(ContentKind.SERIES)
        3 -> EpgFragment()
        else -> LiveFragment()
    }

    private fun render(s: UiState) {
        supportActionBar?.subtitle = s.playlist?.name
        b.progress.isVisible = s.loading
        b.tabs.isVisible = s.playlist != null

        val e = b.empty
        when {
            s.playlist == null -> {
                e.emptyView.isVisible = true
                e.tvEmptyTitle.setText(R.string.empty_title)
                e.tvEmptyText.setText(R.string.empty_text)
                e.btnEmptyAction.isVisible = true
                e.btnEmptyAction.setText(R.string.action_add)
                b.container.isVisible = false
            }
            s.loading && s.allCount == 0 -> {
                e.emptyView.isVisible = true
                e.tvEmptyTitle.setText(R.string.loading_playlist)
                e.tvEmptyText.text = s.playlist.name
                e.btnEmptyAction.isVisible = false
                b.container.isVisible = false
            }
            s.error != null && s.allCount == 0 -> {
                e.emptyView.isVisible = true
                e.tvEmptyTitle.setText(R.string.empty_no_channels)
                e.tvEmptyText.text = s.error
                e.btnEmptyAction.isVisible = true
                e.btnEmptyAction.setText(R.string.retry)
                b.container.isVisible = false
            }
            else -> {
                e.emptyView.isVisible = false
                b.container.isVisible = true
            }
        }
    }

    // ---------------- Menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        searchItem = menu.findItem(R.id.action_search)
        (searchItem?.actionView as? SearchView)?.apply {
            queryHint = getString(R.string.action_search)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    clearFocus(); return true
                }
                override fun onQueryTextChange(newText: String?): Boolean {
                    vm.setQuery(newText.orEmpty()); return true
                }
            })
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val has = vm.state.value.playlist != null
        listOf(R.id.action_search, R.id.action_refresh, R.id.action_edit, R.id.action_delete, R.id.action_multiview)
            .forEach { menu.findItem(it)?.isVisible = has }
        menu.findItem(R.id.action_lock)?.apply {
            isVisible = vm.store.hasPin && has
            val unlocked = ParentalSession.isUnlocked
            setTitle(if (unlocked) R.string.action_relock else R.string.action_unlock)
            setIcon(if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /** Ações de lista exigem PIN quando "Proteger listas com PIN" está ligado. */
    private fun guarded(action: () -> Unit) {
        if (vm.store.hasPin && vm.store.protectLists) PinDialog.require(this) { action() } else action()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val current = vm.state.value.playlist
        when (item.itemId) {
            R.id.action_playlists -> guarded { showPlaylistPicker() }
            R.id.action_add -> guarded { showPlaylistDialog(null) }
            R.id.action_refresh -> vm.refresh()
            R.id.action_edit -> current?.let { guarded { showPlaylistDialog(it) } }
            R.id.action_delete -> current?.let { guarded { confirmDelete(it) } }
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_multiview -> startActivity(Intent(this, MultiViewActivity::class.java))
            R.id.action_lock -> if (ParentalSession.isUnlocked) {
                ParentalSession.lock()
                toast(R.string.relocked)
                vm.parentalChanged()
                invalidateOptionsMenu()
            } else {
                PinDialog.require(this, R.string.pin_unlock_msg) {
                    ParentalSession.unlock()
                    toast(R.string.unlocked)
                    vm.parentalChanged()
                    invalidateOptionsMenu()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showPlaylistPicker() {
        val lists = vm.store.playlists()
        val labels = (lists.map { it.name } + getString(R.string.add_new_playlist)).toTypedArray()
        val checked = lists.indexOfFirst { it.id == vm.state.value.playlist?.id }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_playlists)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                if (which == lists.size) showPlaylistDialog(null) else {
                    searchItem?.collapseActionView()
                    vm.selectPlaylist(lists[which])
                    invalidateOptionsMenu()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(p: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.confirm_delete, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                vm.deletePlaylist(p)
                invalidateOptionsMenu()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- Adicionar / editar lista ----------------

    private fun showPlaylistDialog(existing: Playlist?) {
        val db = DialogPlaylistBinding.inflate(layoutInflater)
        dialogBinding = db
        pickedUri = existing?.sourceUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)

        fun currentType(): PlaylistType = when (db.toggleType.checkedButtonId) {
            R.id.btnTypeXtream -> PlaylistType.XTREAM
            R.id.btnTypeFile -> PlaylistType.FILE
            else -> PlaylistType.URL
        }
        fun showSection(t: PlaylistType) {
            db.tilUrl.isVisible = t == PlaylistType.URL
            db.groupXtream.isVisible = t == PlaylistType.XTREAM
            db.groupFile.isVisible = t == PlaylistType.FILE
        }
        db.toggleType.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) showSection(currentType())
        }

        if (existing != null) {
            db.etName.setText(existing.name)
            db.etUa.setText(existing.userAgent)
            db.etEpg.setText(existing.epgUrl)
            when (existing.type) {
                PlaylistType.URL -> { db.toggleType.check(R.id.btnTypeUrl); db.etUrl.setText(existing.url) }
                PlaylistType.XTREAM -> {
                    db.toggleType.check(R.id.btnTypeXtream)
                    db.etServer.setText(existing.server)
                    db.etUser.setText(existing.username)
                    db.etPass.setText(existing.password)
                    db.rgOutput.check(if (existing.output == "m3u8") R.id.rbHls else R.id.rbTs)
                }
                PlaylistType.FILE -> {
                    db.toggleType.check(R.id.btnTypeFile)
                    pickedUri?.let { db.tvFileName.text = displayName(it) }
                }
            }
        }
        showSection(currentType())

        db.btnPickFile.setOnClickListener {
            try {
                pickFile.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                toast(R.string.err_no_picker)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.dlg_add_title else R.string.dlg_edit_title)
            .setView(db.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { dialogBinding = null }
            .create()

        // servidor, usuário e senha: a janela não aparece em prints nem gravações
        dialog.window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = buildPlaylist(db, currentType(), existing) ?: return@setOnClickListener
                dialog.dismiss()
                searchItem?.collapseActionView()
                vm.savePlaylist(p)
                invalidateOptionsMenu()
            }
        }
        dialog.show()
    }

    private fun buildPlaylist(db: DialogPlaylistBinding, type: PlaylistType, existing: Playlist?): Playlist? {
        listOf(db.tilName, db.tilUrl, db.tilServer, db.tilUser, db.tilPass).forEach { it.error = null }
        var name = db.etName.text?.toString()?.trim().orEmpty()
        val ua = db.etUa.text?.toString()?.trim().orEmpty()
        val epgUrl = db.etEpg.text?.toString()?.trim().orEmpty()
        val id = existing?.id ?: UUID.randomUUID().toString()

        val p = when (type) {
            PlaylistType.URL -> {
                val url = db.etUrl.text?.toString()?.trim().orEmpty()
                val uri = Uri.parse(url)
                if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    db.tilUrl.error = getString(R.string.err_url); return null
                }
                if (name.isEmpty()) name = uri.host ?: "Lista"
                Playlist(id, name, type, url = url, userAgent = ua)
            }
            PlaylistType.XTREAM -> {
                val server = db.etServer.text?.toString()?.trim().orEmpty()
                val user = db.etUser.text?.toString()?.trim().orEmpty()
                val pass = db.etPass.text?.toString()?.trim().orEmpty()
                var ok = true
                if (server.isEmpty()) { db.tilServer.error = getString(R.string.err_required); ok = false }
                if (user.isEmpty()) { db.tilUser.error = getString(R.string.err_required); ok = false }
                if (pass.isEmpty()) { db.tilPass.error = getString(R.string.err_required); ok = false }
                if (!ok) return null
                if (name.isEmpty()) name = Uri.parse(Playlist.normalizeServer(server)).host ?: "Xtream"
                val output = if (db.rgOutput.checkedRadioButtonId == R.id.rbHls) "m3u8" else "ts"
                Playlist(id, name, type, server = server, username = user, password = pass, output = output, userAgent = ua)
            }
            PlaylistType.FILE -> {
                val uri = pickedUri
                if (uri == null) { toast(R.string.err_file); return null }
                if (name.isEmpty()) name = displayName(uri).substringBeforeLast('.')
                Playlist(id, name, type, sourceUri = uri.toString(), userAgent = ua)
            }
        }
        return p.copy(updatedAt = existing?.updatedAt ?: 0L, epgUrl = epgUrl)
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_TAB = "tab"
        private const val KEY_SECTION = "section"
        const val SECTION_HOME = 0
        const val SECTION_LIVE = 1
        const val SECTION_MOVIES = 2
        const val SECTION_SERIES = 3
    }
}
