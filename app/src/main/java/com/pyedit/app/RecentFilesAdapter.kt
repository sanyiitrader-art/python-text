package com.pyedit.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ItemRecentFileBinding

class RecentFilesAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    private var rawItems: List<RecentFilesStore.RecentFile> = emptyList()
    private var displayed: List<RecentFilesStore.RecentFile> = emptyList()
    private var currentUri: String? = null

    fun submitList(newItems: List<RecentFilesStore.RecentFile>) {
        rawItems = newItems
        rebuild()
    }

    /** Item 6: the currently open file is pinned to the top and drawn
     * in a greyed-out box (no border). No-op if unchanged, so frequent
     * dirty-state updates don't redraw the list. */
    fun setCurrentUri(uri: String?) {
        if (uri == currentUri) return
        currentUri = uri
        rebuild()
    }

    private fun rebuild() {
        val current = currentUri
        displayed = if (current != null && rawItems.any { it.uriString == current }) {
            rawItems.filter { it.uriString == current } + rawItems.filter { it.uriString != current }
        } else rawItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemRecentFileBinding) : RecyclerView.ViewHolder(binding.root) {
        val defaultRowBackground = binding.recentRow.background
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = displayed[position]
        holder.binding.tvRecentFileName.text = item.name
        holder.binding.tvRecentFileName.setOnClickListener { onClick(item.uriString) }

        if (item.uriString == currentUri) {
            holder.binding.recentRow.setBackgroundResource(R.drawable.recent_current_bg)
        } else {
            holder.binding.recentRow.background = holder.defaultRowBackground
        }
    }

    override fun getItemCount(): Int = displayed.size
}