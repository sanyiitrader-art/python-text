package com.pyedit.app

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.PopupMenuBinding
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editorController: EditorController
    private lateinit var fileController: FileController
    private lateinit var executionUiController: ExecutionUiController
    private lateinit var executionController: ExecutionController
    private lateinit var outputSheetController: OutputSheetController
    private lateinit var editorSettings: EditorSettings
    private lateinit var workspace: WorkspaceManager
    private lateinit var recentStore: RecentFilesStore

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { fileController.onFolderPicked(it) } }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { fileController.onSingleFilePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editorSettings = EditorSettings(this)
        workspace = WorkspaceManager(this)
        recentStore = RecentFilesStore(this)
        executionController = ExecutionController(this)

        sizeDrawerToScreenWidth()

        editorController = EditorController(this, binding, editorSettings, ::showCrashDiagnostic)
        editorController.setup()

        outputSheetController = OutputSheetController(this, binding)
        outputSheetController.setup()

        fileController = FileController(
            activity = this,
            binding = binding,
            workspace = workspace,
            recentStore = recentStore,
            editorController = editorController,
            onFileOpened = { outputSheetController.resetForNewFileIfNeeded() }
        ) { hasFileOpen, _, displayName ->
            binding.tvFilename.text = displayName
            updateActionAvailability(hasFileOpen)
        }
        fileController.setup()
        fileController.bindOpenButtons(
            launchOpenFolder = { openFolderLauncher.launch(null) },
            launchOpenFile = { openFileLauncher.launch(arrayOf("*/*")) }
        )

        executionUiController = ExecutionUiController(
            activity = this,
            binding = binding,
            executionController = executionController,
            getScriptText = { editorController.getText() },
            getFileName = { fileController.getDisplayFileName() },
            onJumpToError = { line ->
                fileController.showEditorState()
                editorController.jumpToLine(line)
            },
            onRunStateChanged = { running ->
                binding.btnRun.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_run)
            },
            isKeyboardVisible = { editorController.isKeyboardCurrentlyVisible() },
            onShowOutputPanel = { outputSheetController.onRunRequested() },
            onError = ::showCrashDiagnostic
        )
        executionUiController.setup()

        setupTopBar()
        setupOutsideTapDismiss()
        updateActionAvailability(fileController.hasFileOpen)

        lifecycleScope.launch {
            fileController.init()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executionController.teardown()
    }

    private fun sizeDrawerToScreenWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.95f).toInt()
        val params = binding.drawerContent.root.layoutParams
        params.width = drawerWidth
        binding.drawerContent.root.layoutParams = params
    }

    private fun setupOutsideTapDismiss() {
        binding.mainContentRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.mainContentRoot.windowToken, 0)
            }
            false
        }
    }

    private fun setupTopBar() {
        binding.btnHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.START)
        }
        binding.btnMenu.setOnClickListener { anchor -> showThreeDotMenu(anchor) }
        binding.btnRun.setOnClickListener {
            if (fileController.hasFileOpen) executionUiController.onRunStopClicked()
        }
    }

    private fun updateActionAvailability(hasFileOpen: Boolean) {
        binding.btnRun.isEnabled = hasFileOpen
        binding.btnRun.alpha = if (hasFileOpen) 1f else 0.35f
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

        popupBinding.menuItemOpenFile.setOnClickListener {
            popup.dismiss()
            openFileLauncher.launch(arrayOf("*/*"))
        }
        popupBinding.menuItemOpenFolder.setOnClickListener {
            popup.dismiss()
            openFolderLauncher.launch(null)
        }

        val hasFileOpen = fileController.hasFileOpen
        val items = listOf(
            popupBinding.menuItemSave, popupBinding.menuItemSaveAs,
            popupBinding.menuItemFind, popupBinding.menuItemReplace,
            popupBinding.menuItemGoToLine, popupBinding.menuItemCompile,
            popupBinding.menuItemEditorSettings
        )
        items.forEach { it.isEnabled = hasFileOpen; it.alpha = if (hasFileOpen) 1f else 0.35f }
        popupBinding.checkboxAutosave.isEnabled = hasFileOpen
        popupBinding.checkboxAutosave.alpha = if (hasFileOpen) 1f else 0.35f

        popupBinding.checkboxAutosave.isChecked = fileController.autosaveEnabled
        popupBinding.checkboxAutosave.setOnCheckedChangeListener { _, checked ->
            fileController.updateAutosaveEnabled(checked)
        }

        if (hasFileOpen) {
            popupBinding.menuItemSave.setOnClickListener { popup.dismiss(); fileController.saveCurrentFile() }
            popupBinding.menuItemSaveAs.setOnClickListener { popup.dismiss(); fileController.showSaveAsDialog() }
            popupBinding.menuItemEditorSettings.setOnClickListener { popup.dismiss(); editorController.showEditorSettingsDialog() }
            popupBinding.menuItemCompile.setOnClickListener { popup.dismiss(); executionUiController.runCompileCheck() }
            val dismissOnly = View.OnClickListener { popup.dismiss() }
            popupBinding.menuItemFind.setOnClickListener(dismissOnly)
            popupBinding.menuItemReplace.setOnClickListener(dismissOnly)
            popupBinding.menuItemGoToLine.setOnClickListener(dismissOnly)
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
            binding.drawerLayout.closeDrawers()
            return
        }
        fileController.checkUnsavedThenExit { super.onBackPressed() }
    }

    private fun showCrashDiagnostic(title: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        Toast.makeText(this, "$title — editor still works. Tap to see details.", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this).setTitle(title).setMessage(sw.toString()).setPositiveButton("OK", null).show()
    }
}