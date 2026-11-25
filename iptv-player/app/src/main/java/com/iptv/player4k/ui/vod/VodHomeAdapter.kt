package com.iptv.player4k.ui.vod

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.databinding.ItemHeroBinding
import com.iptv.player4k.databinding.ItemRowBinding
import com.iptv.player4k.util.Fmt

/** Tela inicial de filmes/séries: destaque + fileiras horizontais por categoria. */
class VodHomeAdapter(
    private val onOpen: (PosterItem) -> Unit,
    private val onPlay: (PosterItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HomeRow> = emptyList()
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40) }
    private val scrollStates = HashMap<String, Parcelable?>()

    fun submit(list: List<HomeRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is HomeRow.Hero) HERO else ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == HERO) HeroVH(ItemHeroBinding.inflate(inf, parent, false))
        else RowVH(ItemRowBinding.inflate(inf, parent, false)).also { vh ->
            vh.b.rvRow.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                .apply { initialPrefetchItemCount = 6 }
            vh.b.rvRow.setRecycledViewPool(pool)
            vh.b.rvRow.adapter = vh.adapter
            vh.b.rvRow.itemAnimator = null
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Hero -> (holder as HeroVH).bind(row)
            is HomeRow.Row -> (holder as RowVH).bind(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RowVH) holder.title?.let { scrollStates[it] = holder.b.rvRow.layoutManager?.onSaveInstanceState() }
        if (holder is HeroVH) holder.stop()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is HeroVH) holder.stop()
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        if (holder is HeroVH) holder.start()
    }

    inner class RowVH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root) {
        val adapter = PosterAdapter(onOpen)
        var title: String? = null

        fun bind(row: HomeRow.Row) {
            title?.let { scrollStates[it] = b.rvRow.layoutManager?.onSaveInstanceState() }
            title = row.title
            b.tvRowTitle.text = row.title
            adapter.submit(row.items)
            val st = scrollStates[row.title]
            if (st != null) b.rvRow.layoutManager?.onRestoreInstanceState(st) else b.rvRow.scrollToPosition(0)
        }
    }

    inner class HeroVH(val b: ItemHeroBinding) : RecyclerView.ViewHolder(b.root) {
        private var row: HomeRow.Hero? = null
        private var index = 0
        private val handler = Handler(Looper.getMainLooper())
        private val rotate = object : Runnable {
            override fun run() {
                val r = row ?: return
                // não troca o destaque enquanto o usuário está nos botões
                if (r.items.size > 1 && !b.btnHeroPlay.hasFocus() && !b.btnHeroInfo.hasFocus()) {
                    index = (index + 1) % r.items.size
                    show(r.items[index], r.newest)
                }
                handler.postDelayed(this, ROTATE_MS)
            }
        }

        fun bind(r: HomeRow.Hero) {
            row = r
            index = 0
            r.items.firstOrNull()?.let { show(it, r.newest) }
            start()
        }

        fun start() {
            handler.removeCallbacks(rotate)
            if ((row?.items?.size ?: 0) > 1) handler.postDelayed(rotate, ROTATE_MS)
        }

        fun stop() = handler.removeCallbacks(rotate)

        private fun show(item: PosterItem, newest: Boolean) {
            val ctx = b.root.context
            b.tvHeroBadge.setText(if (newest) R.string.hero_new else R.string.hero_featured)
            b.tvHeroTitle.text = item.title
            b.tvHeroMeta.text = listOfNotNull(
                item.year, Fmt.rating(item.rating), item.category.takeIf { it.isNotBlank() },
                if (item.series != null) ctx.getString(R.string.kind_series).lowercase()
                    .replaceFirstChar { it.uppercase() } else null,
            ).joinToString("  •  ")
            b.tvHeroPlot.text = item.plot
            b.tvHeroPlot.visibility = if (item.plot.isNullOrBlank()) View.GONE else View.VISIBLE

            val bg = item.backdrop ?: item.poster
            b.ivHeroBg.load(bg) { crossfade(700) }
            if (Build.VERSION.SDK_INT >= 31) {
                b.ivHeroBg.setRenderEffect(
                    if (item.backdrop == null) RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP) else null
                )
            }
            b.ivHeroPoster.load(item.poster) { crossfade(500) }
            b.btnHeroPlay.setOnClickListener { onPlay(item) }
            b.btnHeroInfo.setOnClickListener { onOpen(item) }
            b.ivHeroPoster.setOnClickListener { onOpen(item) }
        }
    }

    companion object {
        private const val HERO = 1
        private const val ROW = 2
        private const val ROTATE_MS = 9_000L
    }
}
