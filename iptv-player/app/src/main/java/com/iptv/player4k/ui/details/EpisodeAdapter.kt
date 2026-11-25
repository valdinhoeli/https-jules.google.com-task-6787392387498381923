package com.iptv.player4k.ui.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player4k.R
import com.iptv.player4k.data.Episode
import com.iptv.player4k.data.Progress
import com.iptv.player4k.databinding.ItemEpisodeBinding
import com.iptv.player4k.util.Fmt

class EpisodeAdapter(
    private val fallbackImage: () -> String?,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    var items: List<Episode> = emptyList()
        private set
    private var progress: Map<String, Progress> = emptyMap()

    fun submit(list: List<Episode>, prog: Map<String, Progress>) {
        items = list
        progress = prog
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        with(holder.b) {
            tvEpTitle.text = "${e.number}. ${e.title}"
            val ctx = root.context
            tvEpMeta.text = listOfNotNull(
                ctx.getString(R.string.season_n, e.season),
                (e.durationSecs / 60).takeIf { it > 0 }?.let { Fmt.duration(it) },
            ).joinToString(" · ")
            tvEpPlot.text = e.plot
            tvEpPlot.visibility = if (e.plot.isNullOrBlank()) View.GONE else View.VISIBLE
            val art = com.iptv.player4k.util.CoverArt.channel("${e.season}x${e.number} ${e.title}")
            val img = e.image ?: fallbackImage()
            if (img.isNullOrBlank()) ivThumb.setImageDrawable(art)
            else ivThumb.load(img) { crossfade(true); error(art) }
            val p = progress[e.url]
            pbEp.visibility = if (p != null && p.position > 15_000) View.VISIBLE else View.GONE
            if (p != null) pbEp.progress = (p.fraction * 1000).toInt()
            root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        }
    }

    class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)
}
