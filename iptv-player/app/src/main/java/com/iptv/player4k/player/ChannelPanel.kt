package com.iptv.player4k.player

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Session
import com.iptv.player4k.databinding.ActivityPlayerBinding
import com.iptv.player4k.databinding.ItemPanelChannelBinding
import com.iptv.player4k.databinding.ItemPanelProgBinding
import com.iptv.player4k.epg.Programme
import com.iptv.player4k.util.CoverArt
import com.iptv.player4k.util.Fmt

/**
 * Lista de canais sobre o vídeo (OK no canal ao vivo):
 * canais à esquerda, programação do canal focado no meio, programa atual no canto.
 * O vídeo continua tocando atrás; OK num canal troca para ele.
 */
class ChannelPanel(
    private val b: ActivityPlayerBinding,
    /** canal escolhido: lista (grupo) e posição nela */
    private val onPick: (List<Channel>, Int) -> Unit,
    /** voltar estando nos grupos: sai do player */
    private val onExit: () -> Unit,
) {
    private val ctx = b.root.context
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }
    private var channels: List<Channel> = emptyList()
    private var playing = -1
    private var focused = -1

    private val channelAdapter = ChannelsAdapter()
    private val scheduleAdapter = ScheduleAdapter()
    private val groupAdapter = GroupsAdapter()
    private var playingUrl: String? = null
    private var groupLabel = ""

    /** grupo: rótulo e canais (Todos, Favoritos e cada grupo da lista, com controle dos pais) */
    private class Group(val label: String, val channels: List<Channel>)
    private var groups: List<Group> = emptyList()

    val isShowing get() = b.chPanel.isVisible

    init {
        b.rvPanelChannels.layoutManager = LinearLayoutManager(ctx)
        b.rvPanelChannels.adapter = channelAdapter
        b.rvPanelChannels.itemAnimator = null
        b.rvPanelSchedule.layoutManager = LinearLayoutManager(ctx)
        b.rvPanelSchedule.adapter = scheduleAdapter
        b.rvPanelSchedule.itemAnimator = null
        b.rvPanelGroups.layoutManager = LinearLayoutManager(ctx)
        b.rvPanelGroups.adapter = groupAdapter
        b.rvPanelGroups.itemAnimator = null
        // toque fora das listas fecha
        b.chPanel.setOnClickListener { hide() }
    }

    /** [withGroups]: abre já na coluna de grupos (botão voltar no canal ao vivo). */
    fun show(list: List<Channel>, current: Int, withGroups: Boolean = false) {
        b.tvStatus.visibility = View.GONE
        channels = list
        playing = current
        playingUrl = list.getOrNull(current)?.url
        groupLabel = if (list.isNotEmpty() && list.all { it.group == list[0].group }) list[0].group
        else ctx.getString(R.string.panel_all)
        b.panelGroupsCol.isVisible = false
        b.panelCardCol.isVisible = true
        channelAdapter.notifyDataSetChanged()
        b.chPanel.isVisible = true
        val lm = b.rvPanelChannels.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(current, (b.root.height / 3).coerceAtLeast(0))
        focusOn(current)
        if (withGroups) showGroups()
        else b.rvPanelChannels.post {
            b.rvPanelChannels.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus()
                ?: b.rvPanelChannels.requestFocus()
        }
        poke()
    }

    // ---------------- grupos ----------------

    private fun buildGroups(): List<Group> {
        val live = Session.live
        val out = ArrayList<Group>()
        out += Group(ctx.getString(R.string.panel_all), live)
        val pid = Session.playlist?.id
        if (pid != null) {
            val favs = com.iptv.player4k.data.PlaylistStore(ctx).favorites(pid)
            val favList = live.filter { it.url in favs }
            if (favList.isNotEmpty()) out += Group(ctx.getString(R.string.group_favorites), favList)
        }
        live.groupBy { it.group }.forEach { (g, l) -> out += Group(g, l) }
        return out
    }

    private val groupsFocused get() = b.panelGroupsCol.isVisible && b.rvPanelGroups.hasFocus()

    /** Mostra a coluna de grupos com o foco no grupo atual. O vídeo continua tocando. */
    fun showGroups() {
        groups = buildGroups()
        groupAdapter.notifyDataSetChanged()
        b.panelGroupsCol.isVisible = true
        b.panelCardCol.isVisible = false
        val cur = groups.indexOfFirst { it.label == groupLabel }.coerceAtLeast(0)
        (b.rvPanelGroups.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(cur, (b.root.height / 3).coerceAtLeast(0))
        b.rvPanelGroups.post {
            b.rvPanelGroups.findViewHolderForAdapterPosition(cur)?.itemView?.requestFocus()
                ?: b.rvPanelGroups.requestFocus()
        }
        poke()
    }

    /** Abre os canais do grupo escolhido (foco no canal que está tocando, se estiver nele). */
    private fun openGroup(pos: Int) {
        val g = groups.getOrNull(pos) ?: return
        if (g.channels.isEmpty()) return
        groupLabel = g.label
        channels = g.channels
        playing = channels.indexOfFirst { it.url == playingUrl }
        channelAdapter.notifyDataSetChanged()
        b.panelGroupsCol.isVisible = false
        b.panelCardCol.isVisible = true
        val target = playing.coerceAtLeast(0)
        (b.rvPanelChannels.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, (b.root.height / 3).coerceAtLeast(0))
        focusOn(target)
        b.rvPanelChannels.post {
            b.rvPanelChannels.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
        poke()
    }

    /** Voltar: canais → grupos → sai do player. */
    fun back() {
        if (groupsFocused) onExit() else showGroups()
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        b.chPanel.isVisible = false
    }

    /** reinicia o tempo para fechar sozinho */
    fun poke() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 15_000)
    }

    private fun focusOn(pos: Int) {
        if (pos !in channels.indices) return
        focused = pos
        val ch = channels[pos]
        val epg = Session.epg
        b.tvPanelGroup.text = "${ctx.getString(R.string.panel_hint_groups)}   ·   $groupLabel"
        b.tvPanelChannel.text = "${pos + 1}  ${ch.name}"
        b.tvPanelChGroup.text = ch.group
        loadLogo(b.ivPanelLogo, ch)

        val now = System.currentTimeMillis()
        val all = epg?.programmes(ch).orEmpty()
        val list = all.filter { it.stop > now - 3 * 60 * 60_000L }.take(30)
        scheduleAdapter.submit(list, now)
        val cur = list.indexOfFirst { it.isLive(now) }
        (b.rvPanelSchedule.layoutManager as LinearLayoutManager).scrollToPositionWithOffset((cur - 1).coerceAtLeast(0), 0)

        val p = list.getOrNull(cur)
        if (p == null) {
            b.tvPanelNowTitle.setText(R.string.epg_no_info)
            b.tvPanelNowTime.text = ""
            b.tvPanelNowRemain.text = ""
            b.tvPanelNowDesc.isVisible = false
            b.panelNowProgress.isVisible = false
        } else {
            b.tvPanelNowTitle.text = p.title
            b.tvPanelNowTime.text = "${Fmt.time(p.start)} — ${Fmt.time(p.stop)}"
            b.panelNowProgress.isVisible = true
            b.panelNowProgress.setProgressCompat((p.progress(now) * 1000).toInt(), false)
            b.tvPanelNowRemain.text = ctx.getString(R.string.minutes, ((p.stop - now) / 60_000L).toInt().coerceAtLeast(1))
            b.tvPanelNowDesc.text = p.desc
            b.tvPanelNowDesc.isVisible = !p.desc.isNullOrBlank()
        }
    }

    private fun loadLogo(v: android.widget.ImageView, ch: Channel) {
        val logo = ch.logo ?: Session.epg?.logo(ch)
        val art = CoverArt.channel(ch.name)
        if (logo.isNullOrBlank()) v.setImageDrawable(art) else v.load(logo) { error(art) }
    }

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelsAdapter.VH>() {
        inner class VH(val v: ItemPanelChannelBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = ItemPanelChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val vh = VH(v)
            v.root.setOnFocusChangeListener { _, has ->
                paint(v, has)
                if (has) {
                    vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { focusOn(it) }
                    poke()
                }
            }
            v.root.setOnClickListener {
                vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    hide()
                    if (pos != playing) onPick(channels, pos)
                }
            }
            // ◀ na lista de canais abre os grupos
            v.root.setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && ev.action == android.view.KeyEvent.ACTION_DOWN) {
                    showGroups(); true
                } else false
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = channels[position]
            val v = holder.v
            v.tvPcName.text = "${position + 1}  ${ch.name}"
            loadLogo(v.ivPcLogo, ch)
            v.ivPcPlaying.visibility = if (position == playing) View.VISIBLE else View.INVISIBLE
            val now = System.currentTimeMillis()
            val p = Session.epg?.now(ch, now)
            v.tvPcNow.text = p?.title ?: ctx.getString(R.string.epg_no_info)
            v.pcProgress.setProgressCompat(((p?.progress(now) ?: 0f) * 1000).toInt(), false)
            paint(v, v.root.hasFocus())
        }

        /** item focado: fundo claro com texto escuro */
        private fun paint(v: ItemPanelChannelBinding, focused: Boolean) {
            v.tvPcName.setTextColor(if (focused) 0xFF141824.toInt() else 0xFFFFFFFF.toInt())
            v.tvPcNow.setTextColor(if (focused) 0xFF3C4458.toInt() else 0xD0FFFFFF.toInt())
        }
    }

    private inner class GroupsAdapter : RecyclerView.Adapter<GroupsAdapter.VH>() {
        inner class VH(val v: com.iptv.player4k.databinding.ItemPanelGroupBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = com.iptv.player4k.databinding.ItemPanelGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val vh = VH(v)
            v.root.setOnFocusChangeListener { _, has -> paint(vh, has); if (has) poke() }
            v.root.setOnClickListener { vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { openGroup(it) } }
            // ▶ nos grupos abre o grupo focado
            v.root.setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN) {
                    vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { openGroup(it) }; true
                } else false
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val g = groups[position]
            holder.v.tvPgName.text = g.label
            holder.v.tvPgCount.text = g.channels.size.toString()
            paint(holder, holder.v.root.hasFocus())
        }

        private fun paint(vh: VH, focused: Boolean) {
            val current = groups.getOrNull(vh.bindingAdapterPosition)?.label == groupLabel
            vh.v.tvPgName.setTextColor(
                when {
                    focused -> 0xFF141824.toInt()
                    current -> ContextCompat.getColor(ctx, R.color.epg_now)
                    else -> 0xFFFFFFFF.toInt()
                }
            )
            vh.v.tvPgCount.setTextColor(if (focused) 0xFF3C4458.toInt() else 0xB3FFFFFF.toInt())
        }
    }

    private inner class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {
        inner class VH(val v: ItemPanelProgBinding) : RecyclerView.ViewHolder(v.root)
        private var items: List<Programme> = emptyList()
        private var now = 0L

        fun submit(list: List<Programme>, t: Long) {
            items = list
            now = t
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size.coerceAtLeast(1)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemPanelProgBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.v
            val p = items.getOrNull(position)
            if (p == null) {
                v.tvPpTime.text = ""
                v.tvPpTitle.setText(R.string.epg_no_info)
                return
            }
            val live = p.isLive(now)
            val color = if (live) ContextCompat.getColor(ctx, R.color.epg_now)
            else if (p.stop <= now) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt()
            v.tvPpTime.text = Fmt.time(p.start)
            v.tvPpTitle.text = p.title
            v.tvPpTime.setTextColor(color)
            v.tvPpTitle.setTextColor(color)
            v.tvPpTitle.setTypeface(null, if (live) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
