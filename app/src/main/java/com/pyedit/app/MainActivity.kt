package com.pyedit.app

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.DialogEditorSettingsBinding
import com.pyedit.app.databinding.DialogSaveAsBinding
import com.pyedit.app.databinding.DialogUnsavedExitBinding
import com.pyedit.app.databinding.PopupMenuBinding
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: CodeEditor
    private lateinit var editorSettings: EditorSettings
    private lateinit var workspace: WorkspaceManager
    private lateinit var recentStore: RecentFilesStore
    private lateinit var fileTreeAdapter: FileTreeAdapter
    private lateinit var recentFilesAdapter: RecentFilesAdapter

    private var currentFile: File? = null
    private var isDirty: Boolean = false
    private var suppressDirtyTracking: Boolean = false

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null
    private var autosaveEnabled = false
    private val autosaveDelayMs = 1200L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editorSettings = EditorSettings(this)
        workspace = WorkspaceManager(this)
        recentStore = RecentFilesStore(this)

        sizeDrawerToScreenWidth()
        setupTopBar()
        setupEditor()
        setupPythonToolbar()
        setupKeyboardAwareToolbar()
        setupDrawerLists()

        lifecycleScope.launch {
            autosaveEnabled = recentStore.isAutosaveEnabled()
            restoreLastSessionOrDefault()
        }
    }

    private fun sizeDrawerToScreenWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.95f).toInt()
        val params = binding.drawerContent.root.layoutParams
        params.width = drawerWidth
        binding.drawerContent.root.layoutParams = params
    }

    private fun setupTopBar() {
        binding.btnHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.START)
        }
        binding.btnMenu.setOnClickListener { anchor -> showThreeDotMenu(anchor) }
    }

    private fun showThreeDotMenu(anchor: View) {
        val popupBinding = PopupMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f

        popupBinding.checkboxAutosave.isChecked = autosaveEnabled
        popupBinding.checkboxAutosave.setOnCheckedChangeListener { _, checked ->
            autosaveEnabled = checked
            lifecycleScope.launch { recentStore.setAutosaveEnabled(checked) }
        }

        popupBinding.menuItemSave.setOnClickListener {
            popup.dismiss()
            saveCurrentFile()
        }
        popupBinding.menuItemSaveAs.setOnClickListener {
            popup.dismiss()
            showSaveAsDialog()
        }

        val dismissOnly = View.OnClickListener { popup.dismiss() }
        popupBinding.menuItemFind.setOnClickListener(dismissOnly)
        popupBinding.menuItemReplace.setOnClickListener(dismissOnly)
        popupBinding.menuItemGoToLine.setOnClickListener(dismissOnly)
        popupBinding.menuItemCompile.setOnClickListener(dismissOnly)
        popupBinding.menuItemEditorSettings.setOnClickListener {
            popup.dismiss()
            showEditorSettingsDialog()
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun showEditorSettingsDialog() {
        val dialogBinding = DialogEditorSettingsBinding.inflate(layoutInflater)

        dialogBinding.seekFontSize.progress = (editorSettings.fontSize - 10f).toInt()
        dialogBinding.seekTabSize.progress = editorSettings.tabSize - 2

        dialogBinding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = (10 + progress).toFloat()
                editorSettings.fontSize = newSize
                applyFontSize(newSize)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dialogBinding.seekTabSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newTabSize = 2 + progress
                editorSettings.tabSize = newTabSize
                applyTabSize(newTabSize)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Editor Settings")
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun applyFontSize(size: Float) {
        try {
            editor.setTextSize(size)
        } catch (t: Throwable) {
            showCrashDiagnostic("Font size change failed", t)
        }
    }

    private fun applyTabSize(size: Int) {
        try {
            editor.tabWidth = size
        } catch (t: Throwable) {
            showCrashDiagnostic("Tab size change failed", t)
        }
    }

    private fun setupEditor() {
        editor = CodeEditor(this)
        editor.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.editorContainer.addView(editor)

        editor.isWordwrap = false

        try {
            PythonLanguage.attach(this, editor)
        } catch (t: Throwable) {
            showCrashDiagnostic("Color scheme failed", t)
        }

        try {
            PythonEditingBehavior(editor).attach()
        } catch (t: Throwable) {
            showCrashDiagnostic("Smart editing setup failed", t)
        }

        setupDirtyTracking()

        applyFontSize(editorSettings.fontSize)
        applyTabSize(editorSettings.tabSize)
        applyVisualPolish()
    }

    /**
     * New for Phase 2: tracks unsaved changes (spec §61-66) and drives the
     * debounced autosave + crash-recovery timers. A second, independent
     * ContentListener alongside PythonEditingBehavior's — both are
     * registered on the same Content, which is expected to support
     * multiple listeners the same way most such APIs do.
     */
    private fun setupDirtyTracking() {
        editor.text.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) {}

            override fun afterInsert(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, insertedContent: CharSequence
            ) = markDirty()

            override fun afterDelete(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, deletedContent: CharSequence
            ) = markDirty()
        })
    }

    private fun markDirty() {
        if (suppressDirtyTracking) return
        if (!isDirty) {
            isDirty = true
            updateFilenameDisplay()
        }
        scheduleAutosaveAndRecovery()
    }

    private fun scheduleAutosaveAndRecovery() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val file = currentFile ?: return@Runnable
            val content = editor.text.toString()
            // Crash-safe recovery (spec §62) writes regardless of the
            // autosave setting — unsaved work must survive a crash even
            // with autosave off.
            workspace.writeRecovery(file, content)
            if (autosaveEnabled) {
                workspace.saveFile(file, content)
                workspace.clearRecovery(file)
                isDirty = false
                updateFilenameDisplay()
            }
        }
        autosaveRunnable = runnable
        autosaveHandler.postDelayed(runnable, autosaveDelayMs)
    }

    private fun updateFilenameDisplay() {
        val name = currentFile?.name ?: getString(R.string.untitled_file)
        binding.tvFilename.text = if (isDirty) "$name *" else name
    }

    private fun loadIntoEditor(content: String) {
        suppressDirtyTracking = true
        editor.setText(content)
        suppressDirtyTracking = false
    }

    private fun openFile(file: File) {
        checkUnsavedThenRun {
            val content = workspace.readFile(file)
            loadIntoEditor(content)
            currentFile = file
            isDirty = false
            updateFilenameDisplay()
            lifecycleScope.launch {
                recentStore.addRecent(file.absolutePath, file.name)
                recentStore.setLastActiveFile(file.absolutePath)
            }
            binding.drawerLayout.closeDrawers()
            checkRecoveryFor(file)
        }
    }

    private fun checkUnsavedThenRun(action: () -> Unit) {
        if (!isDirty) {
            action()
            return
        }
        showUnsavedExitDialog(
            onSave = { saveCurrentFile { action() } },
            onDiscard = {
                isDirty = false
                action()
            }
        )
    }

    private fun saveCurrentFile(onDone: () -> Unit = {}) {
        val file = currentFile
        if (file == null) {
            showSaveAsDialog(onDone)
            return
        }
        workspace.saveFile(file, editor.text.toString())
        workspace.clearRecovery(file)
        isDirty = false
        updateFilenameDisplay()
        onDone()
    }

    private fun showSaveAsDialog(onDone: () -> Unit = {}) {
        val dialogBinding = DialogSaveAsBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle("Save As")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.editFilename.text.toString().trim()
                if (name.isNotEmpty()) {
                    val file = workspace.saveAs(editor.text.toString(), name)
                    currentFile = file
                    isDirty = false
                    updateFilenameDisplay()
                    lifecycleScope.launch {
                        recentStore.addRecent(file.absolutePath, file.name)
                        recentStore.setLastActiveFile(file.absolutePath)
                    }
                    refreshFileTree()
                    onDone()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Custom layout (not standard AlertDialog buttons) to match the
     * exact Cancel-left / Save+Discard-right arrangement from spec §66. */
    private fun showUnsavedExitDialog(onSave: () -> Unit, onDiscard: () -> Unit) {
        val dialogBinding = DialogUnsavedExitBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            dialog.dismiss()
            onSave()
        }
        dialogBinding.btnDiscard.setOnClickListener {
            dialog.dismiss()
            onDiscard()
        }
        dialog.show()
    }

    private fun checkRecoveryFor(file: File) {
        val recoveryContent = workspace.readRecoveryIfDifferent(file) ?: return
        AlertDialog.Builder(this)
            .setTitle("Unsaved work recovered")
            .setMessage("A previous session for ${file.name} has unsaved changes. Restore them?")
            .setPositiveButton("Restore") { _, _ ->
                loadIntoEditor(recoveryContent)
                isDirty = true
                updateFilenameDisplay()
            }
            .setNegativeButton("Discard") { _, _ ->
                workspace.clearRecovery(file)
            }
            .show()
    }

    private suspend fun restoreLastSessionOrDefault() {
        val lastPath = recentStore.getLastActiveFile()
        val file = lastPath?.let { File(it) }?.takeIf { it.exists() }
        if (file != null) {
            val content = workspace.readFile(file)
            loadIntoEditor(content)
            currentFile = file
            isDirty = false
            updateFilenameDisplay()
            checkRecoveryFor(file)
        } else {
            updateFilenameDisplay()
        }
        refreshFileTree()
    }

    private fun refreshFileTree() {
        fileTreeAdapter.submitTree(workspace.buildTree())
    }

    private fun setupDrawerLists() {
        fileTreeAdapter = FileTreeAdapter { file -> openFile(file) }
        binding.drawerContent.rvFileTree.layoutManager = LinearLayoutManager(this)
        binding.drawerContent.rvFileTree.adapter = fileTreeAdapter

        recentFilesAdapter = RecentFilesAdapter { path -> openFile(File(path)) }
        binding.drawerContent.rvRecentFiles.layoutManager = LinearLayoutManager(this)
        binding.drawerContent.rvRecentFiles.adapter = recentFilesAdapter

        lifecycleScope.launch {
            recentStore.recentFiles.collect { list ->
                recentFilesAdapter.submitList(list)
            }
        }

        binding.drawerContent.tvClearRecent.setOnClickListener {
            lifecycleScope.launch { recentStore.clearRecent() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
            binding.drawerLayout.closeDrawers()
            return
        }
        if (isDirty) {
            showUnsavedExitDialog(
                onSave = { saveCurrentFile { super.onBackPressed() } },
                onDiscard = { super.onBackPressed() }
            )
        } else {
            super.onBackPressed()
        }
    }

    private fun applyVisualPolish() {
        try {
            editor.setCursorWidth(resources.displayMetrics.density * 2.5f)
        } catch (t: Throwable) { /* confirmed to compile; defensive only */ }
    }

    private fun showCrashDiagnostic(title: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        Toast.makeText(this, "$title — editor still works. Tap to see details.", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sw.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupPythonToolbar() {
        val tb = binding.pythonToolbar
        val insert: (String) -> Unit = { s -> editor.commitText(s) }

        tb.tbUndo.setOnClickListener { if (editor.canUndo()) editor.undo() }
        tb.tbRedo.setOnClickListener { if (editor.canRedo()) editor.redo() }
        tb.tbIndent.setOnClickListener { insert("    ") }
        tb.tbOutdent.setOnClickListener { removeOneIndentLevel() }

        tb.tbParenOpen.setOnClickListener { insert("(") }
        tb.tbParenClose.setOnClickListener { insert(")") }
        tb.tbBracketOpen.setOnClickListener { insert("[") }
        tb.tbBracketClose.setOnClickListener { insert("]") }
        tb.tbBraceOpen.setOnClickListener { insert("{") }
        tb.tbBraceClose.setOnClickListener { insert("}") }
        tb.tbSquote.setOnClickListener { insert("'") }
        tb.tbDquote.setOnClickListener { insert("\"") }
        tb.tbColon.setOnClickListener { insert(":") }
        tb.tbUnderscore.setOnClickListener { insert("_") }
        tb.tbHash.setOnClickListener { insert("#") }
        tb.tbEq.setOnClickListener { insert("=") }
        tb.tbEqeq.setOnClickListener { insert("==") }
        tb.tbNeq.setOnClickListener { insert("!=") }
        tb.tbLt.setOnClickListener { insert("<") }
        tb.tbGt.setOnClickListener { insert(">") }
        tb.tbLe.setOnClickListener { insert("<=") }
        tb.tbGe.setOnClickListener { insert(">=") }
        tb.tbPlus.setOnClickListener { insert("+") }
        tb.tbMinus.setOnClickListener { insert("-") }
        tb.tbMul.setOnClickListener { insert("*") }
        tb.tbDiv.setOnClickListener { insert("/") }
        tb.tbFloordiv.setOnClickListener { insert("//") }
        tb.tbMod.setOnClickListener { insert("%") }
        tb.tbPow.setOnClickListener { insert("**") }
        tb.tbArrow.setOnClickListener { insert("->") }
        tb.tbAt.setOnClickListener { insert("@") }
        tb.tbEllipsis.setOnClickListener { insert("...") }
    }

    private fun removeOneIndentLevel() {
        val line = editor.cursor.leftLine
        val col = editor.cursor.leftColumn
        val lineText = editor.text.getLineString(line)
        val prefix = lineText.substring(0, col)
        val trailingSpaces = prefix.takeLastWhile { it == ' ' }.length
        val toRemove = minOf(trailingSpaces, 4)
        if (toRemove > 0) {
            editor.text.delete(line, col - toRemove, line, col)
        }
    }

    private fun setupKeyboardAwareToolbar() {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.rootView.height
            if (screenHeight == 0) return@addOnGlobalLayoutListener
            val heightDiff = screenHeight - visibleFrame.bottom
            val keyboardVisible = heightDiff > screenHeight * 0.15
            binding.pythonToolbar.root.visibility = if (keyboardVisible) View.VISIBLE else View.GONE
        }
    }
}