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

        val showNote = info != null && !info.installable && installed == null
        b.tvPkgNote.visibility = if (showNote) View.VISIBLE else View.GONE
        if (showNote) b.tvPkgNote.text = "No pure-Python wheel - can't be installed on this device"

        val busy = isInstalling(name)
        val (label, enabled) = when {
            busy -> "Installing…" to false
            installed != null -> "Installed" to false
            info == null -> "Install" to false
            !info.installable -> "Unavailable" to false
            else -> "Install" to true
        }
        b.btnInstall.text = label
        b.btnInstall.isEnabled = enabled
        b.btnInstall.alpha = if (enabled) 1f else 0.5f
        b.btnInstall.setOnClickListener { if (enabled) onInstall(name) }
    }
}