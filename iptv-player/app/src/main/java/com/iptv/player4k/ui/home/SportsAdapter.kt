package com.iptv.player4k.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Session
import com.iptv.player4k.databinding.ItemSportEventBinding
import com.iptv.player4k.epg.SportEvent
import com.iptv.player4k.util.CoverArt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Cartões de eventos esportivos do rodapé da tela inicial. */
class SportsAdapter(private val onClick: (SportEvent) -> Unit) : RecyclerView.Adapter<SportsAdapter.VH>() {

    var items: List<SportEvent> = emptyList()
        private set

    fun submit(list: List<SportEvent>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ItemSportEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        v.root.setOnFocusChangeListener { view, has ->
            val s = if (has) 1.05f else 1f
            view.animate().scaleX(s).scaleY(s).translationZ(if (has) 10f else 0f).setDuration(120).start()
        }
        val vh = VH(v)
        v.root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        // controle remoto: ▶ no último evento não pula para outra fileira
        v.root.setOnKeyListener { _, code, ev ->
            code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN &&
                vh.bindingAdapterPosition == items.lastIndex
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.v.root.context
        val p = e.programme
        with(holder.v) {
            tvEvTitle.text = p.title
            tvEvChannel.text = e.channel.name
            tvEvBadge.text = if (e.live) ctx.getString(R.string.sport_live) + "  " + ctx.getString(R.string.sport_left, time(p.stop)) else when (dayOffset(p.start)) {
                0 -> ctx.getString(R.string.sport_today, time(p.start))
                1 -> ctx.getString(R.string.sport_tomorrow, time(p.start))
                else -> SimpleDateFormat("EEE HH:mm", PT).format(p.start).uppercase(PT)
            }
            tvEvBadge.backgroundTintList = ColorStateList.valueOf(
                if (e.live) ContextCompat.getColor(ctx, R.color.live_red) else 0xFF3B4466.toInt()
            )
            pbEv.visibility = if (e.live) android.view.View.VISIBLE else android.view.View.INVISIBLE
            pbEv.progress = (p.progress() * 1000).toInt()
            val logo = e.channel.logo ?: Session.epg?.logo(e.channel)
            val art = CoverArt.channel(e.channel.name)
            if (logo.isNullOrBlank()) ivEvLogo.setImageDrawable(art) else ivEvLogo.load(logo) { error(art); placeholder(art) }
            root.contentDescription = tvEvBadge.text.toString() + ", " + p.title + ", " + e.channel.name
        }
    }

    class VH(val v: ItemSportEventBinding) : RecyclerView.ViewHolder(v.root)

    companion object {
        val PT = Locale("pt", "BR")
        private val hm = SimpleDateFormat("HH:mm", PT)
        fun time(t: Long): String = hm.format(t)

        /** 0 = hoje, 1 = amanhã… */
        fun dayOffset(t: Long, now: Long = System.currentTimeMillis()): Int {
            fun day(x: Long) = Calendar.getInstance().apply {
                timeInMillis = x; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return Math.round((day(t) - day(now)) / 86_400_000.0).toInt()
        }
    }
}
