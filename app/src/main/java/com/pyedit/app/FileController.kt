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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.DialogSaveAsBinding
import com.pyedit.app.databinding.DialogUnsavedExitBinding
import com.pyedit.app.databinding.PopupFileActionsBinding
import kotlinx.coroutines.launch

class FileController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val workspace: WorkspaceManager,
    private val recentStore: RecentFilesStore,
    private val editorController: EditorController,
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

    private lateinit var fileTreeAdapter: FileTreeAdapter
    private lateinit var mainBrowseAdapter: FileTreeAdapter
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private lateinit var mainRecentFilesAdapter: RecentFilesAdapter

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null
    private val autosaveDelayMs = 1200L

    fun setup() {
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

        binding.drawerContent.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                fileTreeAdapter.confirmCurrentEditIfValid()
                hideKeyboard()
            }
            false
        }
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
        restoreLastSessionOrDefault()
    }

    /**
     * New for Phase 3 item 1: called from MainActivity.onPause() — saves
     * the CURRENT cursor position for whatever file is open right now,
     * so a process kill while backgrounded doesn't lose it. Deliberately
     * NOT tied to autosave's debounce timer — this must fire immediately
     * on pause, since there's no guarantee the debounced timer will ever
     * get to run before the process dies.
     */
    fun persistCurrentCursorPosition() {
        val doc = currentFileDoc ?: return
        val (line, column) = editorController.getCursorPosition()
        activity.lifecycleScope.launch {
            recentStore.setCursorPosition(doc.uri.toString(), line, column)
        }
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
            val content = editorController.getText()
            workspace.writeRecovery(doc, content)
            if (autosaveEnabled) {
                workspace.saveFile(doc, content)
                workspace.clearRecovery(doc)
                isDirty = false
                notifyState()
            }
        }
        autosaveRunnable = runnable
        autosaveHandler.postDelayed(runnable, autosaveDelayMs)
    }

    private fun notifyState() {
        val name = currentFileDoc?.name ?: activity.getString(R.string.untitled_file)
        val display = if (isDirty) "$name *" else name
        onStateChanged(hasFileOpen, isDirty, display)
    }

    fun getDisplayFileName(): String = currentFileDoc?.name ?: "untitled.py"

    fun onFolderPicked(uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        rootTreeUri = uri
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
        binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
        binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
    }

    fun showEmptyState() {
        binding.mainWorkspaceView.root.visibility = View.VISIBLE
        binding.editorContainer.visibility = View.GONE
        binding.outputPanel.root.visibility = View.GONE
        if (rootTreeUri == null) {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.VISIBLE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.GONE
        } else {
            binding.mainWorkspaceView.groupEmptyState.visibility = View.GONE
            binding.mainWorkspaceView.groupFolderBrowse.visibility = View.VISIBLE
        }
    }

    fun showEditorState() {
        binding.mainWorkspaceView.root.visibility = View.GONE
        binding.editorContainer.visibility = View.VISIBLE
        binding.outputPanel.root.visibility = View.VISIBLE
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

        mainRecentFilesAdapter = RecentFilesAdapter { uriString ->
            DocumentFile.fromSingleUri(activity, Uri.parse(uriString))?.let { openFile(it) }
        }
        binding.mainWorkspaceView.rvRecentFilesMain.layoutManager = LinearLayoutManager(activity)
        binding.mainWorkspaceView.rvRecentFilesMain.adapter = mainRecentFilesAdapter

        activity.lifecycleScope.launch {
            recentStore.recentFiles.collect { list -> mainRecentFilesAdapter.submitList(list) }
        }
    }

    fun bindOpenButtons(launchOpenFolder: () -> Unit, launchOpenFile: () -> Unit) {
        binding.mainWorkspaceView.btnOpenFolder.setOnClickListener { launchOpenFolder() }
        binding.mainWorkspaceView.btnOpenFile.setOnClickListener { launchOpenFile() }
    }

    /**
     * Updated for Phase 3 item 1: after loading the file's text, attempts
     * to restore a previously-saved cursor position for THIS specific
     * file (by URI) — not just for whatever was last-active overall.
     * Falls back to leaving the cursor at its default (start of file)
     * if nothing was ever saved for this file.
     */
    fun openFile(doc: DocumentFile) {
        checkUnsavedThenRun {
            onFileOpened()

            val content = workspace.readFile(doc)
            editorController.loadText(content)
            currentFileDoc = doc
            isDirty = false
            hasFileOpen = true
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

    fun saveCurrentFile(onDone: () -> Unit = {}) {
        val doc = currentFileDoc
        if (doc == null) { showSaveAsDialog(onDone); return }
        workspace.saveFile(doc, editorController.getText())
        workspace.clearRecovery(doc)
        isDirty = false
        notifyState()
        onDone()
    }

    fun showSaveAsDialog(onDone: () -> Unit = {}) {
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
                        workspace.saveFile(newDoc, editorController.getText())
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
                onDiscard = { proceed() }
            )
        } else {
            proceed()
        }
    }

    private fun checkRecoveryFor(doc: DocumentFile) {
        val recoveryContent = workspace.readRecoveryIfDifferent(doc) ?: return
        AlertDialog.Builder(activity)
            .setTitle("Unsaved work recovered")
            .setMessage("A previous session for ${doc.name} has unsaved changes. Restore them?")
            .setPositiveButton("Restore") { _, _ ->
                editorController.loadText(recoveryContent)
                isDirty = true
                notifyState()
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
        val doc = lastUriString?.let { DocumentFile.fromSingleUri(activity, Uri.parse(it)) }
            ?.takeIf { it.exists() }

        if (doc != null) {
            val content = workspace.readFile(doc)
            editorController.loadText(content)
            currentFileDoc = doc
            isDirty = false
            hasFileOpen = true
            notifyState()
            showEditorState()

            val savedPosition = recentStore.getCursorPosition(doc.uri.toString())
            if (savedPosition != null) {
                editorController.restoreCursorPosition(savedPosition.first, savedPosition.second)
            }

            checkRecoveryFor(doc)
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
            isNameTaken = { candidateName, target -> isNameTaken(candidateName, target) }
        )
        binding.drawerContent.rvFileTree.layoutManager = LinearLayoutManager(activity)
        binding.drawerContent.rvFileTree.adapter = fileTreeAdapter

        recentFilesAdapter = RecentFilesAdapter { uriString ->
            DocumentFile.fromSingleUri(activity, Uri.parse(uriString))?.let { openFile(it) }
        }
        binding.drawerContent.rvRecentFiles.layoutManager = LinearLayoutManager(activity)
        binding.drawerContent.rvRecentFiles.adapter = recentFilesAdapter

        activity.lifecycleScope.launch {
            recentStore.recentFiles.collect { list -> recentFilesAdapter.submitList(list) }
        }

        binding.drawerContent.tvClearRecent.setOnClickListener {
            activity.lifecycleScope.launch { recentStore.clearRecent() }
        }
    }

    private fun isNameTaken(candidateName: String, target: FileTreeAdapter.EditTarget): Boolean {
        val fileName = if (candidateName.endsWith(".py")) candidateName else "$candidateName.py"
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
            val normalizedNew = if (newName.endsWith(".py")) newName else "$newName.py"
            if (normalizedNew != existing.name) {
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
                    if (currentFileDoc?.uri == leaf.doc.uri) {
                        currentFileDoc = null
                        hasFileOpen = false
                        isDirty = false
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