package io.github.audxius.loopkeep.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Reads the user's TikTok collections. TikTok only shows which posts are in a (private) collection
 * to the signed-in owner, and its export doesn't include it. So the user signs in on TikTok's own
 * page here (Loopkeep never sees the password), and `assets/collection_sync.js` asks TikTok's web
 * API from inside that page. The result is filed like a shared collection link.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokSyncScreen(vm: AppViewModel) {
    var status by remember { mutableStateOf("Sign in to TikTok. Use your username, email or phone; Google and Apple sign-in don't work inside apps.") }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun signedIn() = CookieManager.getInstance().getCookie(HOME).orEmpty().let { it.contains("sessionid=") || it.contains("sid_tt=") }

    fun start(view: WebView) {
        if (running) return
        running = true
        failed = false
        status = "Reading your collections…"
        val script = view.context.assets.open("collection_sync.js").bufferedReader().use { it.readText() }
            .replace("__HANDLE__", vm.ownHandle.orEmpty().filter { it.isLetterOrDigit() || it == '.' || it == '_' })
        view.evaluateJavascript(script, null)
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        ScreenHeader("TikTok collections", onBack = { vm.back() }) {
            TextButton(onClick = {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                running = false
                finished = false
                failed = false
                webView?.loadUrl(LOGIN)
                status = "Signed out of TikTok in Loopkeep."
            }) { Text("Sign out", color = Brand.TextSecondary) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Coral, trackColor = Brand.Surface3)
            Row {
                Text(status, color = if (failed) Brand.Rose else Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 6.dp))
                if (failed) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { webView?.let { start(it) } }) { Text("Try again", color = Brand.Coral) }
                }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    // WRAP_CONTENT (Compose's default) makes WebView lay pages out with zero height, so 100vh is 0.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // The desktop site: it has the web API the script uses and keeps login inside the page.
                    settings.userAgentString = DESKTOP_UA
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(object {
                        @JavascriptInterface fun progress(text: String) {
                            main.post { status = text }
                        }

                        @JavascriptInterface fun done(json: String) {
                            main.post {
                                running = false
                                finished = true
                                status = "Done. Filing your posts…"
                                vm.applyTikTokCollections(json)
                                vm.back()
                            }
                        }

                        @JavascriptInterface fun fail(text: String) {
                            main.post {
                                running = false
                                failed = true
                                status = "Couldn't read collections: $text"
                            }
                        }
                    }, "LoopkeepSync")
                    webViewClient = object : WebViewClient() {
                        // Stay on the web: never hand off to the TikTok app or other schemes.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.scheme !in setOf("http", "https")

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!signedIn()) return
                            if (url.contains("/login")) {
                                view.loadUrl(HOME)
                                return
                            }
                            if (!running && !failed && !finished) start(view)
                        }
                    }
                    loadUrl(if (signedIn()) HOME else LOGIN)
                }
            },
        )
    }
}

private const val HOME = "https://www.tiktok.com/"
private const val LOGIN = "https://www.tiktok.com/login"
private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
