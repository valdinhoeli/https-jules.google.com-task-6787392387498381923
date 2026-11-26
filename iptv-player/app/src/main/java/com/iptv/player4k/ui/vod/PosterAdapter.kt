package com.iptv.player4k.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.iptv.player4k.databinding.ItemPosterBinding
import com.iptv.player4k.util.AgeBadge
import com.iptv.player4k.util.CoverArt
import com.iptv.player4k.util.Fmt

class PosterAdapter(
    private val onClick: (PosterItem) -> Unit,
    private val fixedWidth: Int? = null,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<PosterItem> = emptyList()
        private set

    fun submit(list: List<PosterItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        fixedWidth?.let { w ->
            val h = (w * 1.5f).toInt()
            b.card.layoutParams = b.card.layoutParams.apply { width = w; height = h }
            b.tvTitle.layoutParams = b.tvTitle.layoutParams.apply { width = w }
        }
        b.root.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).translationZ(if (has) 12f else 0f).setDuration(150).start()
            b.tvTitle.setTextColor(if (has) 0xFFFFFFFF.toInt() else 0xFF98A2B8.toInt())
        }
        val vh = VH(b)
        // controle remoto: ▶ no último item da fileira não pula para outra fileira
        b.root.setOnKeyListener { _, code, ev ->
            code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN &&
                vh.bindingAdapterPosition == items.lastIndex
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.b) {
            tvTitle.text = item.title
            tvPlaceholder.text = item.title
            val rating = Fmt.rating(item.rating)
            tvRating.visibility = if (rating != null) View.VISIBLE else View.GONE
            tvRating.text = rating
            val p = item.progress
            pbWatched.visibility = if (p != null) View.VISIBLE else View.GONE
            if (p != null) pbWatched.progress = (p * 1000).toInt().coerceAtLeast(30)
            tvPlaceholder.visibility = View.GONE
            AgeBadge.bind(tvAge, item.age)
            val cover = CoverArt.cover(root.context, item.title, item.series != null)
            if (item.poster.isNullOrBlank()) {
                ivPoster.dispose()
                ivPoster.setImageDrawable(cover)
            } else {
                ivPoster.load(item.poster) {
                    crossfade(true)
                    placeholder(cover)
                    error(cover)
                }
            }
            root.setOnClickListener { onClick(item) }
            root.contentDescription = item.title
            card.clipToOutline = true
        }
    }

    class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)
}
