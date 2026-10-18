package com.pyedit.app

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.pyedit.app.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * HTML / CSS / JS tabs for .html, .htm and .css files. The three sources live
 * in sibling files that share the open file's base name; only changed tabs
 * are written, and a missing sibling is created only when its tab has text.
 */
class WebProjectController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val editorController: EditorController,
    private val workspace: WorkspaceManager
) {
    var active = false
        private set

    private val labels = listOf("HTML", "CSS", "JS")
    private val exts = listOf("html", "css", "js")
    private val buffers = arrayOf("", "", "")
    private val originals = arrayOf("", "", "")
    private val docs = arrayOfNulls<DocumentFile>(3)
    private var parent: DocumentFile? = null
    private var base = ""
    private var current = 0
    private var primaryDoc: DocumentFile? = null
    private val tabViews = ArrayList<TextView>()
    private lateinit var bar: LinearLayout

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Wraps the editor in [tab bar + editor]. Call once, after editorController.setup(). */
    fun setup() {
        val editor = editorController.editor
        val container = binding.editorContainer
        container.removeView(editor)

        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(activity.getColor(R.color.topbar_background))
            visibility = View.GONE
        }
        labels.forEachIndexed { i, label ->
            val tv = TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { switchTo(i) }
            }
            bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabViews.add(tv)
        }
        wrapper.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        wrapper.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(
            wrapper,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    fun enter(doc: DocumentFile, primaryText: String, parentFolder: DocumentFile?) {
        val name = doc.name ?: ""
        base = name.substringBeforeLast('.')
        primaryDoc = doc
        parent = parentFolder
        val primary = if (Languages.forFileName(name) == Lang.CSS) 1 else 0
        for (i in 0..2) {
            if (i == primary) {
                docs[i] = doc
                buffers[i] = primaryText
            } else {
                val d = parentFolder?.findFile("$base.${exts[i]}")
                docs[i] = d
                buffers[i] = if (d != null && d.isFile) workspace.readFile(d) else ""
            }
            originals[i] = buffers[i]
        }
        current = primary
        active = true
        bar.visibility = View.VISIBLE
        paintTabs()
    }

    fun leave() {
        active = false
        primaryDoc = null
        parent = null
        for (i in 0..2) docs[i] = null
        if (::bar.isInitialized) bar.visibility = View.GONE
    }

    fun currentText(): String = buffers[current]

    fun syncCurrent() {
        if (active) buffers[current] = editorController.getText()
    }

    private fun switchTo(i: Int) {
        if (!active || i == current) return
        syncCurrent()
        current = i
        editorController.loadText(buffers[i]) // no dirty flag: this is just a view swap
        paintTabs()
    }

    private fun paintTabs() {
        tabViews.forEachIndexed { i, tv ->
            val sel = i == current
            tv.setBackgroundResource(if (sel) R.drawable.tab_selected_bg else 0)
            tv.setTextColor(activity.getColor(if (sel) R.color.text_normal else R.color.text_comment))
        }
    }

    /** Writes the changed tabs. Returns the names of tabs that could not be saved. */
    fun saveAll(): List<String> {
        syncCurrent()
        val failed = ArrayList<String>()
        for (i in 0..2) {
            if (buffers[i] == originals[i]) continue
            var d = docs[i]
            if (d == null || !d.exists()) {
                if (buffers[i].isBlank()) { originals[i] = buffers[i]; continue }
                d = parent?.createFile("application/octet-stream", "$base.${exts[i]}")
                if (d == null) { failed.add(labels[i]); continue }
                docs[i] = d
            }
            try {
                workspace.saveFile(d, buffers[i])
                originals[i] = buffers[i]
            } catch (e: Exception) {
                failed.add(labels[i])
            }
        }
        return failed
    }

    // --- unsaved drafts (survive an app kill) ------------------------------

    private fun draftFile(doc: DocumentFile): File {
        val dir = File(activity.cacheDir, "web_drafts").apply { mkdirs() }
        val key = MessageDigest.getInstance("MD5").digest((doc.uri.toString() + "#web").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, key)
    }

    fun writeDraft() {
        val doc = primaryDoc ?: return
        syncCurrent()
        val o = JSONObject()
        o.put("html", buffers[0]); o.put("css", buffers[1]); o.put("js", buffers[2])
        draftFile(doc).writeText(o.toString())
    }

    fun applyDraftIfAny(): Boolean {
        val doc = primaryDoc ?: return false
        val f = draftFile(doc)
        if (!f.exists()) return false
        return try {
            val o = JSONObject(f.readText())
            val nb = arrayOf(o.optString("html"), o.optString("css"), o.optString("js"))
            if (nb.contentEquals(originals)) {
                f.delete()
                false
            } else {
                for (i in 0..2) buffers[i] = nb[i]
                editorController.loadText(buffers[current])
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearDraft(doc: DocumentFile) {
        draftFile(doc).delete()
    }

    /**
     * Save All support: writes the stored (not currently open) draft of a web
     * project to its files. Returns null if there is no draft, otherwise
     * (wroteAnything, namesOfTabsThatFailed). The draft is deleted only when
     * every tab was written.
     */
    fun saveStoredDraft(doc: DocumentFile, parentFolder: DocumentFile?): Pair<Boolean, List<String>>? {
        val f = draftFile(doc)
        if (!f.exists()) return null
        val o = try { JSONObject(f.readText()) } catch (e: Exception) { return null }
        val texts = arrayOf(o.optString("html"), o.optString("css"), o.optString("js"))
        val name = doc.name ?: return null
        val stem = name.substringBeforeLast('.')
        val primary = if (Languages.forFileName(name) == Lang.CSS) 1 else 0
        val failed = ArrayList<String>()
        var wrote = false
        for (i in 0..2) {
            var d: DocumentFile? = if (i == primary) doc else parentFolder?.findFile("$stem.${exts[i]}")
            val onDisk = if (d != null && d.isFile) workspace.readFile(d) else ""
            if (texts[i] == onDisk) continue
            if (d == null || !d.exists()) {
                if (texts[i].isBlank()) continue
                d = parentFolder?.createFile("application/octet-stream", "$stem.${exts[i]}")
                if (d == null) { failed.add(labels[i]); continue }
            }
            try {
                workspace.saveFile(d, texts[i])
                wrote = true
            } catch (e: Exception) {
                failed.add(labels[i])
            }
        }
        if (failed.isEmpty()) f.delete()
        return Pair(wrote, failed)
    }

    fun findParent(root: DocumentFile?, target: Uri): DocumentFile? {
        if (root == null) return null
        for (child in root.listFiles()) {
            if (child.uri == target) return root
            if (child.isDirectory) findParent(child, target)?.let { return it }
        }
        return null
    }

    // --- run -----------------------------------------------------------------

    private fun insertBefore(html: String, tag: String, text: String, atEndIfMissing: Boolean): String {
        if (text.isEmpty()) return html
        val idx = html.lastIndexOf(tag, ignoreCase = true)
        return when {
            idx >= 0 -> html.substring(0, idx) + text + "\n" + html.substring(idx)
            atEndIfMissing -> html + "\n" + text
            else -> text + "\n" + html
        }
    }

    /** Also records where the JS tab starts in the combined page, so the
     * preview can report errors as "JS tab, line N". */
    private fun buildPreview(): String {
        val css = buffers[1]
        val js = buffers[2].replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\\\/script")
        val style = if (css.isNotBlank()) "<style>\n$css\n</style>" else ""
        val script = if (js.isNotBlank()) "<script>\n$js\n</script>" else ""
        var html = insertBefore(buffers[0], "</head>", style, false)
        html = insertBefore(html, "</body>", script, true)

        WebPreviewHolder.jsFirstLine = 0
        WebPreviewHolder.jsLineCount = 0
        if (script.isNotEmpty()) {
            val idx = html.lastIndexOf(script)
            if (idx >= 0) {
                WebPreviewHolder.jsFirstLine = html.substring(0, idx).count { it == '\n' } + 2
                WebPreviewHolder.jsLineCount = js.count { it == '\n' } + 1
            }
        }
        return html
    }

    fun runPreview() {
        syncCurrent()
        WebPreviewHolder.html = buildPreview()
        WebPreviewHolder.title = primaryDoc?.name ?: "preview"
        WebPreviewHolder.dirUri = parent?.uri
        if (parent == null) {
            Toast.makeText(
                activity,
                "Folder not known - linked files can't load. Open the folder first.",
                Toast.LENGTH_LONG
            ).show()
        }
        activity.startActivity(Intent(activity, WebPreviewActivity::class.java))
    }
}