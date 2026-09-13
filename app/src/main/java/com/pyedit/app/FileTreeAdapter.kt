package com.pyedit.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ItemFileNodeBinding

/**
 * Flattens the Root Folder tree into a displayable list with indentation,
 * supporting collapse/expand per spec §19 ("> = collapsed, ∨ = expanded").
 */
class FileTreeAdapter(
    private val onFileClick: (java.io.File) -> Unit
) : RecyclerView.Adapter<FileTreeAdapter.ViewHolder>() {

    private data class Row(val node: WorkspaceManager.FileNode, val depth: Int)

    private var rootNodes: List<WorkspaceManager.FileNode> = emptyList()
    private val collapsedPaths = mutableSetOf<String>()
    private var flattenedRows: List<Row> = emptyList()

    fun submitTree(nodes: List<WorkspaceManager.FileNode>) {
        rootNodes = nodes
        recomputeRows()
    }

    private fun recomputeRows() {
        val rows = mutableListOf<Row>()
        fun visit(nodes: List<WorkspaceManager.FileNode>, depth: Int) {
            for (node in nodes) {
                rows.add(Row(node, depth))
                if (node is WorkspaceManager.FileNode.Folder &&
                    node.file.absolutePath !in collapsedPaths
                ) {
                    visit(node.children, depth + 1)
                }
            }
        }
        visit(rootNodes, 0)
        flattenedRows = rows
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemFileNodeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = flattenedRows[position]
        val indentDp = 12 * row.depth
        val density = holder.binding.root.resources.displayMetrics.density
        holder.binding.root.setPadding((indentDp * density).toInt(), 0, 0, 0)

        when (val node = row.node) {
            is WorkspaceManager.FileNode.Folder -> {
                val collapsed = node.file.absolutePath in collapsedPaths
                val arrow = if (collapsed) ">" else "\u2228" // ">" or "∨"
                holder.binding.tvNodeName.text = "$arrow ${node.name}"
                holder.binding.root.setOnClickListener {
                    if (collapsed) collapsedPaths.remove(node.file.absolutePath)
                    else collapsedPaths.add(node.file.absolutePath)
                    recomputeRows()
                }
            }
            is WorkspaceManager.FileNode.Leaf -> {
                holder.binding.tvNodeName.text = node.name
                holder.binding.root.setOnClickListener { onFileClick(node.file) }
            }
        }
    }

    override fun getItemCount(): Int = flattenedRows.size
}