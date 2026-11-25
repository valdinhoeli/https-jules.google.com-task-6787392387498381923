package com.iptv.player4k.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player4k.databinding.ItemGroupBinding

class GroupAdapter(
    private val vertical: Boolean,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var items: List<GroupItem> = emptyList()
    private var selected: String = ""

    init { setHasStableIds(true) }

    fun submit(newItems: List<GroupItem>, newSelected: String) {
        if (newItems === items) {
            if (newSelected != selected) {
                val old = items.indexOfFirst { it.key == selected }
                selected = newSelected
                if (old >= 0) notifyItemChanged(old)
                val now = items.indexOfFirst { it.key == selected }
                if (now >= 0) notifyItemChanged(now)
            }
            return
        }
        items = newItems
        selected = newSelected
        notifyDataSetChanged()
    }

    fun indexOfSelected() = items.indexOfFirst { it.key == selected }

    override fun getItemId(position: Int) = items[position].key.hashCode().toLong()
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.layoutParams = b.root.layoutParams.apply {
            width = if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (!vertical) b.tvName.layoutParams = b.tvName.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.b) {
            tvName.text = item.label
            tvCount.text = item.count.toString()
            root.isSelected = item.key == selected
            root.setOnClickListener { onClick(item.key) }
        }
    }

    class VH(val b: ItemGroupBinding) : RecyclerView.ViewHolder(b.root)
}
