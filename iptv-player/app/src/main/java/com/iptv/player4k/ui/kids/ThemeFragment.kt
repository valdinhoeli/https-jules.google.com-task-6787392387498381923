package com.iptv.player4k.ui.kids

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.Novelas
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.Session
import com.iptv.player4k.data.ThemeContent
import com.iptv.player4k.databinding.FragmentThemeBinding
import com.iptv.player4k.databinding.ItemThemeChannelBinding
import com.iptv.player4k.epg.SportEvent
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.ui.MainViewModel
import com.iptv.player4k.ui.details.DetailsActivity
import com.iptv.player4k.ui.home.SportActions
import com.iptv.player4k.ui.home.SportsAdapter
import com.iptv.player4k.ui.vod.HomeRow
import com.iptv.player4k.ui.vod.PosterItem
import com.iptv.player4k.ui.vod.VodHomeAdapter
import com.iptv.player4k.ui.vod.VodRows
import com.iptv.player4k.util.CoverArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela temática: reúne tudo de um assunto que está na lista.
 * - KIDS: canais, filmes e séries infantis
 * - ESPORTES: jogos ao vivo e próximos (guia), canais de esporte, filmes/séries de esporte
 * - NOVELAS: novelas passando agora (guia), canais de novelas, novelas do catálogo
 */
class ThemeFragment : Fragment(R.layout.fragment_theme) {
    enum class Theme { KIDS, SPORTS, NOVELAS }

    private val vm: MainViewModel by activityViewModels()
    private var b: FragmentThemeBinding? = null
    private val channelAdapter = ChannelsAdapter()
    private val nowAdapter = ChannelsAdapter()
    private val liveAdapter = SportsAdapter { e -> SportActions.open(requireActivity(), vm, e) }
    private val nextAdapter = SportsAdapter { e -> SportActions.open(requireActivity(), vm, e) }
    private lateinit var vodAdapter: VodHomeAdapter
    private var shownVod: ThemeContent? = null
    /** ao abrir a aba, o primeiro item recebe o foco assim que o conteúdo aparecer */
    private var wantFocus = true

    private val theme: Theme get() = Theme.valueOf(requireArguments().getString(ARG_THEME, Theme.KIDS.name))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentThemeBinding.bind(view).also { this.b = it }
        fun horizontal(rv: RecyclerView, a: RecyclerView.Adapter<*>) {
            rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            rv.adapter = a
            rv.itemAnimator = null
        }
        // a área de rolagem não recebe foco (o controle vai direto aos itens)
        b.themeScroll.isFocusable = false
        b.themeScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        horizontal(b.rvThemeLive, liveAdapter)
        horizontal(b.rvThemeNext, nextAdapter)
        horizontal(b.rvThemeNow, nowAdapter)
        horizontal(b.rvThemeChannels, channelAdapter)
        vodAdapter = VodHomeAdapter(onOpen = ::open, onPlay = ::play)
        b.rvThemeVod.layoutManager = LinearLayoutManager(requireContext())
        b.rvThemeVod.adapter = vodAdapter
        b.rvThemeVod.itemAnimator = null
        b.tvThemeEmpty.setText(
            when (theme) { Theme.KIDS -> R.string.kids_empty; Theme.SPORTS -> R.string.sports_empty; Theme.NOVELAS -> R.string.novelas_empty }
        )
        b.ivThemeEmpty.setImageResource(
            when (theme) { Theme.KIDS -> R.drawable.ic_child; Theme.SPORTS -> R.drawable.ic_sports; Theme.NOVELAS -> R.drawable.ic_series }
        )

        val content: Flow<ThemeContent> = when (theme) {
            Theme.KIDS -> vm.kids
            Theme.SPORTS -> vm.sportsTheme
            Theme.NOVELAS -> vm.novelas
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(content, vm.contentVersion, vm.epg, vm.sports) { c, _, _, ev -> c to ev }
                    .collect { (c, ev) -> render(c, ev) }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // ao abrir a aba, atualiza quem está ao vivo / no ar
        if (!hidden && theme == Theme.SPORTS) vm.refreshSports()
        if (!hidden && pendingRender) { pendingRender = false; render(contentNow(), vm.sports.value) }
        if (!hidden) {
            wantFocus = true
            b?.let { bb -> bb.themeScroll.post { firstItem(bb)?.let { if (it.requestFocus()) wantFocus = false } } }
        }
    }

