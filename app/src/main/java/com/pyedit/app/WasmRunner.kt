package com.pyedit.app

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Runs Ruby / PHP (WebAssembly) in a hidden WebView. Runtime files are served
 * from the installed folder through a virtual host; nothing else is reachable
 * (all other requests, including the network, are blocked). Stop destroys
 * the WebView. Every startup step and every failure is reported to the Listener.
 */
class WasmRunner(private val activity: AppCompatActivity) {

    interface Listener {
        fun onStage(text: String)
        fun onOutput(stream: Int, text: String)
        fun onDone(ok: Boolean, message: String)
    }

    private val host = "wasm.pyedit.local"
    private var web: WebView? = null
    private var listener: Listener? = null
    private var token = 0
    private var running = false

    inner class Bridge(private val mine: Int) {
        @JavascriptInterface
        fun write(stream: Int, text: String) {
            activity.runOnUiThread { if (mine == token && running) listener?.onOutput(stream, text) }
        }

        @JavascriptInterface
        fun stage(text: String) {
            activity.runOnUiThread { if (mine == token && running) listener?.onStage(text) }
        }

        @JavascriptInterface
        fun done(ok: Boolean, message: String) {
            activity.runOnUiThread { finish(mine, ok, message) }
        }
    }

    private fun finish(mine: Int, ok: Boolean, message: String) {
        if (mine == token && running) {
            running = false
            val l = listener
            release()
            l?.onDone(ok, message)
        }
    }

    private fun release() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    fun stop() {
        token++
        running = false
        release()
        listener = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun run(id: WasmRuntimes.Id, code: String, l: Listener) {
        stop()
        listener = l
        running = true
        val mine = token
        try {
            val wv = WebView(activity)
            web = wv
            wv.alpha = 0f
            activity.findViewById<ViewGroup>(android.R.id.content).addView(wv, FrameLayout.LayoutParams(1, 1))
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.addJavascriptInterface(Bridge(mine), "Android")
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.host == host) serve(request.url) else empty(404)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (mine == token && running) {
                        listener?.onOutput(2, "Load error for ${request.url.path}: ${error.description}\n")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (mine == token && running) {
                        listener?.onOutput(2, "Couldn't load ${request.url.path} (HTTP ${errorResponse.statusCode})\n")
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    finish(
                        mine, false,
                        "The WebView process " + (if (detail.didCrash()) "crashed" else "was killed (probably out of memory)")
                    )
                    return true
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (mine == token && running && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        listener?.onOutput(2, "[browser] ${m.message()} (line ${m.lineNumber()})\n")
                    }
                    return true
                }
            }
            wv.loadDataWithBaseURL("https://$host/page", page(id, code), "text/html", "utf-8", null)
        } catch (t: Throwable) {
            finish(mine, false, "WebView isn't available: ${t.message}")
        }
    }

    private fun empty(code: Int) = WebResourceResponse(
        "text/plain", "utf-8", code, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun serve(url: Uri): WebResourceResponse {
        val segs = url.pathSegments
        if (segs.size != 2) return empty(404)
        val id = WasmRuntimes.Id.values().firstOrNull { it.dir == segs[0] } ?: return empty(404)
        val base = WasmRuntimes.dir(activity, id).canonicalFile
        val f = try { File(base, segs[1]).canonicalFile } catch (e: Exception) { return empty(404) }
        if (f.parentFile != base || !f.isFile) return empty(404)
        val mime = when (f.extension.lowercase()) {
            "wasm" -> "application/wasm"
            "mjs", "js" -> "text/javascript"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        return WebResourceResponse(mime, null, FileInputStream(f))
    }

    // Console output is forwarded to the app; page errors end the run with a message.
    private val prelude = """
<script>
(function(){var A=window.Android;
function line(a){return Array.prototype.map.call(a,String).join(' ')+"\n";}
console.log=console.info=console.debug=function(){A.write(1,line(arguments));};
console.warn=console.error=function(){A.write(2,line(arguments));};
window.onerror=function(m){A.done(false,String(m));};
window.onunhandledrejection=function(e){var r=e&&e.reason;A.done(false,String(r&&r.message?r.message:r));};
})();
</script>
"""

    private fun page(id: WasmRuntimes.Id, code: String): String {
        val src = JSONObject.quote(code)
        return if (id == WasmRuntimes.Id.RUBY) {
            "<!doctype html><html><body>" + prelude +
                "<script>window.Android.stage('Loading the Ruby runtime script');</script>" +
                "<script src=\"/ruby/browser.umd.js\"></script><script>" +
                "(async function(){var A=window.Android;try{" +
                "var ns=window['ruby-wasm-wasi'];" +
                "if(!ns||!ns.DefaultRubyVM)throw new Error('The Ruby runtime script did not load (global ruby-wasm-wasi not found)');" +
                "A.stage('Compiling WebAssembly (the first run can take 10-60 s)');" +
                "var url='/ruby/ruby+stdlib.wasm';var mod;" +
                "try{mod=await WebAssembly.compileStreaming(fetch(url));}" +
                "catch(e1){A.stage('Streaming compile failed ('+(e1&&e1.message?e1.message:e1)+'); compiling from memory');" +
                "var r2=await fetch(url);mod=await WebAssembly.compile(await r2.arrayBuffer());}" +
                "A.stage('Starting Ruby');" +
                "var r=await ns.DefaultRubyVM(mod);" +
                "A.stage('Running');" +
                "r.vm.eval(" + src + ");" +
                "A.done(true,'');" +
                "}catch(e){A.done(false,String(e&&e.message?e.message:e));}})();" +
                "</script></body></html>"
        } else {
            "<!doctype html><html><body>" + prelude +
                "<script>(async function(){var A=window.Android;try{" +
                "A.stage('Loading the PHP runtime script');" +
                "var m=await import('/php/PhpWeb.mjs');" +
                "A.stage('Starting PHP (compiling WebAssembly, the first run can take 10-60 s)');" +
                "var php=new m.PhpWeb();" +
                "php.addEventListener('output',function(e){A.write(1,String(e.detail));});" +
                "php.addEventListener('error',function(e){A.write(2,String(e.detail));});" +
                "A.stage('Running');" +
                "var code=await php.run(" + src + ");" +
                "var ok=(code===0||code===undefined);" +
                "A.done(ok,ok?'':'exit code '+code);" +
                "}catch(e){A.done(false,String(e&&e.message?e.message:e));}})();" +
                "</script></body></html>"
        }
    }
}