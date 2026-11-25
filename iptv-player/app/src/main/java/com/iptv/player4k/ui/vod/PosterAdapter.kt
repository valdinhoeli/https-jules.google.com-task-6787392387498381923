package com.iptv.player4k.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.iptv.player4k.databinding.ItemPosterBinding
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
        return VH(b)
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
            if (item.poster.isNullOrBlank()) {
                ivPoster.dispose()
                ivPoster.setImageDrawable(null)
            } else {
                ivPoster.load(item.poster) { crossfade(true) }
            }
            root.setOnClickListener { onClick(item) }
            card.clipToOutline = true
        }
    }

    class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)
}