    override fun onDestroyView() {
        b = null
        shownVod = null
        super.onDestroyView()
    }

    private fun section(title: TextView, rv: RecyclerView, text: String?, count: Int) {
        val show = text != null && count > 0
        title.isVisible = show
        rv.isVisible = show
        if (show) title.text = "$text  ·  $count"
    }

    private var pendingRender = false

    private fun contentNow(): ThemeContent = when (theme) {
        Theme.KIDS -> vm.kids.value
        Theme.SPORTS -> vm.sportsTheme.value
        Theme.NOVELAS -> vm.novelas.value
    }

    private fun render(c: ThemeContent, events: List<SportEvent>?) {
        // escondida atrás de outra tela: não redesenha (redesenha ao aparecer)
        if (isHidden) { pendingRender = true; return }
        val b = b ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // NOVELAS: canais passando novela agora (pelo guia), além dos canais de novela
            val now = if (theme == Theme.NOVELAS) withContext(Dispatchers.Default) {
                // canais que já estão em "Canais de novelas" não se repetem aqui
                val own = c.channels.mapTo(HashSet()) { it.url }
                novelasOnAir().filter { it.url !in own }
            } else emptyList()
            val b2 = this@ThemeFragment.b ?: return@launch
            val live = if (theme == Theme.SPORTS) events.orEmpty().filter { it.live } else emptyList()
            val next = if (theme == Theme.SPORTS) events.orEmpty().filter { !it.live } else emptyList()
            val empty = c.isEmpty && live.isEmpty() && next.isEmpty() && now.isEmpty()
            val loading = empty && (vm.library.value.loading || vm.state.value.loading)
            b2.themeEmpty.isVisible = empty && !loading
            b2.themeScroll.isVisible = !empty
            section(b2.tvThemeLive, b2.rvThemeLive, getString(R.string.sports_live_now), live.size)
            section(b2.tvThemeNext, b2.rvThemeNext, getString(R.string.sports_next), next.size)
            section(b2.tvThemeNow, b2.rvThemeNow, getString(R.string.novelas_now), now.size)
            section(
                b2.tvThemeChannels, b2.rvThemeChannels,
                getString(when (theme) { Theme.KIDS -> R.string.kids_channels; Theme.SPORTS -> R.string.sports_channels; Theme.NOVELAS -> R.string.novelas_channels }),
                c.channels.size,
            )
            liveAdapter.submit(live)
            nextAdapter.submit(next)
            nowAdapter.submit(now)
            channelAdapter.submit(c.channels)
            if (shownVod !== c) {
                shownVod = c
                vodAdapter.submit(rows(c))
            }
            // controle remoto: ao abrir a aba, o foco vai para o primeiro item (não para a área de rolagem)
            if (wantFocus && isVisible && !empty) {
                b2.themeScroll.post {
                    val v = this@ThemeFragment.b?.let { firstItem(it) } ?: return@post
                    if (v.requestFocus()) wantFocus = false
                }
            }
        }
    }

    private fun firstItem(b: FragmentThemeBinding): View? =
        listOf(b.rvThemeLive, b.rvThemeNext, b.rvThemeNow, b.rvThemeChannels, b.rvThemeVod)
            .firstOrNull { it.isVisible && it.childCount > 0 }
            ?.let { rv -> if (rv === b.rvThemeVod) rv.focusSearch(View.FOCUS_DOWN) ?: rv.getChildAt(0) else rv.getChildAt(0) }

    /** Canais (já liberados pelo controle dos pais) com novela no ar agora, pelo guia. */
    private fun novelasOnAir(): List<Channel> {
        val epg = Session.epg ?: return emptyList()
        val t = System.currentTimeMillis()
        return Session.live.filter { ch ->
            val p = epg.now(ch, t) ?: return@filter false
            p.isNovela || Novelas.isNovelaTitle(p.title)
        }.take(40)
    }

    private fun rows(c: ThemeContent): List<HomeRow> {
        val movies = c.movies.map { VodRows.poster(it) }.sortedByDescending { it.added }
        val series = c.series.map { VodRows.poster(it) }.sortedByDescending { it.added }
        val rows = ArrayList<HomeRow>()
        when (theme) {
            Theme.KIDS -> {
                val pool = movies + series
                val hero = (if (pool.any { it.added > 0 }) pool.sortedByDescending { it.added } else pool.sortedByDescending { it.rating ?: 0f }).take(5)
                if (hero.isNotEmpty()) rows += HomeRow.Hero(hero, newest = false, badges = hero.map { getString(R.string.kids_badge) })
                if (movies.isNotEmpty()) rows += HomeRow.Row(getString(R.string.kids_movies), movies)
                if (series.isNotEmpty()) rows += HomeRow.Row(getString(R.string.kids_series), series)
            }
            Theme.SPORTS -> {
                if (movies.isNotEmpty()) rows += HomeRow.Row(getString(R.string.sports_movies), movies)
                if (series.isNotEmpty()) rows += HomeRow.Row(getString(R.string.sports_series), series)
            }
            Theme.NOVELAS -> {
                if (series.isNotEmpty()) rows += HomeRow.Row(getString(R.string.novelas_series), series)
                if (movies.isNotEmpty()) rows += HomeRow.Row(getString(R.string.novelas_movies), movies)
            }
        }
        // por categoria, quando a lista separa (Novelas Turcas, Lutas, Animação…)
        val byCat = (series + movies).groupBy { it.category }.filter { it.value.size >= 2 }
        if (byCat.size > 1) byCat.toSortedMap().forEach { (cat, items) -> rows += HomeRow.Row(cat, items) }
        return rows
    }

    private fun open(item: PosterItem) {
        startActivity(
            Intent(requireContext(), DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_KIND, if (item.movie != null) ContentKind.MOVIE.name else ContentKind.SERIES.name)
                .putExtra(DetailsActivity.EXTRA_ID, item.movie?.id ?: item.series!!.id)
        )
    }

    private fun play(item: PosterItem) {
        val m = item.movie
        if (m != null) VodRows.playMovie(requireContext(), m) else open(item)
    }

    private fun playChannel(list: List<Channel>, pos: Int) {
        // a fila do player são os canais do tema: ▲/▼ troca só entre eles
        PlaybackQueue.set(vm.state.value.playlist, list)
        vm.addRecent(list[pos])
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, pos))
    }

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelsAdapter.VH>() {
        private var items: List<Channel> = emptyList()

        fun submit(list: List<Channel>) {
            if (list == items) { notifyItemRangeChanged(0, items.size); return }
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemThemeChannelBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = ItemThemeChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val vh = VH(v)
            v.root.setOnClickListener { vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { playChannel(items, it) } }
            v.root.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.08f else 1f
                view.animate().scaleX(s).scaleY(s).translationZ(if (has) 10f else 0f).setDuration(120).start()
            }
            // controle remoto: ▶ no último canal não pula para outra fileira
            v.root.setOnKeyListener { _, code, ev ->
                code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN &&
                    vh.bindingAdapterPosition == items.lastIndex
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            with(holder.v) {
                tvKcName.text = ch.name
                val now = Session.epg?.now(ch)
                tvKcNow.text = now?.title ?: ch.group
                val logo = ch.logo ?: Session.epg?.logo(ch)
                val art = CoverArt.channel(ch.name)
                if (logo.isNullOrBlank()) ivKcLogo.setImageDrawable(art) else ivKcLogo.load(logo) { error(art); placeholder(art) }
                root.contentDescription = ch.name + (now?.let { ", " + it.title } ?: "")
            }
        }
    }

    companion object {
        private const val ARG_THEME = "theme"
        fun newInstance(theme: Theme) = ThemeFragment().apply { arguments = bundleOf(ARG_THEME to theme.name) }
    }
}
