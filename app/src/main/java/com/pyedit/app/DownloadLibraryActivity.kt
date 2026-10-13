package com.pyedit.app

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pyedit.app.databinding.ActivityDownloadLibraryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadLibraryBinding
    private lateinit var catalog: PackageCatalog
    private lateinit var installer: PackageInstaller
    private lateinit var adapter: PackageAdapter

    private val tabLabels = listOf("Python", "JavaScript", "TypeScript", "Lua", "Ruby", "PHP", "Web")
    private val tabViews = mutableListOf<TextView>()
    private var selectedTab = 0

    private var allNames: List<String> = emptyList()
    private var namesLoading = false
    private var namesError: String? = null
    private var results: List<String> = emptyList()
    private var lookingUp = false
    private var lookupJob: Job? = null

    private var installedVersions: Map<String, String> = emptyMap()
    private val installing = mutableSetOf<String>()
    private val infoRequested = mutableSetOf<String>()
    private val infoFailed = mutableSetOf<String>()

    private var keyboardSeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        catalog = PackageCatalog(this)
        installer = PackageInstaller(this)

        binding.btnBack.setOnClickListener { finish() }

        setupTabs()
        setupList()
        setupSearch()
        setupBack()

        refreshInstalled()
        loadNames()
        selectTab(0)
    }

    // --- tabs -----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setupTabs() {
        tabLabels.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(tv)
            binding.tabsContainer.addView(tv)
        }
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        tabViews.forEachIndexed { i, tv ->
            val selected = i == index
            // Selected: transparent grey, square corners (plain rectangle drawable)
            tv.setBackgroundResource(if (selected) R.drawable.tab_selected_bg else 0)
            tv.setTextColor(getColor(if (selected) R.color.text_normal else R.color.text_comment))
        }
        applyQuery()
    }

    // --- list -----------------------------------------------------------

    private fun setupList() {
        adapter = PackageAdapter(
            infoFor = { catalog.cachedInfo(it) },
            infoFailed = { infoFailed.contains(PackageCatalog.normalize(it)) },
            installedVersion = { installedVersions[PackageCatalog.normalize(it)] },
            isInstalling = { installing.contains(PackageCatalog.normalize(it)) },
            onNeedInfo = { requestInfo(it) },
            onInstall = { install(it) }
        )
        binding.rvPackages.layoutManager = LinearLayoutManager(this)
        binding.rvPackages.adapter = adapter
    }

    private fun requestInfo(name: String) {
        val key = PackageCatalog.normalize(name)
        if (!infoRequested.add(key)) return
        lifecycleScope.launch {
            try {
                if (catalog.info(name) == null) infoFailed.add(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                infoFailed.add(key)
            }
            adapter.refresh(name)
        }
    }

    private fun loadNames(force: Boolean = false) {
        namesLoading = true
        namesError = null
        render()
        lifecycleScope.launch {
            try {
                allNames = catalog.loadNames(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                namesError = e.message ?: "Network error"
            }
            namesLoading = false
            applyQuery()
        }
    }

    // --- search ---------------------------------------------------------

    private fun setupSearch() {
        val edit = binding.editSearch
        setSearchActive(false)

        edit.setOnFocusChangeListener { _, hasFocus ->
            setSearchActive(hasFocus)
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                edit.post { imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
            }
        }

        // Filtering happens on every keystroke, not on the icon tap.
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyQuery() }
        })

        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                endSearchEditing()
                true
            } else false
        }

        binding.btnSearchIcon.setOnClickListener { endSearchEditing() }

        // If the keyboard is dismissed some other way (system Back / swipe),
        // return the box to its inactive state too.
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            binding.root.getWindowVisibleDisplayFrame(r)
            val screenH = binding.root.rootView.height
            if (screenH == 0) return@addOnGlobalLayoutListener
            val visible = screenH - r.bottom > screenH * 0.15
            if (visible) {
                keyboardSeen = true
            } else if (keyboardSeen) {
                keyboardSeen = false
                if (binding.editSearch.hasFocus()) endSearchEditing()
            }
        }
    }

    private fun setSearchActive(active: Boolean) {
        binding.editSearch.alpha = if (active) 1f else 0.5f
        binding.editSearch.isCursorVisible = active
    }

    /** Ends editing but keeps the typed text and the filtered results. */
    private fun endSearchEditing() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
        binding.editSearch.clearFocus()
        binding.root.requestFocus() // root is focusable-in-touch-mode, so focus doesn't bounce back
        setSearchActive(false)
    }

    private fun applyQuery() {
        if (selectedTab != 0) { render(); return }

        val q = binding.editSearch.text.toString().trim()
        results = catalog.search(q, allNames)
        adapter.submit(results)

        lookupJob?.cancel()
        lookingUp = false
        if (q.isNotEmpty() && catalog.looksLikeName(q)) {
            lookingUp = true
            lookupJob = lifecycleScope.launch {
                try {
                    delay(450) // be polite to PyPI while typing
                    val found = catalog.info(q)
                    if (found != null) {
                        val key = PackageCatalog.normalize(found.name)
                        if (results.none { PackageCatalog.normalize(it) == key }) {
                            results = listOf(found.name) + results
                            adapter.submit(results)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // exact lookup is best-effort; the local list result stands
                }
                lookingUp = false
                render()
            }
        }
        render()
    }

    // --- states ---------------------------------------------------------

    private fun render() {
        val b = binding
        b.progress.visibility = View.GONE
        b.tvRetry.visibility = View.GONE
        b.tvStatus.visibility = View.GONE
        b.rvPackages.visibility = View.GONE

        if (selectedTab != 0) {
            b.tvStatus.text = "Not Yet"
            b.tvStatus.visibility = View.VISIBLE
            return
        }

        when {
            namesLoading || (lookingUp && results.isEmpty()) -> b.progress.visibility = View.VISIBLE
            namesError != null && results.isEmpty() -> {
                b.tvStatus.text = "Couldn't load the package list\n$namesError"
                b.tvStatus.visibility = View.VISIBLE
                b.tvRetry.visibility = View.VISIBLE
                b.tvRetry.setOnClickListener { loadNames(true) }
            }
            results.isEmpty() -> {
                b.tvStatus.text = "No packages found"
                b.tvStatus.visibility = View.VISIBLE
            }
            else -> b.rvPackages.visibility = View.VISIBLE
        }
    }

    // --- install --------------------------------------------------------

    private fun refreshInstalled() {
        lifecycleScope.launch {
            try {
                installedVersions = withContext(Dispatchers.IO) { installer.installedVersions() }
                adapter.notifyDataSetChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { /* leave the map empty */ }
        }
    }

    private fun install(name: String) {
        val key = PackageCatalog.normalize(name)
        if (!installing.add(key)) return // blocks duplicate attempts
        adapter.refresh(name)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { installer.install(name) }
            try {
                installedVersions = withContext(Dispatchers.IO) { installer.installedVersions() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { /* keep previous map */ }
            installing.remove(key)
            adapter.refresh(name)

            if (result.ok) {
                Toast.makeText(this@DownloadLibraryActivity, result.message, Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this@DownloadLibraryActivity)
                    .setTitle("Install failed")
                    .setMessage(result.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // --- back -----------------------------------------------------------

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.editSearch.hasFocus()) {
                    endSearchEditing()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}