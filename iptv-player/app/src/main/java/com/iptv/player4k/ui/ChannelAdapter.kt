package com.iptv.player4k.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.QualityTag
import com.iptv.player4k.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    private var favorites: Set<String> = emptySet()

    fun submit(newItems: List<Channel>, favs: Set<String>) {
        if (newItems === items && favs == favorites) return
        items = newItems
        favorites = favs
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        with(holder.b) {
            tvNumber.text = (position + 1).toString()
            tvName.text = ch.name
            tvGroup.text = ch.group
            ivFav.visibility = if (ch.url in favorites) View.VISIBLE else View.GONE

            val tag = ch.qualityTag
            if (tag == null) {
                tvQuality.visibility = View.GONE
            } else {
                tvQuality.visibility = View.VISIBLE
                tvQuality.text = tag.label
                val color = ContextCompat.getColor(root.context, colorFor(tag))
                tvQuality.setTextColor(color)
                (tvQuality.background?.mutate() as? GradientDrawable)
                    ?.setStroke((1.5f * root.resources.displayMetrics.density).toInt(), color)
            }

            if (ch.logo.isNullOrBlank()) {
                ivLogo.dispose()
                ivLogo.setImageResource(R.drawable.ic_channel_placeholder)
            } else {
                ivLogo.load(ch.logo) {
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                }
            }

            root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            root.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(items[pos])
                true
            }
        }
    }

    private fun colorFor(tag: QualityTag) = when (tag) {
        QualityTag.UHD -> R.color.q_4k
        QualityTag.FHD -> R.color.q_fhd
        QualityTag.HD -> R.color.q_hd
        QualityTag.SD -> R.color.q_sd
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)
}
