package com.pyedit.app

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: CodeEditor
    private lateinit var editorSettings: EditorSettings
    private lateinit var workspace: WorkspaceManager
    private lateinit var recentStore: RecentFilesStore
    private lateinit var fileTreeAdapter: FileTreeAdapter
    private lateinit var mainBrowseAdapter: FileTreeAdapter
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private lateinit var mainRecentFilesAdapter: RecentFilesAdapter

    private var pythonEditingBehavior: PythonEditingBehavior? = null

    private var rootTreeUri: Uri? = null
    private var currentFileDoc: DocumentFile? = null
    private var isDirty: Boolean = false
    private var suppressDirtyTracking: Boolean = false
    private var hasFileOpen: Boolean = false

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null
    private var autosaveEnabled = false
    private val autosaveDelayMs = 1200L

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onFolderPicked(it) } }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onSingleFilePicked(it) } }

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
        setupMainWorkspaceView()
        updateActionAvailability()

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

        val items = listOf(
            popupBinding.menuItemSave, popupBinding.menuItemSaveAs,
            popupBinding.menuItemFind, popupBinding.menuItemReplace,
            popupBinding.menuItemGoToLine, popupBinding.menuItemCompile,
            popupBinding.menuItemEditorSettings
        )
        items.forEach { it.isEnabled = hasFileOpen; it.alpha = if (hasFileOpen) 1f else 0.35f }
        popupBinding.checkboxAutosave.isEnabled = hasFileOpen
        popupBinding.checkboxAutosave.alpha = if (hasFileOpen) 1f else 0.35f

        popupBinding.checkboxAutosave.isChecked = autosaveEnabled
        popupBinding.checkboxAutosave.setOnCheckedChangeListener { _, checked ->
            autosaveEnabled = checked
            lifecycleScope.launch { recentStore.setAutosaveEnabled(checked) }
        }

        if (hasFileOpen) {
            popupBinding.menuItemSave.setOnClickListener { popup.dismiss(); saveCurrentFile() }
            popupBinding.menuItemSaveAs.setOnClickListener { popup.dismiss(); showSaveAsDialog() }
            popupBinding.menuItemEditorSettings.setOnClickListener { popup.dismiss(); showEditorSettingsDialog() }
            val dismissOnly = View.OnClickListener { popup.dismiss() }
            popupBinding.menuItemFind.setOnClickListener(dismissOnly)
            popupBinding.menuItemReplace.setOnClickListener(dismissOnly)
            popupBinding.menuItemGoToLine.setOnClickListener(dismissOnly)
            popupBinding.menuItemCompile.setOnClickListener(dismissOnly)
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun updateActionAvailability() {
        binding.btnRun.isEnabled = hasFileOpen
        binding.btnRun.alpha = if (hasFileOpen) 1f else 0.35f
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
        try { editor.setTextSize(size) } catch (t: Throwable) { showCrashDiagnostic("Font size change failed", t) }
    }

    private fun applyTabSize(size: Int) {
        try { editor.tabWidth = size } catch (t: Throwable) { showCrashDiagnostic("Tab size change failed", t) }
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

        attachContentBehaviors()

        applyFontSize(editorSettings.fontSize)
        applyTabSize(editorSettings.tabSize)
        applyVisualPolish()
    }

    /**
     * FIX for the indentation regression: editor.setText() (used every
     * time a file is loaded) creates a NEW Content object rather than
     * mutating the existing one, which silently orphans any listener
     * attached to the old Content. Both PythonEditingBehavior and dirty-
     * tracking must be freshly (re-)attached after every load, not just
     * once at startup — this is called both here and after every
     * loadIntoEditor().
     */
    private fun attachContentBehaviors() {
        try {
            val behavior = PythonEditingBehavior(editor)
            behavior.attach()
            pythonEditingBehavior = behavior
        } catch (t: Throwable) {
            showCrashDiagnostic("Smart editing setup failed", t)
        }

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
        if (!isDirty) { isDirty = true; updateFilenameDisplay() }
        scheduleAutosaveAndRecovery()
    }

    private fun scheduleAutosaveAndRecovery() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val doc = currentFileDoc ?: return@Runnable
            val content = editor.text.toString()
            workspace.writeRecovery(doc, content)
            if (autosaveEnabled) {
                workspace.saveFile(doc, content)
                workspace.clearRecovery(doc)
                isDirty = false
                updateFilenameDisplay()
            }
        }
        autosaveRunnable = runnable
        autosaveHandler.postDelayed(runnable, autosaveDelayMs)
    }

    private fun updateFilenameDisplay() {
        val name = currentFileDoc?.name ?: getString(R.string.untitled_file)
        binding.tvFilename.text = if (isDirty) "$name *" else name
    }

    /** Re-attaches listeners after setText, per the fix above. */
    private fun loadIntoEditor(content: String) {
        suppressDirtyTracking = true
        editor.setText(content)
        attachContentBehaviors()
        suppressDirtyTracking = false
    }

    // --- Folder / file picking (SAF) --------------------------------------

    private fun onFolderPicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        rootTreeUri = uri
        lifecycleScope.launch { recentStore.setRootTreeUri(uri.toString()) }
        refreshFolderBrowseViews()
        showFolderBrowseState()
    }

    private fun onSingleFilePicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val doc = DocumentFile.fromSingleUri(this, uri) ?: return
        openFile(doc)
    }

    private fun refreshFolderBrowseViews() {
        val uri = rootTreeUri ?: return
        val tree = workspace.buildTree(uri)
        val hasPython = workspace.hasAnyPythonFile(tree)

        fileTreeAdapter.submitTree(tree)
        binding.drawerContent.tvDrawerNoPythonFiles.visibility = if (hasPython) View.GONE else View.VISIBLE

        mainBrowseAdapter.submitTree(tree)
        binding.mainWorkspaceView.tvNoPythonFilesMain.visibility = if (hasPython) View.GONE else View.VISIBLE
    }

    private fun showFolderBrowseState() {
        binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
        binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
    }

    private fun showEmptyState() {
        binding.mainWorkspaceView.root.visibility = View.VISIBLE
        binding.editorContainer.visibility = View.GONE
        if (rootTreeUri == null) {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.VISIBLE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.GONE
        } else {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
        }
    }

    private fun showEditorState() {
        binding.mainWorkspaceView.root.visibility = View.GONE
        binding.editorContainer.visibility = View.VISIBLE
    }

    private fun setupMainWorkspaceView() {
        binding.mainWorkspaceView.btnOpenFolder.setOnClickListener { openFolderLauncher.launch(null) }
        binding.mainWorkspaceView.btnOpenFile.setOnClickListener { openFileLauncher.launch(arrayOf("*/*")) }

        mainBrowseAdapter = FileTreeAdapter { leaf -> openFile(leaf.doc) }
        binding.mainWorkspaceView.rvFolderBrowseMain.layoutManager = LinearLayoutManager(this)
        binding.mainWorkspaceView.rvFolderBrowseMain.adapter = mainBrowseAdapter

        mainRecentFilesAdapter = RecentFilesAdapter { uriString ->
            DocumentFile.fromSingleUri(this, Uri.parse(uriString))?.let { openFile(it) }
        }
        binding.mainWorkspaceView.rvRecentFilesMain.layoutManager = LinearLayoutManager(this)
        binding.mainWorkspaceView.rvRecentFilesMain.adapter = mainRecentFilesAdapter

        lifecycleScope.launch {
            recentStore.recentFiles.collect { list -> mainRecentFilesAdapter.submitList(list) }
        }
    }

    // --- File open / save --------------------------------------------------

    private fun openFile(doc: DocumentFile) {
        checkUnsavedThenRun {
            val content = workspace.readFile(doc)
            loadIntoEditor(content)
            currentFileDoc = doc
            isDirty = false
            hasFileOpen = true
            updateFilenameDisplay()
            updateActionAvailability()
            showEditorState()
            lifecycleScope.launch {
                recentStore.addRecent(doc.uri.toString(), doc.name ?: "untitled.py")
                recentStore.setLastActiveFile(doc.uri.toString())
            }
            binding.drawerLayout.closeDrawers()
            checkRecoveryFor(doc)
        }
    }

    private fun checkUnsavedThenRun(action: () -> Unit) {
        if (!isDirty) { action(); return }
        showUnsavedExitDialog(
            onSave = { saveCurrentFile { action() } },
            onDiscard = { isDirty = false; action() }
        )
    }

    private fun saveCurrentFile(onDone: () -> Unit = {}) {
        val doc = currentFileDoc
        if (doc == null) { showSaveAsDialog(onDone); return }
        workspace.saveFile(doc, editor.text.toString())
        workspace.clearRecovery(doc)
        isDirty = false
        updateFilenameDisplay()
        onDone()
    }

    private fun showSaveAsDialog(onDone: () -> Unit = {}) {
        val uri = rootTreeUri
        if (uri == null) {
            Toast.makeText(this, "Open a folder first to Save As into it", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = DialogSaveAsBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle("Save As")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.editFilename.text.toString().trim()
                if (name.isNotEmpty()) {
                    val doc = workspace.createNewFileInTree(uri, name)
                    if (doc != null) {
                        workspace.saveFile(doc, editor.text.toString())
                        currentFileDoc = doc
                        isDirty = false
                        hasFileOpen = true
                        updateFilenameDisplay()
                        updateActionAvailability()
                        lifecycleScope.launch {
                            recentStore.addRecent(doc.uri.toString(), doc.name ?: name)
                            recentStore.setLastActiveFile(doc.uri.toString())
                        }
                        refreshFolderBrowseViews()
                        onDone()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnsavedExitDialog(onSave: () -> Unit, onDiscard: () -> Unit) {
        val dialogBinding = DialogUnsavedExitBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).setCancelable(true).create()
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener { dialog.dismiss(); onSave() }
        dialogBinding.btnDiscard.setOnClickListener { dialog.dismiss(); onDiscard() }
        dialog.show()
    }

    private fun checkRecoveryFor(doc: DocumentFile) {
        val recoveryContent = workspace.readRecoveryIfDifferent(doc) ?: return
        AlertDialog.Builder(this)
            .setTitle("Unsaved work recovered")
            .setMessage("A previous session for ${doc.name} has unsaved changes. Restore them?")
            .setPositiveButton("Restore") { _, _ ->
                loadIntoEditor(recoveryContent)
                isDirty = true
                updateFilenameDisplay()
            }
            .setNegativeButton("Discard") { _, _ -> workspace.clearRecovery(doc) }
            .show()
    }

    private suspend fun restoreLastSessionOrDefault() {
        val savedRootUri = recentStore.getRootTreeUri()?.let { Uri.parse(it) }
        if (savedRootUri != null) {
            rootTreeUri = savedRootUri
            refreshFolderBrowseViews()
        }

        val lastUriString = recentStore.getLastActiveFile()
        val doc = lastUriString?.let { DocumentFile.fromSingleUri(this, Uri.parse(it)) }
            ?.takeIf { it.exists() }

        if (doc != null) {
            val content = workspace.readFile(doc)
            loadIntoEditor(content)
            currentFileDoc = doc
            isDirty = false
            hasFileOpen = true
            updateFilenameDisplay()
            updateActionAvailability()
            showEditorState()
            checkRecoveryFor(doc)
        } else {
            showEmptyState()
        }
    }

    private fun setupDrawerLists() {
        fileTreeAdapter = FileTreeAdapter { leaf -> openFile(leaf.doc) }
        binding.drawerContent.rvFileTree.layoutManager = LinearLayoutManager(this)
        binding.drawerContent.rvFileTree.adapter = fileTreeAdapter

        recentFilesAdapter = RecentFilesAdapter { uriString ->
            DocumentFile.fromSingleUri(this, Uri.parse(uriString))?.let { openFile(it) }
        }
        binding.drawerContent.rvRecentFiles.layoutManager = LinearLayoutManager(this)
        binding.drawerContent.rvRecentFiles.adapter = recentFilesAdapter

        lifecycleScope.launch {
            recentStore.recentFiles.collect { list -> recentFilesAdapter.submitList(list) }
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
        try { editor.setCursorWidth(resources.displayMetrics.density * 2.5f) } catch (t: Throwable) {}
    }

    private fun showCrashDiagnostic(title: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        Toast.makeText(this, "$title — editor still works. Tap to see details.", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this).setTitle(title).setMessage(sw.toString()).setPositiveButton("OK", null).show()
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
        if (toRemove > 0) editor.text.delete(line, col - toRemove, line, col)
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