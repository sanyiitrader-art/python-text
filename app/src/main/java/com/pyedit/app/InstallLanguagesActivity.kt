package com.pyedit.app

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ActivityInstallLanguagesBinding
import com.pyedit.app.databinding.ItemLanguageBinding
import kotlinx.coroutines.launch
import java.util.Locale

class InstallLanguagesActivity : AppCompatActivity() {

    private enum class Kind { EXISTING, INCLUDED, TYPESCRIPT, WASM }

    private data class Row(
        val title: String, val badge: String, val kind: Kind, val info: String,
        val wasm: WasmRuntimes.Id? = null
    )

    // Provisional order (lower -> higher typical overhead), not a benchmark.
    private val rows = listOf(
        Row("Lua", "Lua", Kind.INCLUDED, "Included \u00B7 LuaJ 3.0.1 (Lua 5.2 dialect)"),
        Row("JavaScript", "JS", Kind.INCLUDED, "Included \u00B7 QuickJS (ES2020, CommonJS require, no Node APIs)"),
        Row("Python", "Py", Kind.EXISTING, "Already available \u00B7 CPython 3.11 (existing runtime)"),
        Row("PHP", "PHP", Kind.WASM, "PHP 8.4 (php-wasm ${WasmRuntimes.Id.PHP.version}, WebAssembly) \u00B7 no stdin", WasmRuntimes.Id.PHP),
        Row("Ruby", "Rb", Kind.WASM, "Ruby 3.3 (ruby.wasm ${WasmRuntimes.Id.RUBY.version}, WebAssembly) \u00B7 no stdin, no native gems", WasmRuntimes.Id.RUBY),
        Row("TypeScript", "TS", Kind.TYPESCRIPT, "TypeScript ${RuntimeInstaller.TS_VERSION} compiler \u00B7 runs on QuickJS"),
        Row("Web", "</>", Kind.INCLUDED, "Included \u00B7 Android WebView preview for HTML/CSS/JS")
    )

    private lateinit var adapter: LangAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityInstallLanguagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = LangAdapter()
        binding.rvLanguages.layoutManager = LinearLayoutManager(this)
        binding.rvLanguages.adapter = adapter

        RuntimeInstaller.init(applicationContext)
        RuntimeInstaller.prefetchSizes()
        WasmRuntimes.init(applicationContext)
        WasmRuntimes.prefetchSizes()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RuntimeInstaller.tsState.collect { adapter.refreshWhere { it.kind == Kind.TYPESCRIPT } } }
                launch { RuntimeInstaller.tsSizes.collect { adapter.refreshWhere { it.kind == Kind.TYPESCRIPT } } }
                for (id in WasmRuntimes.Id.values()) {
                    launch { WasmRuntimes.state(id).collect { adapter.refreshWhere { it.wasm == id } } }
                    launch { WasmRuntimes.sizes(id).collect { adapter.refreshWhere { it.wasm == id } } }
                }
            }
        }
    }

    private fun formatSize(bytes: Long?): String =
        if (bytes == null) "Unknown" else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    private inner class LangAdapter : RecyclerView.Adapter<LangAdapter.VH>() {

        inner class VH(val b: ItemLanguageBinding) : RecyclerView.ViewHolder(b.root) {
            var pulse: ObjectAnimator? = null
        }

        fun refreshWhere(pred: (Row) -> Boolean) {
            val idx = rows.indexOfFirst(pred)
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val b = h.b
            h.pulse?.cancel()
            h.pulse = null
            b.tvInfo.alpha = 1f
            b.tvInfo.setTextColor(getColor(R.color.text_comment))
            b.tvTitle.text = r.title
            b.tvBadge.text = r.badge
            b.btnAction.setOnClickListener(null)

            when (r.kind) {
                Kind.EXISTING -> { done(h); b.tvInfo.text = r.info; button(h, "Available", false) }
                Kind.INCLUDED -> { done(h); b.tvInfo.text = r.info; button(h, "Included", false) }
                Kind.TYPESCRIPT -> bindInstallable(
                    h, r, RuntimeInstaller.tsState.value, RuntimeInstaller.tsSizes.value,
                    { RuntimeInstaller.installTypeScript(applicationContext) },
                    { RuntimeInstaller.cancelDownload() }
                )
                Kind.WASM -> {
                    val id = r.wasm!!
                    bindInstallable(
                        h, r, WasmRuntimes.state(id).value, WasmRuntimes.sizes(id).value,
                        { WasmRuntimes.install(applicationContext, id) },
                        { WasmRuntimes.cancel(id) }
                    )
                }
            }
        }

        private fun done(h: VH) { h.b.ring.isIndeterminate = false; h.b.ring.setProgressCompat(100, true) }

        private fun button(h: VH, label: String, enabled: Boolean) {
            h.b.btnAction.text = label
            h.b.btnAction.isEnabled = enabled
            h.b.btnAction.alpha = if (enabled) 1f else 0.5f
        }

        private fun determinate(h: VH, p: Int) { h.b.ring.isIndeterminate = false; h.b.ring.setProgressCompat(p, true) }

        private fun bindInstallable(
            h: VH, r: Row, st: RuntimeInstaller.State, sz: RuntimeInstaller.Sizes,
            install: () -> Unit, cancel: () -> Unit
        ) {
            val b = h.b
            when (st) {
                is RuntimeInstaller.State.NotInstalled, is RuntimeInstaller.State.Cancelled -> {
                    determinate(h, 0)
                    val prefix = if (st is RuntimeInstaller.State.Cancelled) "Cancelled \u00B7 " else ""
                    b.tvInfo.text = "$prefix${r.info}\nDownload ${formatSize(sz.download)} \u00B7 Installed ${formatSize(sz.installed)}"
                    button(h, "Install", true)
                    b.btnAction.setOnClickListener { install() }
                }
                is RuntimeInstaller.State.Downloading -> {
                    if (st.total > 0) {
                        val pct = (st.done * 100 / st.total).toInt()
                        determinate(h, pct)
                        b.tvInfo.text = "Downloading $pct%"
                    } else {
                        b.ring.isIndeterminate = true
                        b.tvInfo.text = "Downloading\u2026"
                    }
                    button(h, "Cancel", true)
                    b.btnAction.setOnClickListener { cancel() }
                }
                is RuntimeInstaller.State.Installing -> {
                    b.ring.isIndeterminate = true
                    b.tvInfo.text = "Installing\u2026"
                    h.pulse = ObjectAnimator.ofFloat(b.tvInfo, "alpha", 1f, 0.4f).apply {
                        duration = 700
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        start()
                    }
                    button(h, "Installing\u2026", false)
                }
                is RuntimeInstaller.State.Installed -> {
                    determinate(h, 100)
                    b.tvInfo.text = "\u2713 Installed \u00B7 ${r.title} ${st.version}"
                    b.tvInfo.setTextColor(getColor(R.color.success_color))
                    button(h, "Installed", false)
                }
                is RuntimeInstaller.State.Failed -> {
                    determinate(h, 0)
                    b.tvInfo.text = "Failed: ${st.message}"
                    b.tvInfo.setTextColor(getColor(R.color.error_color))
                    button(h, "Retry", true)
                    b.btnAction.setOnClickListener { install() }
                }
            }
        }
    }
}