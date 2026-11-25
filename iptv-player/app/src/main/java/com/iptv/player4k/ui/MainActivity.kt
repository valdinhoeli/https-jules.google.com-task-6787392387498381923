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
import com.iptv.player4k.ui.epg.EpgFragment
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
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(b.toolbar)
        setupTabs(savedInstanceState?.getInt(KEY_TAB) ?: vm.currentTab)

        b.empty.btnEmptyAction.setOnClickListener {
            if (vm.state.value.playlist == null) showPlaylistDialog(null) else vm.refresh()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch {
                    vm.messages.collect { msg -> Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
        vm.start()
    }

    override fun onResume() {
        super.onResume()
        // Recentes/favoritos/progresso podem ter mudado no player
        vm.rebuild()
        vm.contentChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, vm.currentTab)
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
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        b.tabs.getTabAt(initial.coerceIn(0, 3))?.select()
    }

    private fun showTab(pos: Int) {
        vm.currentTab = pos
        // no Guia em paisagem/TV, recolhe o título para a grade ganhar espaço
        b.appBar.setExpanded(!(pos == 3 && resources.getBoolean(R.bool.wide_layout)), true)
        val fm = supportFragmentManager
        val tag = "tab$pos"
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments.filter { it.tag?.startsWith("tab") == true && it.tag != tag }.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.container, createTab(pos), tag)
        tx.commitAllowingStateLoss()
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
        listOf(R.id.action_search, R.id.action_refresh, R.id.action_edit, R.id.action_delete)
            .forEach { menu.findItem(it)?.isVisible = has }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val current = vm.state.value.playlist
        when (item.itemId) {
            R.id.action_playlists -> showPlaylistPicker()
            R.id.action_add -> showPlaylistDialog(null)
            R.id.action_refresh -> vm.refresh()
            R.id.action_edit -> current?.let { showPlaylistDialog(it) }
            R.id.action_delete -> current?.let { confirmDelete(it) }
            R.id.action_settings -> showSettings()
            R.id.action_device -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_title)
                .setMessage(DeviceInfo.describe(this))
                .setPositiveButton(R.string.ok, null)
                .show()
            R.id.action_about -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.ok, null)
                .show()
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

    private fun showSettings() {
        val st = vm.store
        val labels = arrayOf(
            getString(R.string.set_force_max),
            getString(R.string.set_large_buffer),
            getString(R.string.set_match_display),
            getString(R.string.set_pip),
        )
        val values = booleanArrayOf(st.forceMaxQuality, st.largeBuffer, st.matchDisplayMode, st.pipEnabled)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setMultiChoiceItems(labels, values) { _, which, checked -> values[which] = checked }
            .setPositiveButton(R.string.save) { _, _ ->
                st.forceMaxQuality = values[0]
                st.largeBuffer = values[1]
                st.matchDisplayMode = values[2]
                st.pipEnabled = values[3]
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
    }
}
