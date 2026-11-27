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

        // TV: barra do topo e conteúdo das seções dentro da área segura
        TvSafeArea.pad(b.appBar, left = 40, top = 27, right = 56)
        TvSafeArea.pad(b.contentFrame, left = 40, right = 40, bottom = 27)

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
        // marca: logo + "Eli Player" com "4K" em destaque
        b.toolbar.setLogo(R.drawable.ic_brand)
        supportActionBar?.title = android.text.SpannableStringBuilder("Eli Player  4K").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), length - 2, length, 0)
        }
        // tela recriada pelo sistema: não reabre o último canal de novo
        if (savedInstanceState != null) vm.autoStartDone = true
        // vários perfis: pergunta quem está assistindo ao abrir o app
        // perfil com senha: pergunta sempre ao abrir (também se o sistema recriou o app)
        val cur = vm.store.currentProfileId
        val lockedNow = vm.store.profileLocked(cur) && com.iptv.player4k.data.Session.unlockedProfileId != cur
        if (lockedNow || (!com.iptv.player4k.data.Session.profileAsked && savedInstanceState == null && vm.store.profiles().size > 1)) {
            com.iptv.player4k.data.Session.profileAsked = true
            waitingProfile = true
        }
        savedInstanceState0 = savedInstanceState?.getInt(KEY_SECTION)
        setupTabs(savedInstanceState?.getInt(KEY_TAB) ?: vm.currentTab)
        setupHome()
        showSection(savedInstanceState?.getInt(KEY_SECTION) ?: vm.section)

        b.empty.btnEmptyAction.setOnClickListener {
            if (vm.state.value.playlist == null) showPlaylistDialog(null) else vm.refresh()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch { vm.state.collect { maybeStartLastChannel(it) } }
                launch {
                    kotlinx.coroutines.flow.combine(vm.state, vm.library, vm.home) { s, l, hs -> Triple(s, l, hs) }
                        .collect { (s, l, hs) -> renderHome(s, l, hs) }
                }
                launch { kotlinx.coroutines.flow.combine(vm.account, vm.state) { a, _ -> a }.collect(::renderExpiry) }
                launch { kotlinx.coroutines.flow.combine(vm.kids, vm.library) { k, l -> k to l }.collect { (k, l) -> renderKidsCount(k, l) } }
                launch {
                    kotlinx.coroutines.flow.combine(vm.sportsTheme, vm.novelas, vm.sports, vm.library) { sp, nv, ev, l -> arrayOf(sp, nv, ev, l) }
                        .collect { renderThemeCounts() }
                }
                launch { kotlinx.coroutines.flow.combine(vm.sports, vm.epg) { s, e -> s to e }.collect { (s, e) -> renderSports(s, e) } }
                launch {
                    vm.messages.collect { msg -> Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
        vm.start()
        if (waitingProfile) openProfiles(startup = true)
    }

    /**
     * Pouca memória (TV Box com 1–2 GB): descarta as seções escondidas (Filmes, Séries, KIDS…),
     * com suas capas. Elas são recriadas na hora em que o usuário voltar a elas.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        if (supportFragmentManager.isStateSaved) return
        val hidden = supportFragmentManager.fragments.filter { it.tag?.startsWith("tab") == true && it.isHidden }
        if (hidden.isEmpty()) return
        val tx = supportFragmentManager.beginTransaction()
        hidden.forEach { tx.remove(it) }
        tx.commitAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        SecureScreen.apply(this)
        // perfil pode ter mudado (tela "Quem está assistindo?")
        applyProfile()
        // recentes/favoritos/progresso e controle dos pais podem ter mudado (só refaz o necessário)
        vm.onScreenResumed()
        // chaves da tela inicial (Ajustes): rodapé de esportes e novidades
        renderSports(vm.sports.value, vm.epg.value)
        invalidateOptionsMenu()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, vm.currentTab)
        outState.putInt(KEY_SECTION, vm.section)
    }

    // ---------------- Abas ----------------

    private var settingUpTabs = false
    private var savedInstanceState0: Int? = null

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
            tab.view.setOnFocusChangeListener { _, has ->
                if (has && !tab.isSelected && vm.section == SECTION_LIVE) tab.select()
            }
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            // ao abrir na tela inicial, a aba é só marcada: a tela da seção é criada quando o usuário entrar nela
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (settingUpTabs) return
                // Ao vivo/Guia (0 e 3) nunca abrem por cima de Filmes ou Séries
                if (isLiveTab(tab.position) && vm.section != SECTION_LIVE) return
                showTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        settingUpTabs = (savedInstanceState0 ?: vm.section) == SECTION_HOME
        b.tabs.getTabAt(initial.coerceIn(0, 3))?.select()
        settingUpTabs = false
    }

    private fun isLiveTab(pos: Int) = pos == 0 || pos == 3

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
    private val dateFmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR"))
    private var clockTicks = 0
    private val clockTick = object : Runnable {
        override fun run() {
            b.home.tvHomeClock.text = clockFmt.format(java.util.Date())
            // a cada minuto: eventos que começaram/acabaram e o progresso dos que estão ao vivo
            if (clockTicks++ % 2 == 1 && vm.section == SECTION_HOME) vm.refreshSports()
            b.root.postDelayed(this, 30_000)
        }
    }
    private lateinit var recentAdapter: com.iptv.player4k.ui.vod.PosterAdapter
    private lateinit var sportsAdapter: com.iptv.player4k.ui.home.SportsAdapter

    private fun setupHome() {
        val h = b.home
        h.tvHomeBrand.text = android.text.SpannableStringBuilder("Eli Player  4K").apply {
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), length - 2, length, 0)
        }
        listOf(h.cardLive to SECTION_LIVE, h.cardMovies to SECTION_MOVIES, h.cardSeries to SECTION_SERIES, h.cardKids to SECTION_KIDS,
            h.cardSports to SECTION_SPORTS, h.cardNovelas to SECTION_NOVELAS).forEach { (card, sec) ->
            card.setOnClickListener {
                when {
                    vm.state.value.playlist != null -> showSection(sec)
                    // perfil KIDS não adiciona listas: só um adulto, no perfil dele
                    kidsProfile -> Toast.makeText(this, R.string.profile_kids_no_list, Toast.LENGTH_LONG).show()
                    else -> showPlaylistDialog(null)
                }
            }
            card.setOnFocusChangeListener { v, has ->
                // cartões ocupando a largura toda (celular em pé) crescem menos para não cortar
                val sc = if (!has) 1f else if (resources.getBoolean(R.bool.wide_layout)) 1.06f else 1.03f
                v.animate().scaleX(sc).scaleY(sc).translationZ(if (has) 16f else 0f).setDuration(150).start()
            }
        }
        arrangeHomeCards()
        // adicionados recentemente (filmes e séries); o tamanho final vem de fitHome()
        val posterW = (resources.displayMetrics.density * (if (resources.getBoolean(R.bool.wide_layout)) 72 else 76)).toInt()
        recentAdapter = com.iptv.player4k.ui.vod.PosterAdapter({ openDetails(it) }, posterW)
        h.rvHomeRecent.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)
        h.rvHomeRecent.adapter = recentAdapter
        h.rvHomeRecent.itemAnimator = null
        // tudo na tela sem rolar: as capas ocupam o espaço que sobra entre os cartões e o rodapé
        h.homeScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) h.homeScroll.post { fitHome() }
        }
        h.homeCards.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) h.homeScroll.post { fitHome() }
        }
        // rodapé: eventos esportivos
        sportsAdapter = com.iptv.player4k.ui.home.SportsAdapter(::openSportEvent)
        h.rvHomeSports.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)
        h.rvHomeSports.adapter = sportsAdapter
        h.rvHomeSports.itemAnimator = null
        // TV: textos e botões dentro da área segura (48dp nas laterais, 27dp em cima/embaixo)
        TvSafeArea.pad(h.homeHeader, left = 20, top = 13, right = 20)
        TvSafeArea.pad(h.homeScrollContent, left = 20, right = 20)
        TvSafeArea.pad(h.homeSports, left = 20, right = 20, bottom = 17)
        h.btnHomeProfile.setOnClickListener { openProfiles() }
        h.btnHomeLists.setOnClickListener { guarded { showPlaylistPicker() } }
        h.btnHomeRefresh.setOnClickListener { vm.refresh() }
        h.btnHomeSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.root.post(clockTick)
    }

    /**
     * Os 6 cartões: TV/paisagem numa linha só; celular em pé em 2 linhas de 3. Menores que antes,
     * para caber tudo (cartões, novidades e esportes ao vivo) sem rolar a tela.
     */
    private fun arrangeHomeCards() {
        val h = b.home
        val d = resources.displayMetrics.density
        val wide = resources.getBoolean(R.bool.wide_layout)
        val cards = listOf(h.cardLive, h.cardMovies, h.cardSeries, h.cardKids, h.cardSports, h.cardNovelas)
        cards.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        h.homeCards.removeAllViews()
        val perRow = if (wide) 6 else 3
        cards.chunked(perRow).forEach { rowCards ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                clipChildren = false
            }
            rowCards.forEach { card ->
                card.layoutParams = android.widget.LinearLayout.LayoutParams(0, (d * (if (wide) 96 else 82)).toInt(), 1f)
                    .apply { val m = (d * 4).toInt(); setMargins(m, m, m, m) }
                val inner = card.getChildAt(0) as? android.view.ViewGroup
                if (inner != null) for (i in 0 until inner.childCount) {
                    when (val v = inner.getChildAt(i)) {
                        is android.widget.ImageView -> v.layoutParams = v.layoutParams.apply {
                            width = (d * (if (wide) 28 else 24)).toInt(); height = width
                        }
                        is android.widget.TextView -> if (v.id == android.view.View.NO_ID) {
                            v.textSize = if (wide) 16f else 13.5f
                        } else {
                            v.textSize = if (wide) 11.5f else 10.5f
                            v.maxLines = 2
                            v.gravity = android.view.Gravity.CENTER
                            v.ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    }
                }
                row.addView(card)
            }
            h.homeCards.addView(row)
        }
        // ◀ no primeiro cartão de cada linha não sai da tela inicial
        cards.forEach { it.nextFocusLeftId = android.view.View.NO_ID }
        h.cardLive.nextFocusLeftId = h.cardLive.id
    }

    /** Ajusta as capas de "Adicionados recentemente" ao espaço livre: nada para rolar. */
    private fun fitHome() {
        val h = b.home
        if (!h.rvHomeRecent.isVisible || h.homeScroll.height == 0) return
        val d = resources.displayMetrics.density
        val content = h.homeScrollContent
        val titleH = h.tvHomeRecent.height + (h.tvHomeRecent.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin
        val free = h.homeScroll.height - content.paddingTop - content.paddingBottom - h.homeCards.height - titleH -
            h.rvHomeRecent.paddingTop - h.rvHomeRecent.paddingBottom
        // nome embaixo da capa (~2 linhas) + folga para o zoom do foco
        val posterH = (free - d * 40).toInt()
        val minH = (d * 72).toInt()
        val maxH = (d * 192).toInt()
        if (posterH < minH) {
            // tela muito baixa: a fileira de novidades sai (o resto continua sem rolar)
            h.tvHomeRecent.isVisible = false
            h.rvHomeRecent.isVisible = false
            return
        }
        recentAdapter.setItemWidth((posterH.coerceAtMost(maxH) / 1.5f).toInt())
    }

    private fun renderHome(s: UiState, lib: LibraryState, hs: HomeSummary) {
        val h = b.home
        h.tvHomeList.text = s.playlist?.name ?: ""
        if (s.playlist == null) {
            h.tvHomeRecent.isVisible = false
            h.rvHomeRecent.isVisible = false
            listOf(h.tvCountLive, h.tvCountMovies, h.tvCountSeries, h.tvCountKids, h.tvCountSports, h.tvCountNovelas)
                .forEach { it.setText(R.string.home_add_list) }
            return
        }
        h.tvCountLive.text = if (s.loading && s.totalCount == 0) getString(R.string.home_loading)
        else resources.getQuantityString(R.plurals.channel_count, s.totalCount, s.totalCount)
        // contagens e novidades vêm prontas do ViewModel (calculadas em segundo plano)
        val movies = hs.movies
        val series = hs.series
        val busy = lib.loading && lib.library.isEmpty
        // perfil KIDS: sem "adicionados recentemente" (traria filmes de todo o catálogo)
        val recent = if (vm.store.homeShowRecent && !kidsProfile) hs.recent else emptyList()
        h.tvHomeRecent.isVisible = recent.isNotEmpty()
        h.rvHomeRecent.isVisible = recent.isNotEmpty()
        if (recent.isNotEmpty()) h.homeScroll.post { fitHome() }
        if (recent !== recentAdapter.items) recentAdapter.submit(recent)
        h.tvCountMovies.text = if (busy) getString(R.string.home_loading) else resources.getQuantityString(R.plurals.home_movies_count, movies, movies)
        h.tvCountSeries.text = if (busy) getString(R.string.home_loading) else resources.getQuantityString(R.plurals.home_series_count, series, series)
    }

    private fun themeCount(tv: android.widget.TextView, c: com.iptv.player4k.data.ThemeContent, live: Int = 0) {
        val s = vm.state.value
        if (s.playlist == null) { tv.setText(R.string.home_add_list); return }
        if (c.isEmpty && live == 0 && (vm.library.value.loading || s.loading)) { tv.setText(R.string.home_loading); return }
        val parts = ArrayList<String>(3)
        if (live > 0) parts += getString(R.string.theme_count_live, live)
        if (c.titles > 0 || (c.channels.isEmpty() && live == 0)) parts += resources.getQuantityString(R.plurals.kids_titles, c.titles, c.titles)
        if (c.channels.isNotEmpty()) parts += resources.getQuantityString(R.plurals.channel_count, c.channels.size, c.channels.size)
        tv.text = parts.take(2).joinToString(" · ")
    }

    private fun renderThemeCounts() {
        themeCount(b.home.tvCountSports, vm.sportsTheme.value, vm.sports.value.orEmpty().count { it.live })
        themeCount(b.home.tvCountNovelas, vm.novelas.value)
    }

    private fun renderKidsCount(k: com.iptv.player4k.data.ThemeContent, lib: LibraryState) {
        val tv = b.home.tvCountKids
        if (vm.state.value.playlist == null) { tv.setText(R.string.home_add_list); return }
        if (k.isEmpty && (lib.loading || vm.state.value.loading)) { tv.setText(R.string.home_loading); return }
        val parts = ArrayList<String>(2)
        if (k.titles > 0 || k.channels.isEmpty()) parts += resources.getQuantityString(R.plurals.kids_titles, k.titles, k.titles)
        if (k.channels.isNotEmpty()) parts += resources.getQuantityString(R.plurals.channel_count, k.channels.size, k.channels.size)
        tv.text = parts.joinToString(" · ")
    }

    /** Validade da lista (painel Xtream), com cor conforme os dias que faltam. */
    private fun renderExpiry(a: AccountState) {
        val tv = b.home.tvHomeExpiry
        if (vm.state.value.playlist == null) { tv.isVisible = false; return }
        tv.isVisible = true
        val grey = 0x40FFFFFF
        val green = 0xFF15965A.toInt()
        val amber = 0xFFD97706.toInt()
        val red = getColor(R.color.live_red)
        val info = a.info
        val (text, color) = when {
            !a.xtream -> getString(R.string.exp_unknown) to grey
            info == null -> getString(if (a.loading) R.string.exp_checking else R.string.exp_unknown) to grey
            !info.isActive -> getString(R.string.exp_inactive, info.status.orEmpty().lowercase()) to red
            info.expiresAt == null -> getString(R.string.exp_unlimited) to green
            else -> {
                val days = info.daysLeft() ?: 0
                val date = dateFmt.format(java.util.Date(info.expiresAt))
                when {
                    info.expiresAt < System.currentTimeMillis() -> getString(R.string.exp_expired, date) to red
                    days == 0L -> getString(R.string.exp_today) to red
                    else -> getString(
                        R.string.exp_days, getString(R.string.exp_until, date),
                        resources.getQuantityString(R.plurals.exp_days_left, days.toInt(), days.toInt()),
                    ) to (if (days <= 3) red else if (days <= 15) amber else green)
                }
            }
        }
        tv.text = if (info?.trial == true) text + " · " + getString(R.string.exp_trial) else text
        tv.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    /** Rodapé de eventos esportivos (ao vivo e próximos). */
    private fun renderSports(events: List<com.iptv.player4k.epg.SportEvent>?, epg: EpgState) {
        val h = b.home
        val show = vm.store.homeShowSports && !kidsProfile
        h.homeSports.isVisible = show
        if (!show) return
        val list = events.orEmpty()
        h.rvHomeSports.isVisible = list.isNotEmpty()
        h.tvSportsEmpty.isVisible = list.isEmpty()
        val live = list.count { it.live }
        h.tvSportsSummary.text = if (list.isEmpty()) "" else getString(R.string.home_sports_summary, live, list.size - live)
        h.tvSportsEmpty.setText(
            when {
                vm.state.value.playlist == null -> R.string.home_add_list
                events == null || (epg.loading && epg.data == null) -> R.string.home_sports_loading
                epg.data == null || epg.data.isEmpty -> R.string.home_sports_no_epg
                else -> R.string.home_sports_none
            }
        )
        // mantém o foco no mesmo cartão ao atualizar
        val focusedPos = h.rvHomeSports.focusedChild?.let { h.rvHomeSports.getChildAdapterPosition(it) } ?: -1
        sportsAdapter.submit(list)
        if (focusedPos >= 0 && list.isNotEmpty()) {
            val pos = focusedPos.coerceAtMost(list.size - 1)
            h.rvHomeSports.post { h.rvHomeSports.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    /**
     * "Abrir no último canal assistido": assim que a lista carrega (só na abertura do app),
     * toca o último canal ao vivo. Respeita o controle dos pais (canal bloqueado não abre).
     */
    // ---------------- Perfis ----------------

    private val kidsProfile get() = vm.store.currentProfile.kids
    private var profileKey: String? = null
    /** Esperando a escolha do perfil na abertura: não abre o último canal antes disso. */
    private var waitingProfile = false

    private fun openProfiles(startup: Boolean = false) {
        waitingProfile = startup
        startActivity(
            Intent(this, com.iptv.player4k.ui.profiles.ProfilesActivity::class.java)
                .putExtra(com.iptv.player4k.ui.profiles.ProfilesActivity.EXTRA_STARTUP, startup)
        )
    }

    /** Avatar no topo e, no perfil KIDS, tela inicial só com o cartão KIDS. */
    private fun applyProfile() {
        val p = vm.store.currentProfile
        val h = b.home
        h.btnHomeProfile.text = p.initial
        h.btnHomeProfile.contentDescription = getString(R.string.home_profile) + ": " + p.name
        val density = resources.displayMetrics.density
        fun ring(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(p.color)
            setStroke((3 * density).toInt(), if (focused) android.graphics.Color.WHITE else getColor(R.color.home_btn_stroke))
        }
        h.btnHomeProfile.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring(true))
            addState(intArrayOf(), ring(false))
        }
        val key = p.id + "|" + p.kids
        val first = profileKey == null
        val changed = !first && profileKey != key
        profileKey = key
        val kids = p.kids
        listOf(h.cardLive, h.cardMovies, h.cardSeries, h.cardSports, h.cardNovelas).forEach { it.isVisible = !kids }
        h.cardKids.isVisible = true
        // linhas de cartões sem nenhum cartão visível somem
        listOf(h.cardLive, h.cardMovies, h.cardSeries, h.cardKids, h.cardSports, h.cardNovelas)
            .mapNotNull { it.parent as? android.view.ViewGroup }.distinct().forEach { row ->
                row.isVisible = (0 until row.childCount).any { row.getChildAt(it).isVisible }
            }
        h.btnHomeLists.isVisible = !kids
        h.btnHomeSettings.isVisible = !kids
        if (changed) {
            // outro perfil: volta para a tela inicial e redesenha recentes/esportes
            if (vm.section != SECTION_HOME) showSection(SECTION_HOME)
            renderHome(vm.state.value, vm.library.value, vm.home.value)
            (if (kids) h.cardKids else h.cardLive).post { (if (kids) h.cardKids else h.cardLive).requestFocus() }
        } else if (first && kids && vm.section == SECTION_HOME) h.cardKids.post { h.cardKids.requestFocus() }
        if (waitingProfile) {
            waitingProfile = false
            maybeStartLastChannel(vm.state.value)
        }
    }

    private fun maybeStartLastChannel(s: UiState) {
        if (waitingProfile) return
        if (vm.autoStartDone || s.playlist == null) return
        if (!vm.store.startLastChannel) { vm.autoStartDone = true; return }
        if (s.totalCount == 0) { if (!s.loading && s.error != null) vm.autoStartDone = true; return }
        vm.autoStartDone = true
        val url = vm.store.recents(s.playlist.id).firstOrNull() ?: return
        val live = com.iptv.player4k.data.Session.live
        val ch = live.firstOrNull { it.url == url } ?: return
        val group = live.filter { it.group == ch.group }
        com.iptv.player4k.data.PlaybackQueue.set(s.playlist, group)
        startActivity(
            Intent(this, com.iptv.player4k.player.PlayerActivity::class.java)
                .putExtra(com.iptv.player4k.player.PlayerActivity.EXTRA_INDEX, group.indexOf(ch).coerceAtLeast(0))
        )
    }

    /** Evento ao vivo: abre direto no canal. Próximo: avisa quando começa. */
    private fun openSportEvent(e: com.iptv.player4k.epg.SportEvent) =
        com.iptv.player4k.ui.home.SportActions.open(this, vm, e)

    private fun openDetails(item: com.iptv.player4k.ui.vod.PosterItem) {
        startActivity(
            Intent(this, com.iptv.player4k.ui.details.DetailsActivity::class.java)
                .putExtra(com.iptv.player4k.ui.details.DetailsActivity.EXTRA_KIND, if (item.movie != null) ContentKind.MOVIE.name else ContentKind.SERIES.name)
                .putExtra(com.iptv.player4k.ui.details.DetailsActivity.EXTRA_ID, item.movie?.id ?: item.series!!.id)
        )
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
            // esconde de verdade a tela da seção: a prévia do Guia (e qualquer vídeo) para de tocar
            val fm = supportFragmentManager
            val tx = fm.beginTransaction().setReorderingAllowed(true)
            fm.fragments.filter { it.tag?.startsWith("tab") == true && !it.isHidden }.forEach { tx.hide(it) }
            tx.commitAllowingStateLoss()
            val card = when (previous) {
                SECTION_MOVIES -> b.home.cardMovies
                SECTION_SERIES -> b.home.cardSeries
                SECTION_KIDS -> b.home.cardKids
                SECTION_SPORTS -> b.home.cardSports
                SECTION_NOVELAS -> b.home.cardNovelas
                else -> b.home.cardLive
            }
            card.post { card.requestFocus() }
            return
        }
        supportActionBar?.title = getString(
            when (sec) {
                SECTION_MOVIES -> R.string.home_movies; SECTION_SERIES -> R.string.home_series
                SECTION_KIDS -> R.string.home_kids; SECTION_SPORTS -> R.string.home_sports_tab
                SECTION_NOVELAS -> R.string.home_novelas; else -> R.string.home_live
            }
        )
        // Ao vivo: abas "Ao vivo" e "Guia"; Filmes/Séries: sem abas
        val live = sec == SECTION_LIVE
        b.tabs.isVisible = live
        val target = when (sec) {
            SECTION_MOVIES -> 1
            SECTION_SERIES -> 2
            else -> if (vm.currentTab == 3) 3 else 0
        }
        when {
            // KIDS não tem aba na barra: mostra a tela direto
            sec == SECTION_KIDS -> showTab(4)
            sec == SECTION_SPORTS -> showTab(5)
            sec == SECTION_NOVELAS -> showTab(6)
            b.tabs.selectedTabPosition != target -> b.tabs.getTabAt(target)?.select()
            else -> showTab(target)
        }
        hideVodTabs()
        b.tabs.post { hideVodTabs() }
        invalidateOptionsMenu()
        b.container.post { b.container.requestFocus() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && vm.section == SECTION_HOME && homeKey(event.keyCode)) return true
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

    /**
     * Setas na tela inicial: ▼ da área de cima (cartões/novidades) vai direto aos eventos do rodapé
     * (a rolagem não "prende" o foco) e ▲ do rodapé volta para as novidades.
     */
    private fun homeKey(code: Int): Boolean {
        val h = b.home
        val focused = currentFocus ?: return false
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (isInside(focused, h.homeScroll)) {
                val next = android.view.FocusFinder.getInstance().findNextFocus(h.homeScroll, focused, android.view.View.FOCUS_DOWN)
                if (next != null && next !== focused) return false
                return focusNearest(h.rvHomeSports, focused)
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> if (isInside(focused, h.rvHomeSports)) {
                if (h.rvHomeRecent.isVisible && focusNearest(h.rvHomeRecent, focused)) return true
                return nearestOf(listOf(h.cardKids, h.cardSports, h.cardNovelas).filter { it.isVisible }.ifEmpty { listOf(h.cardKids) }, focused)?.requestFocus() == true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // nas fileiras, ◀/▶ no começo/fim não "foge" para outra fileira
                for (rv in listOf(h.rvHomeSports, h.rvHomeRecent)) {
                    if (!isInside(focused, rv)) continue
                    val dir = if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) android.view.View.FOCUS_LEFT else android.view.View.FOCUS_RIGHT
                    val next = android.view.FocusFinder.getInstance().findNextFocus(rv, focused, dir)
                    if (next == null) {
                        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return true
                        val pos = rv.getChildAdapterPosition(rv.findContainingItemView(focused) ?: return true)
                        val target = pos + if (dir == android.view.View.FOCUS_RIGHT) 1 else -1
                        // ainda há itens fora da tela: rola até ele e foca
                        if (target in 0 until (rv.adapter?.itemCount ?: 0)) {
                            rv.scrollToPosition(target)
                            rv.post { lm.findViewByPosition(target)?.requestFocus() }
                        }
                        return true
                    }
                }
            }
        }
        return false
    }

    /** Foca o item da fileira mais próximo (na horizontal) do item focado agora. */
    private fun focusNearest(rv: androidx.recyclerview.widget.RecyclerView, from: android.view.View): Boolean {
        if (!rv.isVisible || rv.childCount == 0) return false
        val items = (0 until rv.childCount).map { rv.getChildAt(it) }.filter { it.isFocusable && it.isShown }
        return nearestOf(items, from)?.requestFocus() == true
    }

    private fun nearestOf(views: List<android.view.View>, from: android.view.View): android.view.View? {
        val fx = centerX(from)
        val screenW = resources.displayMetrics.widthPixels
        return views.filter { val r = android.graphics.Rect(); it.getGlobalVisibleRect(r) && r.width() > 0 && r.left < screenW }
            .minByOrNull { kotlin.math.abs(centerX(it) - fx) }
    }

    private fun centerX(v: android.view.View): Int {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return loc[0] + v.width / 2
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
        4 -> com.iptv.player4k.ui.kids.ThemeFragment.newInstance(com.iptv.player4k.ui.kids.ThemeFragment.Theme.KIDS)
        5 -> com.iptv.player4k.ui.kids.ThemeFragment.newInstance(com.iptv.player4k.ui.kids.ThemeFragment.Theme.SPORTS)
        6 -> com.iptv.player4k.ui.kids.ThemeFragment.newInstance(com.iptv.player4k.ui.kids.ThemeFragment.Theme.NOVELAS)
        else -> LiveFragment()
    }

    private fun render(s: UiState) {
        supportActionBar?.subtitle = s.playlist?.name
        b.progress.isVisible = s.loading
        // a barra "Ao vivo | Guia" é só da seção TV AO VIVO: Filmes, Séries e as outras abas ficam separadas
        b.tabs.isVisible = s.playlist != null && vm.section == SECTION_LIVE

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
        // KIDS: sem busca (a tela já mostra todo o conteúdo infantil)
        if (vm.section >= SECTION_KIDS) menu.findItem(R.id.action_search)?.isVisible = false
        // perfil KIDS: sem listas, ajustes, bloqueio ou multi-tela
        if (kidsProfile) for (i in 0 until menu.size()) menu.getItem(i).isVisible = false
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
        com.iptv.player4k.security.SecureField.protect(db.etPass)
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
        const val SECTION_KIDS = 4
        const val SECTION_SPORTS = 5
        const val SECTION_NOVELAS = 6
    }
}
