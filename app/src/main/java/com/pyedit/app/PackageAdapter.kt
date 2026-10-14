package com.pyedit.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pyedit.app.databinding.ItemPackageBinding

class PackageAdapter(
    private val infoFor: (String) -> PackageInfo?,
    private val infoFailed: (String) -> Boolean,
    private val installedVersion: (String) -> String?,
    private val isInstalling: (String) -> Boolean,
    private val onNeedInfo: (String) -> Unit,
    private val onInstall: (String) -> Unit
) : RecyclerView.Adapter<PackageAdapter.VH>() {

    private var names: List<String> = emptyList()

    inner class VH(val b: ItemPackageBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<String>) {
        names = list
        notifyDataSetChanged()
    }

    fun refresh(name: String) {
        val key = PackageCatalog.normalize(name)
        val idx = names.indexOfFirst { PackageCatalog.normalize(it) == key }
        if (idx >= 0) notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = names.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = names[position]
        val info = infoFor(name)
        val b = holder.b
        val ctx = b.root.context

        b.tvPkgName.text = info?.name?.takeIf { it.isNotBlank() } ?: name

        when {
            info != null -> b.tvPkgDesc.text = info.summary.ifBlank { "No description" }
            infoFailed(name) -> b.tvPkgDesc.text = "Details unavailable"
            else -> {
                b.tvPkgDesc.text = "Loading details…"
                onNeedInfo(name)
            }
        }

        val installed = installedVersion(name)
        b.tvPkgVersion.text = when {
            installed != null -> "installed v$installed"
            info != null && info.version.isNotBlank() -> "v${info.version}"
            else -> ""
        }
        b.tvPkgVersion.visibility = if (b.tvPkgVersion.text.isEmpty()) View.GONE else View.VISIBLE

        // Compatibility note, same card style as before
        var note: String? = null
        var noteColor = R.color.accent_grey
        if (installed == null && info != null) {
            when {
                info.compat == Compat.INCOMPATIBLE -> {
                    note = "This package is incompatible with this device." +
                        (info.reason?.let { "\n$it" } ?: "")
                    noteColor = R.color.warning_color
                }
                info.compat == Compat.UNKNOWN ->
                    note = info.reason ?: "Compatibility couldn't be determined."
                info.nativeBuild -> note = "Android native build"
            }
        }
        b.tvPkgNote.visibility = if (note != null) View.VISIBLE else View.GONE
        if (note != null) {
            b.tvPkgNote.text = note
            b.tvPkgNote.setTextColor(ctx.getColor(noteColor))
        }

        val busy = isInstalling(name)
        val (label, enabled) = when {
            busy -> "Installing…" to false
            installed != null -> "Installed" to false
            info == null -> "Install" to false
            info.compat == Compat.INCOMPATIBLE -> "Unavailable" to false
            else -> "Install" to true // INSTALLABLE, or UNKNOWN (installer decides)
        }
        b.btnInstall.text = label
        b.btnInstall.isEnabled = enabled
        b.btnInstall.alpha = if (enabled) 1f else 0.5f
        b.btnInstall.setOnClickListener { if (enabled) onInstall(name) }
    }
}