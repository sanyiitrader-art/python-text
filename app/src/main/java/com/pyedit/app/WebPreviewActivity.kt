package com.pyedit.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.pyedit.app.databinding.ActivityWebPreviewBinding
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** Hand-off from the Run button (the previewed text is the editor's current text). */
object WebPreviewHolder {
    var html: String = ""
    var title: String = ""
    var dirUri: Uri? = null

    // Where the JS tab starts in the combined page (0 = no mapping). Read once.
    var jsFirstLine: Int = 0
    var jsLineCount: Int = 0
}

/**
 * Previews HTML in a WebView. Relative CSS/JS/images are served from the
 * HTML file's own folder through a virtual https host. File and content
 * access stay off, and navigation to other sites is blocked. Console output
 * and uncaught errors appear as floating notifications over the page.
 */
class WebPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebPreviewBinding
    private val host = "pyedit.local"
    private val handler = Handler(Looper.getMainLooper())

    private var jsFirstLine = 0
    private var jsLineCount = 0

    // card coalescing: quick consecutive console lines share one card
    private var lastInfoCard: TextView? = null
    private var lastInfoTime = 0L
    private val lastInfoLines = ArrayList<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = WebPreviewHolder.title

        jsFirstLine = WebPreviewHolder.jsFirstLine
        jsLineCount = WebPreviewHolder.jsLineCount
        WebPreviewHolder.jsFirstLine = 0 // one-shot, so a later standalone run can't reuse it
        WebPreviewHolder.jsLineCount = 0

        val dir = WebPreviewHolder.dirUri?.let { DocumentFile.fromTreeUri(this, it) }

        val web = binding.webView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != host) return null
                return serve(dir, request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                notify("Blocked navigation to ${request.url}", true)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                notify("\u2713 Page loaded", false)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.url.host != host) return
                val path = request.url.path?.trimStart('/') ?: return
                if (path.isEmpty() || path == "favicon.ico") return
                notify("\u2717 Couldn't load $path (HTTP ${errorResponse.statusCode})", true)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val isError = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                if (isError) notify("\u2717 ${m.message()}${where(m.lineNumber())}", true)
                else notify(m.message(), false)
                return true
            }
        }

        web.loadDataWithBaseURL("https://$host/", WebPreviewHolder.html, "text/html", "utf-8", null)
    }

    private fun where(line: Int): String {
        if (line <= 0) return ""
        return if (jsFirstLine > 0 && line >= jsFirstLine && line < jsFirstLine + jsLineCount)
            "  (JS tab, line ${line - jsFirstLine + 1})"
        else "  (line $line)"
    }

    // --- notifications --------------------------------------------------------

    private fun notify(text: String, error: Boolean) {
        val container = binding.notifications
        val now = SystemClock.uptimeMillis()

        val prev = lastInfoCard
        if (!error && prev != null && prev.parent === container && now - lastInfoTime < 400) {
            lastInfoLines.add(text)
            prev.text = joinLines()
            lastInfoTime = now
            startTimer(prev)
            return
        }

        // keep at most 4 cards: drop the oldest informational one first, else the oldest
        while (container.childCount >= 4) {
            var victim: View = container.getChildAt(0)
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i)
                if (c.getTag(R.id.notifications) != "error") { victim = c; break }
            }
            cancelTimer(victim)
            container.removeView(victim)
        }

        val card = makeCard(error)
        card.setTag(R.id.notifications, if (error) "error" else "info")
        if (error) {
            card.text = text
            lastInfoCard = null
        } else {
            lastInfoLines.clear()
            lastInfoLines.add(text)
            card.text = text
            lastInfoCard = card
            lastInfoTime = now
        }
        container.addView(card)
        startTimer(card)
    }

    private fun joinLines(): String {
        val shown = lastInfoLines.take(8).joinToString("\n")
        val extra = lastInfoLines.size - 8
        return if (extra > 0) "$shown\n\u2026 +$extra more" else shown
    }

    private fun makeCard(error: Boolean): TextView {
        val tv = TextView(this)
        tv.setTextColor(getColor(if (error) R.color.error_color else R.color.text_normal))
        tv.textSize = 12.5f
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.background = GradientDrawable().apply {
            setColor(getColor(R.color.popup_background))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), getColor(if (error) R.color.error_color else R.color.mint_primary))
        }
        tv.elevation = dp(6).toFloat()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        tv.layoutParams = lp
        attachSwipe(tv)
        return tv
    }

    private fun attachSwipe(card: View) {
        var downX = 0f
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    cancelTimer(v) // don't vanish while being touched
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    v.translationX = dx
                    v.alpha = (1f - abs(dx) / v.width.coerceAtLeast(1)).coerceIn(0.2f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > v.width * 0.3f) {
                        dismiss(v, if (dx > 0) v.width.toFloat() else -v.width.toFloat())
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(120).start()
                        startTimer(v)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startTimer(card: View) {
        cancelTimer(card)
        val r = Runnable { dismiss(card, 0f) } // auto-dismiss fades out
        card.tag = r
        handler.postDelayed(r, 5000)
    }

    private fun cancelTimer(card: View) {
        (card.tag as? Runnable)?.let { handler.removeCallbacks(it) }
    }

    private fun dismiss(card: View, toX: Float) {
        cancelTimer(card)
        if (card.parent == null) return
        card.animate().translationX(toX).alpha(0f).setDuration(160)
            .withEndAction { (card.parent as? ViewGroup)?.removeView(card) }
            .start()
    }

    // --- local file serving ---------------------------------------------------

    private fun serve(dir: DocumentFile?, url: Uri): WebResourceResponse {
        var cur: DocumentFile? = dir
        for (seg in url.pathSegments) {
            if (seg.isEmpty() || seg == "..") continue
            cur = cur?.findFile(seg)
        }
        if (cur == null || !cur.isFile) return notFound()
        val ext = Languages.extensionOf(cur.name ?: "")
        val mime = when (ext) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val stream = contentResolver.openInputStream(cur.uri) ?: return notFound()
        return WebResourceResponse(mime, "utf-8", stream)
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
        super.onDestroy()
    }
}