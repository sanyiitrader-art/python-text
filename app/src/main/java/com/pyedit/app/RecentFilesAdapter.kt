package com.pyedit.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ItemRecentFileBinding

class RecentFilesAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    private var items: List<RecentFilesStore.RecentFile> = emptyList()

    fun submitList(newItems: List<RecentFilesStore.RecentFile>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemRecentFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvRecentFileName.text = item.name
        holder.binding.tvRecentFileName.setOnClickListener { onClick(item.path) }
    }

    override fun getItemCount(): Int = items.size
}