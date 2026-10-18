package com.pyedit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.DialogSaveAsBinding
import com.pyedit.app.databinding.DialogUnsavedExitBinding
import com.pyedit.app.databinding.PopupFileActionsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FileController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val workspace: WorkspaceManager,
    private val recentStore: RecentFilesStore,
    private val editorController: EditorController,
    private val web: WebProjectController,
    private val onFileOpened: () -> Unit,
    private val onStateChanged: (hasFileOpen: Boolean, isDirty: Boolean, displayName: String) -> Unit
) {
    var rootTreeUri: Uri? = null
        private set
    var currentFileDoc: DocumentFile? = null
        private set
    var isDirty: Boolean = false
        private set
    var hasFileOpen: Boolean = false
        private set
    var autosaveEnabled: Boolean = false
        private set

    private var hasCompletedFirstLaunch: Boolean = false

    private lateinit var fileTreeAdapter: FileTreeAdapter
    private lateinit var mainBrowseAdapter: FileTreeAdapter
    private lateinit var recentFilesAdapter: RecentFilesAdapter

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null
    private val autosaveDelayMs = 1200L

    private val maxVisibleRecentRows = 4

    private data class Draft(val recent: RecentFilesStore.RecentFile, val doc: DocumentFile, val content: String)

    fun setup() {
        binding.mainWorkspaceView.root.visibility = View.GONE

        editorController.onContentChanged { markDirty() }
        setupDrawerLists()
        setupMainWorkspaceView()

        binding.drawerContent.btnDrawerAdd.setOnClickListener {
            if (rootTreeUri == null) {
                Toast.makeText(activity, "Open a folder first to create a file in it", Toast.LENGTH_SHORT).show()
            } else {
                fileTreeAdapter.startCreatingNewFile(computeDefaultNewFileName())
            }
        }

        binding.drawerContent.btnNamingCancel.setOnClickListener { cancelNaming() }
        binding.drawerContent.btnNamingSave.setOnClickListener { fileTreeAdapter.confirmCurrentEditIfValid() }

        binding.drawerContent.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                cancelNaming()
                hideKeyboard()
            }
            false
        }
        binding.drawerContent.rvFileTree.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) cancelNaming()
            false
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) { cancelNaming() }
        })
    }

    private fun cancelNaming() {
        if (::fileTreeAdapter.isInitialized && fileTreeAdapter.isEditing()) {
            fileTreeAdapter.cancelEditing()
        }
    }

    private fun setNamingMode(active: Boolean) {
        binding.drawerContent.btnDrawerAdd.visibility = if (active) View.GONE else View.VISIBLE
        binding.drawerContent.tvRootFolderName.visibility = if (active) View.GONE else View.VISIBLE
        binding.drawerContent.namingActions.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun computeDefaultNewFileName(): String {
        val uri = rootTreeUri ?: return "untitled.py"
        val root = DocumentFile.fromTreeUri(activity, uri) ?: return "untitled.py"

        var candidate = "untitled.py"
        var n = 2
        while (root.findFile(candidate) != null) {
            candidate = "untitled($n).py"
            n++
        }
        return candidate
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.drawerContent.root.windowToken, 0)
    }

    suspend fun init() {
        autosaveEnabled = recentStore.isAutosaveEnabled()
        hasCompletedFirstLaunch = recentStore.hasCompletedFirstLaunch()
        restoreLastSessionOrDefault()
    }

    // --- disk / draft helpers --------------------------------------------------

    /**
     * Writes the open file. Returns an empty list on success, otherwise the
     * reasons / tab names that failed (nothing is marked saved on failure).
     */
    private fun writeOpenFileToDisk(doc: DocumentFile): List<String> {
        if (web.active) return web.saveAll()
        return try {
            workspace.saveFile(doc, editorController.getText())
            emptyList()
        } catch (e: Exception) {
            listOf(e.message ?: "write failed")
        }
    }

    private fun storeDraft(doc: DocumentFile) {
        if (web.active) web.writeDraft() else workspace.writeRecovery(doc, editorController.getText())
    }

    private fun clearDraft(doc: DocumentFile) {
        web.clearDraft(doc)
        workspace.clearRecovery(doc)
    }

    private fun parentOf(doc: DocumentFile): DocumentFile? =
        doc.parentFile
            ?: rootTreeUri?.let { web.findParent(DocumentFile.fromTreeUri(activity, it), doc.uri) }

    fun persistCurrentCursorPosition() {
        val doc = currentFileDoc ?: return
        persistDraftIfDirty()
        val (line, column) = editorController.getCursorPosition()
        activity.lifecycleScope.launch {
            recentStore.setCursorPosition(doc.uri.toString(), line, column)
        }
    }

    private fun persistDraftIfDirty() {
        val doc = currentFileDoc ?: return
        if (!isDirty || !doc.exists()) return
        if (autosaveEnabled) {
            val failed = writeOpenFileToDisk(doc)
            if (failed.isEmpty()) {
                clearDraft(doc)
                isDirty = false
                notifyState()
            } else {
                storeDraft(doc)
            }
        } else {
            storeDraft(doc)
        }
    }

    private fun markFirstLaunchCompletedIfNeeded() {
        if (hasCompletedFirstLaunch) return
        hasCompletedFirstLaunch = true
        activity.lifecycleScope.launch { recentStore.setHasCompletedFirstLaunch() }
    }

    private fun markDirty() {
        if (!isDirty) {
            isDirty = true
            notifyState()
        }
        scheduleAutosaveAndRecovery()
    }

    private fun scheduleAutosaveAndRecovery() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val doc = currentFileDoc ?: return@Runnable
            if (!doc.exists()) return@Runnable
            storeDraft(doc)
            if (autosaveEnabled) {
                val failed = writeOpenFileToDisk(doc)
                if (failed.isEmpty()) {
                    clearDraft(doc)
                    isDirty = false
                    notifyState()
                }
            }
        }
        autosaveRunnable = runnable
        autosaveHandler.postDelayed(runnable, autosaveDelayMs)
    }

    private fun notifyState() {
        val name = currentFileDoc?.name ?: activity.getString(R.string.untitled_file)
        val display = if (isDirty) "$name *" else name
        onStateChanged(hasFileOpen, isDirty, display)

        if (::recentFilesAdapter.isInitialized) {
            recentFilesAdapter.setCurrentUri(currentFileDoc?.uri?.toString())
            updateRecentListHeight()
        }
    }

    fun getDisplayFileName(): String = currentFileDoc?.name ?: "untitled.py"

    fun onFolderPicked(uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        rootTreeUri = uri
        markFirstLaunchCompletedIfNeeded()
        activity.lifecycleScope.launch { recentStore.setRootTreeUri(uri.toString()) }
        refreshFolderBrowseViews()
        showFolderBrowseState()
    }

    fun onSingleFilePicked(uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val doc = DocumentFile.fromSingleUri(activity, uri) ?: return
        openFile(doc)
    }

    private fun refreshFolderBrowseViews() {
        val uri = rootTreeUri ?: return
        val tree = workspace.buildTree(uri)
        val hasPython = workspace.hasAnyPythonFile(tree)

        binding.drawerContent.tvRootFolderName.text = workspace.folderDisplayName(uri)

        fileTreeAdapter.submitTree(tree)
        binding.drawerContent.tvDrawerNoPythonFiles.visibility = if (hasPython) View.GONE else View.VISIBLE

        mainBrowseAdapter.submitTree(tree)
        binding.mainWorkspaceView.tvNoPythonFilesMain.visibility = if (hasPython) View.GONE else View.VISIBLE
    }

    private fun showFolderBrowseState() {
        binding.mainWorkspaceView.root.visibility = View.VISIBLE
        binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
        binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
    }

    fun showEmptyState() {
        binding.mainWorkspaceView.root.visibility = View.VISIBLE
        binding.editorContainer.visibility = View.GONE
        binding.outputPanel.root.visibility = View.GONE

        if (!hasCompletedFirstLaunch) {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.VISIBLE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.GONE
        } else {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
            if (rootTreeUri != null) {
                refreshFolderBrowseViews()
            }
        }
    }

    fun showEditorState() {
        binding.mainWorkspaceView.root.visibility = View.GONE
        binding.editorContainer.visibility = View.VISIBLE
        binding.outputPanel.root.visibility = if (web.active) View.GONE else View.VISIBLE
    }

    private fun setupMainWorkspaceView() {
        mainBrowseAdapter = FileTreeAdapter(
            onFileClick = { leaf -> openFile(leaf.doc) },
            onFileLongPress = { _, _, _ -> },
            onNameConfirmed = { _, _ -> },
            isNameTaken = { _, _ -> false }
        )
        binding.mainWorkspaceView.rvFolderBrowseMain.layoutManager = LinearLayoutManager(activity)
        binding.mainWorkspaceView.rvFolderBrowseMain.adapter = mainBrowseAdapter
    }

    fun bindOpenButtons(launchOpenFolder: () -> Unit, launchOpenFile: () -> Unit) {
        binding.mainWorkspaceView.btnOpenFolder.setOnClickListener { launchOpenFolder() }
        binding.mainWorkspaceView.btnOpenFile.setOnClickListener { launchOpenFile() }
    }

    private fun loadFileIntoEditor(doc: DocumentFile) {
        val lang = Languages.forFileName(doc.name ?: "")
        currentFileDoc = doc
        hasFileOpen = true
        if (lang == Lang.WEB || lang == Lang.CSS) {
            web.enter(doc, workspace.readFile(doc), parentOf(doc))
            editorController.loadText(web.currentText())
            isDirty = web.applyDraftIfAny()
        } else {
            web.leave()
            val draft = workspace.readRecoveryIfDifferent(doc)
            editorController.loadText(draft ?: workspace.readFile(doc))
            isDirty = draft != null
        }
    }

    fun openFile(doc: DocumentFile) {
        if (!doc.exists()) {
            Toast.makeText(activity, "That file no longer exists", Toast.LENGTH_SHORT).show()
            refreshFolderBrowseViews()
            return
        }

        persistDraftIfDirty()
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }

        onFileOpened()
        markFirstLaunchCompletedIfNeeded()

        loadFileIntoEditor(doc)
        notifyState()
        showEditorState()
        editorController.clearErrorHighlightIfActive()

        activity.lifecycleScope.launch {
            recentStore.addRecent(doc.uri.toString(), doc.name ?: "untitled.py")
            recentStore.setLastActiveFile(doc.uri.toString())

            val savedPosition = recentStore.getCursorPosition(doc.uri.toString())
            if (savedPosition != null) {
                editorController.restoreCursorPosition(savedPosition.first, savedPosition.second)
            }
        }

        binding.drawerLayout.closeDrawers()
    }

    fun saveCurrentFile(onDone: () -> Unit = {}) {
        val doc = currentFileDoc
        if (doc == null || !doc.exists()) {
            showSaveAsDialog(onDone)
            return
        }
        val failed = writeOpenFileToDisk(doc)
        if (failed.isNotEmpty()) {
            val msg = if (web.active)
                "Couldn't save the ${failed.joinToString(" and ")} tab. Open the whole folder (Open Folder) so its file can be created."
            else
                "Couldn't save ${doc.name}: ${failed.first()}"
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            return
        }
        clearDraft(doc)
        isDirty = false
        notifyState()
        onDone()
    }

    /**
     * Save All: the open file plus every other recent file that has unsaved
     * text (plain drafts and web-project drafts). A file is only counted as
     * saved after a successful write; failures are listed, never swallowed.
     */
    fun saveAll() {
        val currentUri = currentFileDoc?.uri?.toString()
        val saved = ArrayList<String>()
        val failed = ArrayList<String>()

        currentFileDoc?.let { doc ->
            if (isDirty) {
                val name = doc.name ?: "current file"
                if (!doc.exists()) {
                    failed.add("$name (file no longer exists)")
                } else {
                    val bad = writeOpenFileToDisk(doc)
                    if (bad.isEmpty()) {
                        clearDraft(doc)
                        isDirty = false
                        notifyState()
                        saved.add(name)
                    } else {
                        failed.add("$name (${bad.joinToString(", ")})")
                    }
                }
            }
        }

        activity.lifecycleScope.launch {
            val recents = recentStore.recentFiles.first()
            for (r in recents) {
                if (r.uriString == currentUri) continue
                val doc = DocumentFile.fromSingleUri(activity, Uri.parse(r.uriString)) ?: continue
                if (!doc.exists()) continue
                val name = doc.name ?: r.name
                val lang = Languages.forFileName(name)

                if (lang == Lang.WEB || lang == Lang.CSS) {
                    val result = web.saveStoredDraft(doc, parentOf(doc)) ?: continue
                    if (result.second.isNotEmpty()) failed.add("$name (${result.second.joinToString(", ")} tab)")
                    else if (result.first) saved.add(name)
                } else {
                    val content = workspace.readRecoveryIfDifferent(doc) ?: continue
                    try {
                        workspace.saveFile(doc, content)
                        workspace.clearRecovery(doc)
                        saved.add(name)
                    } catch (e: Exception) {
                        failed.add("$name (${e.message ?: "write failed"})")
                    }
                }
            }

            when {
                failed.isNotEmpty() -> AlertDialog.Builder(activity)
                    .setTitle("Some files weren't saved")
                    .setMessage(
                        (if (saved.isNotEmpty()) "Saved: ${saved.joinToString(", ")}\n\n" else "") +
                            "Not saved (your changes are kept):\n" + failed.joinToString("\n") { "• $it" }
                    )
                    .setPositiveButton("OK", null)
                    .show()
                saved.isEmpty() -> Toast.makeText(activity, "Nothing to save", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(
                    activity,
                    "Saved ${saved.size} file${if (saved.size == 1) "" else "s"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun showSaveAsDialog(onDone: () -> Unit = {}) {
        if (web.active) {
            Toast.makeText(activity, "Save As isn't available for web projects yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = rootTreeUri
        if (uri == null) {
            Toast.makeText(activity, "Open a folder first to Save As into it", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = DialogSaveAsBinding.inflate(activity.layoutInflater)
        AlertDialog.Builder(activity)
            .setTitle("Save As")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.editFilename.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newDoc = workspace.createNewFileInTree(uri, name)
                    if (newDoc != null) {
                        try {
                            workspace.saveFile(newDoc, editorController.getText())
                        } catch (e: Exception) {
                            Toast.makeText(activity, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        currentFileDoc = newDoc
                        isDirty = false
                        hasFileOpen = true
                        notifyState()
                        activity.lifecycleScope.launch {
                            recentStore.addRecent(newDoc.uri.toString(), newDoc.name ?: name)
                            recentStore.setLastActiveFile(newDoc.uri.toString())
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
        val dialogBinding = DialogUnsavedExitBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).setView(dialogBinding.root).setCancelable(true).create()
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener { dialog.dismiss(); onSave() }
        dialogBinding.btnDiscard.setOnClickListener { dialog.dismiss(); onDiscard() }
        dialog.show()
    }

    fun checkUnsavedThenExit(proceed: () -> Unit) {
        if (isDirty) {
            showUnsavedExitDialog(
                onSave = { saveCurrentFile { proceed() } },
                onDiscard = {
                    currentFileDoc?.let { clearDraft(it) }
                    proceed()
                }
            )
        } else {
            proceed()
        }
    }

    private suspend fun restoreLastSessionOrDefault() {
        val savedRootUri = recentStore.getRootTreeUri()?.let { Uri.parse(it) }
        if (savedRootUri != null) {
            rootTreeUri = savedRootUri
            refreshFolderBrowseViews()
        }

        val lastUriString = recentStore.getLastActiveFile()
        val doc = lastUriString?.let { DocumentFile.fromSingleUri(activity, Uri.parse(it)) }
            ?.takeIf { it.exists() }

        if (doc != null) {
            loadFileIntoEditor(doc)
            notifyState()
            showEditorState()

            val savedPosition = recentStore.getCursorPosition(doc.uri.toString())
            if (savedPosition != null) {
                editorController.restoreCursorPosition(savedPosition.first, savedPosition.second)
            }
        } else {
            notifyState()
            showEmptyState()
        }
    }

    private fun setupDrawerLists() {
        fileTreeAdapter = FileTreeAdapter(
            onFileClick = { leaf -> openFile(leaf.doc) },
            onFileLongPress = { leaf, depth, anchor -> showFileActionsPopup(leaf, depth, anchor) },
            onNameConfirmed = { existing, newName -> handleNameConfirmed(existing, newName) },
            isNameTaken = { candidateName, target -> isNameTaken(candidateName, target) },
            onEditingStateChanged = { editing -> setNamingMode(editing) }
        )
        val treeRv = binding.drawerContent.rvFileTree
        treeRv.layoutManager = LinearLayoutManager(activity)
        treeRv.adapter = fileTreeAdapter

        treeRv.clipToPadding = false
        binding.drawerContent.drawerFooter.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (treeRv.paddingBottom != v.height) {
                treeRv.setPadding(treeRv.paddingLeft, treeRv.paddingTop, treeRv.paddingRight, v.height)
            }
        }

        recentFilesAdapter = RecentFilesAdapter { uriString ->
            DocumentFile.fromSingleUri(activity, Uri.parse(uriString))?.let { openFile(it) }
        }
        binding.drawerContent.rvRecentFiles.layoutManager = LinearLayoutManager(activity)
        binding.drawerContent.rvRecentFiles.adapter = recentFilesAdapter

        activity.lifecycleScope.launch {
            recentStore.recentFiles.collect { list ->
                recentFilesAdapter.submitList(list)
                updateRecentListHeight()
            }
        }

        binding.drawerContent.tvClearRecent.setOnClickListener { onClearRecentClicked() }
    }

    private fun onClearRecentClicked() {
        activity.lifecycleScope.launch {
            val currentUri = currentFileDoc?.uri?.toString()
            val recents = recentStore.recentFiles.first()

            val drafts = recents.filter { it.uriString != currentUri }.mapNotNull { r ->
                val doc = DocumentFile.fromSingleUri(activity, Uri.parse(r.uriString))
                    ?: return@mapNotNull null
                if (!doc.exists()) return@mapNotNull null
                val content = workspace.readRecoveryIfDifferent(doc) ?: return@mapNotNull null
                Draft(r, doc, content)
            }

            if (drafts.isEmpty()) {
                recentStore.clearRecent(currentUri)
                return@launch
            }

            showUnsavedExitDialog(
                onSave = {
                    val failed = ArrayList<String>()
                    for (d in drafts) {
                        try {
                            workspace.saveFile(d.doc, d.content)
                            workspace.clearRecovery(d.doc)
                        } catch (e: Exception) {
                            failed.add(d.doc.name ?: d.recent.name)
                        }
                    }
                    if (failed.isEmpty()) {
                        activity.lifecycleScope.launch { recentStore.clearRecent(currentUri) }
                    } else {
                        Toast.makeText(
                            activity,
                            "Couldn't save: ${failed.joinToString(", ")}. The Recent list was not cleared.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onDiscard = {
                    drafts.forEach { workspace.clearRecovery(it.doc) }
                    activity.lifecycleScope.launch { recentStore.clearRecent(currentUri) }
                }
            )
        }
    }

    private fun updateRecentListHeight() {
        val rv = binding.drawerContent.rvRecentFiles
        rv.post {
            val lp = rv.layoutParams
            if (recentFilesAdapter.itemCount <= maxVisibleRecentRows) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                val rowHeight = rv.getChildAt(0)?.height?.takeIf { it > 0 }
                    ?: (44 * activity.resources.displayMetrics.density).toInt()
                lp.height = rowHeight * maxVisibleRecentRows
            }
            rv.layoutParams = lp
        }
    }

    private fun isNameTaken(candidateName: String, target: FileTreeAdapter.EditTarget): Boolean {
        val fileName = if (Languages.isDiscoverable(candidateName)) candidateName else "$candidateName.py"
        val parentDoc = when (target) {
            is FileTreeAdapter.EditTarget.NewFile ->
                rootTreeUri?.let { DocumentFile.fromTreeUri(activity, it) }
            is FileTreeAdapter.EditTarget.Rename -> target.leaf.doc.parentFile
        } ?: return false

        val existing = parentDoc.findFile(fileName) ?: return false
        return when (target) {
            is FileTreeAdapter.EditTarget.NewFile -> true
            is FileTreeAdapter.EditTarget.Rename -> existing.uri != target.leaf.doc.uri
        }
    }

    private fun handleNameConfirmed(existing: WorkspaceManager.FileNode.Leaf?, newName: String) {
        val uri = rootTreeUri ?: return
        if (existing == null) {
            val newDoc = workspace.createNewFileInTree(uri, newName)
            fileTreeAdapter.cancelEditing()
            if (newDoc != null) {
                refreshFolderBrowseViews()
                openFile(newDoc)
            } else {
                Toast.makeText(activity, "Could not create file", Toast.LENGTH_SHORT).show()
                refreshFolderBrowseViews()
            }
        } else {
            if (newName != existing.name) {
                val renamed = workspace.renameFile(existing.doc, newName)
                if (!renamed) {
                    Toast.makeText(activity, "Could not rename file", Toast.LENGTH_SHORT).show()
                }
            }
            fileTreeAdapter.cancelEditing()
            refreshFolderBrowseViews()
        }
    }

    private fun showFileActionsPopup(leaf: WorkspaceManager.FileNode.Leaf, depth: Int, anchor: View) {
        val popupBinding = PopupFileActionsBinding.inflate(activity.layoutInflater)
        val popup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f

        popupBinding.actionRename.setOnClickListener {
            popup.dismiss()
            fileTreeAdapter.startRenaming(leaf, depth)
        }
        popupBinding.actionDelete.setOnClickListener {
            popup.dismiss()
            confirmDelete(leaf)
        }

        popup.showAsDropDown(anchor)
    }

    private fun confirmDelete(leaf: WorkspaceManager.FileNode.Leaf) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.confirm_delete_title))
            .setMessage(activity.getString(R.string.confirm_delete_message))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val deleted = workspace.deleteFile(leaf.doc)
                if (deleted) {
                    clearDraft(leaf.doc)
                    if (currentFileDoc?.uri == leaf.doc.uri) {
                        currentFileDoc = null
                        hasFileOpen = false
                        isDirty = false
                        web.leave()
                        notifyState()
                        showEmptyState()
                    }
                    refreshFolderBrowseViews()
                } else {
                    Toast.makeText(activity, "Could not delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun updateAutosaveEnabled(enabled: Boolean) {
        autosaveEnabled = enabled
        activity.lifecycleScope.launch { recentStore.setAutosaveEnabled(enabled) }
    }
}