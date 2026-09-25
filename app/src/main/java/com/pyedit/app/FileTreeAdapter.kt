package com.pyedit.app

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ItemFileNodeBinding
import com.pyedit.app.databinding.ItemFileNodeEditBinding

class FileTreeAdapter(
    private val onFileClick: (WorkspaceManager.FileNode.Leaf) -> Unit,
    private val onFileLongPress: (leaf: WorkspaceManager.FileNode.Leaf, depth: Int, anchor: View) -> Unit,
    private val onNameConfirmed: (existing: WorkspaceManager.FileNode.Leaf?, newName: String) -> Unit,
    private val isNameTaken: (candidateName: String, target: EditTarget) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class EditTarget {
        data class NewFile(val depth: Int, val defaultName: String) : EditTarget()
        data class Rename(val leaf: WorkspaceManager.FileNode.Leaf, val depth: Int) : EditTarget()
    }

    private data class Row(
        val node: WorkspaceManager.FileNode?,
        val depth: Int,
        val editTarget: EditTarget? = null
    )

    private var rootNodes: List<WorkspaceManager.FileNode> = emptyList()
    private val collapsedKeys = mutableSetOf<String>()
    private var flattenedRows: List<Row> = emptyList()
    private var currentEditTarget: EditTarget? = null

    private var activeEditText: EditText? = null

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_LEAF = 1
        private const val TYPE_EDIT = 2
    }

    fun submitTree(nodes: List<WorkspaceManager.FileNode>) {
        rootNodes = nodes
        recomputeRows()
    }

    fun startCreatingNewFile(defaultName: String) {
        currentEditTarget = EditTarget.NewFile(depth = 0, defaultName = defaultName)
        recomputeRows()
    }

    fun startRenaming(leaf: WorkspaceManager.FileNode.Leaf, depth: Int) {
        currentEditTarget = EditTarget.Rename(leaf, depth)
        recomputeRows()
    }

    fun cancelEditing() {
        currentEditTarget = null
        activeEditText = null
        recomputeRows()
    }

    fun confirmCurrentEditIfValid(): Boolean {
        val editText = activeEditText ?: return false
        val target = currentEditTarget ?: return false
        val text = editText.text.toString()
        val reason = validationReason(text, target)
        if (reason == null) {
            val existing = (target as? EditTarget.Rename)?.leaf
            onNameConfirmed(existing, text)
            return true
        }
        shakeAndVibrateInvalid(editText)
        return false
    }

    private fun recomputeRows() {
        val rows = mutableListOf<Row>()

        (currentEditTarget as? EditTarget.NewFile)?.let { rows.add(Row(null, it.depth, it)) }
        val renameTarget = currentEditTarget as? EditTarget.Rename

        fun visit(nodes: List<WorkspaceManager.FileNode>, depth: Int) {
            for (node in nodes) {
                if (renameTarget != null && node === renameTarget.leaf) {
                    rows.add(Row(node, depth, renameTarget))
                } else {
                    rows.add(Row(node, depth))
                    if (node is WorkspaceManager.FileNode.Folder &&
                        node.doc.uri.toString() !in collapsedKeys
                    ) {
                        visit(node.children, depth + 1)
                    }
                }
            }
        }
        visit(rootNodes, 0)
        flattenedRows = rows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        val row = flattenedRows[position]
        return when {
            row.editTarget != null -> TYPE_EDIT
            row.node is WorkspaceManager.FileNode.Folder -> TYPE_FOLDER
            else -> TYPE_LEAF
        }
    }

    inner class NodeViewHolder(val binding: ItemFileNodeBinding) : RecyclerView.ViewHolder(binding.root)
    inner class EditViewHolder(val binding: ItemFileNodeEditBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_EDIT) {
            EditViewHolder(ItemFileNodeEditBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            NodeViewHolder(ItemFileNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = flattenedRows[position]
        val density = holder.itemView.resources.displayMetrics.density
        val indentPx = (12 * row.depth * density).toInt()

        when (holder) {
            is EditViewHolder -> {
                holder.binding.root.setPadding(indentPx, 0, 0, 0)
                bindEditRow(holder, row.editTarget!!)
            }
            is NodeViewHolder -> {
                holder.binding.root.setPadding(indentPx, 0, 0, 0)
                when (val node = row.node) {
                    is WorkspaceManager.FileNode.Folder -> {
                        val key = node.doc.uri.toString()
                        val collapsed = key in collapsedKeys
                        val arrow = if (collapsed) ">" else "\u2228"
                        holder.binding.tvNodeName.text = "$arrow ${node.name}"
                        holder.binding.ivNodeIcon.visibility = View.GONE
                        holder.binding.root.setOnLongClickListener(null)
                        holder.binding.root.setOnClickListener {
                            if (collapsed) collapsedKeys.remove(key) else collapsedKeys.add(key)
                            recomputeRows()
                        }
                    }
                    is WorkspaceManager.FileNode.Leaf -> {
                        holder.binding.tvNodeName.text = node.name
                        holder.binding.ivNodeIcon.visibility = View.VISIBLE
                        holder.binding.root.setOnClickListener { onFileClick(node) }
                        holder.binding.root.setOnLongClickListener {
                            onFileLongPress(node, row.depth, holder.binding.root)
                            true
                        }
                    }
                    null -> { /* unreachable for NodeViewHolder */ }
                }
            }
        }
    }

    /**
     * FIX: split the old single "isValidExtension" check into three
     * distinct, accurate reasons instead of one generic message covering
     * all of them:
     * - completely empty box
     * - has ".py" but nothing before it (no actual filename)
     * - has real text but wrong/missing extension
     * - name collision (unchanged from before)
     */
    private fun validationReason(name: String, target: EditTarget): String? {
        if (name.isEmpty()) return "Type a file name"

        val nameBeforeExtension = if (name.endsWith(".py")) name.removeSuffix(".py") else name
        if (nameBeforeExtension.isEmpty()) return "Type a name before .py"

        if (!name.endsWith(".py")) return "Only .py files are supported"

        if (isNameTaken(name, target)) return "A file with this name already exists"
        return null
    }

    private fun bindEditRow(holder: EditViewHolder, target: EditTarget) {
        val editText = holder.binding.editFileName
        val errorLabel = holder.binding.tvNameError
        activeEditText = editText

        editText.setOnEditorActionListener(null)
        editText.translationX = 0f

        val initialName = when (target) {
            is EditTarget.NewFile -> target.defaultName
            is EditTarget.Rename -> target.leaf.name
        }
        editText.setText(initialName)
        val selectEnd = initialName.lastIndexOf(".py").let { if (it == -1) initialName.length else it }
        editText.setSelection(0, selectEnd)
        applyValidationUi(editText, errorLabel, validationReason(initialName, target))

        editText.tag?.let { (it as? TextWatcher)?.let { w -> editText.removeTextChangedListener(w) } }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyValidationUi(editText, errorLabel, validationReason(s?.toString() ?: "", target))
            }
        }
        editText.tag = watcher
        editText.addTextChangedListener(watcher)

        editText.setOnEditorActionListener { v, actionId, event ->
            val isEnterAction = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            if (isEnterAction) {
                val text = v.text.toString()
                val reason = validationReason(text, target)
                if (reason == null) {
                    val existing = (target as? EditTarget.Rename)?.leaf
                    onNameConfirmed(existing, text)
                } else {
                    shakeAndVibrateInvalid(editText)
                }
                true
            } else {
                false
            }
        }

        editText.requestFocus()
        editText.post {
            val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun applyValidationUi(editText: EditText, errorLabel: android.widget.TextView, reason: String?) {
        val valid = reason == null
        editText.setBackgroundResource(
            if (valid) R.drawable.edit_row_border_normal else R.drawable.edit_row_border_invalid
        )
        errorLabel.text = reason ?: ""
        errorLabel.visibility = if (valid) View.GONE else View.VISIBLE
    }

    private fun shakeAndVibrateInvalid(editText: EditText) {
        val amplitude = (8 * editText.context.resources.displayMetrics.density)
        val animator = ObjectAnimator.ofFloat(
            editText, "translationX",
            0f, amplitude, -amplitude, amplitude, -amplitude, amplitude / 2f, -amplitude / 2f, 0f
        )
        animator.duration = 500
        animator.start()
        vibrate500(editText.context)
    }

    private fun vibrate500(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) { /* haptics are a nice-to-have */ }
    }

    override fun getItemCount(): Int = flattenedRows.size
}