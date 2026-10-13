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

    private enum class Kind { EXISTING, INCLUDED, TYPESCRIPT, UNAVAILABLE }

    private data class Row(val title: String, val badge: String, val kind: Kind, val info: String)

    // Provisional order (lower -> higher typical overhead), not a benchmark.
    private val rows = listOf(
        Row("Lua", "Lua", Kind.INCLUDED, "Included \u00B7 LuaJ 3.0.1 (Lua 5.2 dialect)"),
        Row("JavaScript", "JS", Kind.INCLUDED, "Included \u00B7 QuickJS (ES2020, no Node APIs, no ES modules)"),
        Row("Python", "Py", Kind.EXISTING, "Already available \u00B7 CPython 3.11 (existing runtime)"),
        Row("PHP", "PHP", Kind.UNAVAILABLE, "Unavailable \u00B7 no Android PHP runtime can be run from app storage"),
        Row("Ruby", "Rb", Kind.UNAVAILABLE, "Unavailable \u00B7 no Android Ruby runtime can be run from app storage"),
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RuntimeInstaller.tsState.collect { adapter.refreshTypeScript() } }
                launch { RuntimeInstaller.tsSizes.collect { adapter.refreshTypeScript() } }
            }
        }
    }

    private fun formatSize(bytes: Long?): String =
        if (bytes == null) "Unknown" else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    private inner class LangAdapter : RecyclerView.Adapter<LangAdapter.VH>() {

        inner class VH(val b: ItemLanguageBinding) : RecyclerView.ViewHolder(b.root) {
            var pulse: ObjectAnimator? = null
        }

        fun refreshTypeScript() {
            val idx = rows.indexOfFirst { it.kind == Kind.TYPESCRIPT }
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

            fun determinate(p: Int) {
                b.ring.isIndeterminate = false
                b.ring.setProgressCompat(p, true)
            }
            fun button(label: String, enabled: Boolean) {
                b.btnAction.text = label
                b.btnAction.isEnabled = enabled
                b.btnAction.alpha = if (enabled) 1f else 0.5f
            }

            when (r.kind) {
                Kind.EXISTING -> { determinate(100); b.tvInfo.text = r.info; button("Available", false) }
                Kind.INCLUDED -> { determinate(100); b.tvInfo.text = r.info; button("Included", false) }
                Kind.UNAVAILABLE -> { determinate(0); b.tvInfo.text = r.info; button("Unavailable", false) }
                Kind.TYPESCRIPT -> bindTypeScript(h, r)
            }
        }

        private fun bindTypeScript(h: VH, r: Row) {
            val b = h.b
            val sizes = RuntimeInstaller.tsSizes.value
            fun determinate(p: Int) { b.ring.isIndeterminate = false; b.ring.setProgressCompat(p, true) }
            fun button(label: String, enabled: Boolean) {
                b.btnAction.text = label
                b.btnAction.isEnabled = enabled
                b.btnAction.alpha = if (enabled) 1f else 0.5f
            }

            when (val s = RuntimeInstaller.tsState.value) {
                is RuntimeInstaller.State.NotInstalled, is RuntimeInstaller.State.Cancelled -> {
                    determinate(0)
                    val prefix = if (s is RuntimeInstaller.State.Cancelled) "Cancelled \u00B7 " else ""
                    b.tvInfo.text = "$prefix${r.info}\nDownload ${formatSize(sizes.download)} \u00B7 Installed ${formatSize(sizes.installed)}"
                    button("Install", true)
                    b.btnAction.setOnClickListener { RuntimeInstaller.installTypeScript(applicationContext) }
                }
                is RuntimeInstaller.State.Downloading -> {
                    if (s.total > 0) {
                        val pct = (s.done * 100 / s.total).toInt()
                        determinate(pct)
                        b.tvInfo.text = "Downloading $pct%"
                    } else {
                        b.ring.isIndeterminate = true // no reliable total -> no fake percentage
                        b.tvInfo.text = "Downloading\u2026"
                    }
                    button("Cancel", true)
                    b.btnAction.setOnClickListener { RuntimeInstaller.cancelDownload() }
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
                    button("Installing\u2026", false) // not cancellable: it finishes or rolls back
                }
                is RuntimeInstaller.State.Installed -> {
                    determinate(100)
                    b.tvInfo.text = "\u2713 Installed \u00B7 TypeScript ${s.version}"
                    b.tvInfo.setTextColor(getColor(R.color.success_color))
                    button("Installed", false)
                }
                is RuntimeInstaller.State.Failed -> {
                    determinate(0)
                    b.tvInfo.text = "Failed: ${s.message}"
                    b.tvInfo.setTextColor(getColor(R.color.error_color))
                    button("Retry", true)
                    b.btnAction.setOnClickListener { RuntimeInstaller.installTypeScript(applicationContext) }
                }
            }
        }
    }
}