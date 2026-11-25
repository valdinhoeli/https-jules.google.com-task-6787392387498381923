package com.iptv.player4k.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import com.iptv.player4k.epg.EpgData
import com.iptv.player4k.util.Fmt

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    private var favorites: Set<String> = emptySet()
    private var epg: EpgData? = null

    fun submit(newItems: List<Channel>, favs: Set<String>) {
        if (newItems === items && favs == favorites) return
        items = newItems
        favorites = favs
        notifyDataSetChanged()
    }

    fun setEpg(data: EpgData?) {
        if (data === epg) return
        epg = data
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EPG)
    }

    /** Atualiza só as linhas do guia (barra de progresso e "agora"). */
    fun tick() = notifyItemRangeChanged(0, itemCount, PAYLOAD_EPG)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_EPG }) bindEpg(holder.b, items[position])
        else onBindViewHolder(holder, position)
    }

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
            bindEpg(this, ch)

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

    private fun bindEpg(b: ItemChannelBinding, ch: Channel) {
        val data = epg
        val now = System.currentTimeMillis()
        val cur = data?.now(ch, now)
        if (cur == null) {
            b.epgBox.visibility = View.GONE
            b.tvGroup.visibility = View.VISIBLE
            return
        }
        val ctx = b.root.context
        b.epgBox.visibility = View.VISIBLE
        b.tvGroup.visibility = View.GONE
        val red = ContextCompat.getColor(ctx, R.color.live_red)
        b.tvNow.text = SpannableStringBuilder().apply {
            append("● ")
            setSpan(ForegroundColorSpan(red), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val s = length
            append(cur.title)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            val t = length
            append(Fmt.range(cur.start, cur.stop))
            setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_secondary)), t, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.epgProgress.setProgressCompat((cur.progress(now) * 1000).toInt(), false)
        val next = data.next(ch, now)
        if (next == null) {
            b.tvNext.visibility = View.GONE
        } else {
            b.tvNext.visibility = View.VISIBLE
            b.tvNext.text = ctx.getString(R.string.epg_next) + " " + Fmt.time(next.start) + " · " + next.title
        }
    }

    private fun colorFor(tag: QualityTag) = when (tag) {
        QualityTag.UHD -> R.color.q_4k
        QualityTag.FHD -> R.color.q_fhd
        QualityTag.HD -> R.color.q_hd
        QualityTag.SD -> R.color.q_sd
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val PAYLOAD_EPG = "epg"
    }
}
