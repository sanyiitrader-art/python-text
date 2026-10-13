package com.pyedit.app

import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.pyedit.app.databinding.ActivityWebPreviewBinding
import java.io.ByteArrayInputStream

/** Hand-off from the Run button (the previewed text is the editor's current text). */
object WebPreviewHolder {
    var html: String = ""
    var title: String = ""
    var dirUri: Uri? = null
}

/**
 * Previews HTML in a WebView. Relative CSS/JS/images are served from the
 * HTML file's own folder through a virtual https host, so the page can't
 * reach anything outside that folder. File and content access are off.
 */
class WebPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebPreviewBinding
    private val host = "pyedit.local"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = WebPreviewHolder.title

        val dir = WebPreviewHolder.dirUri?.let { DocumentFile.fromTreeUri(this, it) }

        val web = binding.webView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != host) return null // external URLs load normally
                return serve(dir, request.url)
            }
        }
        web.loadDataWithBaseURL("https://$host/", WebPreviewHolder.html, "text/html", "utf-8", null)
    }

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
        binding.webView.destroy()
        super.onDestroy()
    }
}