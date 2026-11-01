package io.github.audxius.loopkeep.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Reads the user's TikTok collections. TikTok only shows which posts are in a (private) collection
 * to the signed-in owner, and its export doesn't include it. So the user signs in on TikTok's own
 * page here (Loopkeep never sees the password), and `assets/collection_sync.js` asks TikTok's web
 * API from inside that page. The result is filed like a shared collection link.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokSyncScreen(vm: AppViewModel) {
    var status by remember { mutableStateOf("Sign in to TikTok. Google won't sign in inside other apps, so a Google account signs in with the QR code and your TikTok app.") }
    // The link inside the QR code TikTok is showing; the signed-in TikTok app confirms it.
    var qrLink by remember { mutableStateOf<String?>(null) }
    var qrHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
        status = "Reading your videos and collections…"
        val script = view.context.assets.open("collection_sync.js").bufferedReader().use { it.readText() }
            .replace("__HANDLE__", vm.ownHandle.orEmpty().filter { it.isLetterOrDigit() || it == '.' || it == '_' })
        view.evaluateJavascript(script, null)
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        ScreenHeader("Sync from TikTok", onBack = { vm.back() }) {
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
            if (!signedIn() && !running) {
                if (qrLink == null) {
                    GradientButton(
                        "Signed up with Google? Use QR code", onClick = { webView?.loadUrl(LOGIN_QR) },
                        icon = Icons.Default.QrCode, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                } else {
                    GradientButton(
                        "Confirm in TikTok app", onClick = { if (!openInTikTok(context, qrLink!!)) qrHelp = true },
                        icon = Icons.AutoMirrored.Filled.OpenInNew, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                    Text(
                        if (qrHelp) "Couldn't open TikTok directly. Take a screenshot of the code below, then in TikTok: Search → scan icon → pick the screenshot from your photos → Confirm. Then come back here."
                        else "Opens TikTok, where you confirm the login. Then come back here; the sync starts by itself. Didn't work? Screenshot the code and scan it from TikTok's scanner (Search → scan icon → photos).",
                        color = Brand.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (qrLink == null || running || failed) Row {
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
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val hook = context.assets.open("qr_hook.js").bufferedReader().use { it.readText() }
                        WebViewCompat.addDocumentStartJavaScript(this, hook, setOf("https://www.tiktok.com"))
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface fun qr(url: String) {
                            if (url.startsWith("https://www.tiktok.com/")) main.post { qrLink = url }
                        }

                        // Your own videos, downloaded by the page with your session (see collection_sync.js).
                        @JavascriptInterface fun needs(id: String): Boolean = vm.needsOwnVideo(id)
                        @JavascriptInterface fun fileStart(id: String): Boolean = vm.ownVideoFiles.start(id)
                        @JavascriptInterface fun fileChunk(id: String, base64: String): Boolean = vm.ownVideoFiles.append(id, base64)
                        @JavascriptInterface fun fileEnd(id: String): String = vm.ownVideoFiles.finish(id).orEmpty()

                        @JavascriptInterface fun progress(text: String) {
                            main.post { status = text }
                        }

                        @JavascriptInterface fun done(json: String) {
                            main.post {
                                running = false
                                finished = true
                                status = "Done. Saving your videos and filing collections…"
                                vm.applyTikTokSync(json)
                                vm.back()
                            }
                        }

                        @JavascriptInterface fun fail(text: String) {
                            main.post {
                                running = false
                                failed = true
                                status = "Couldn't read from TikTok: $text"
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
private const val LOGIN_QR = "https://www.tiktok.com/login/qrcode"
private const val DESKTOP_UA = io.github.audxius.loopkeep.pipeline.TikTokSession.USER_AGENT

/**
 * Hands the QR login link to the TikTok app, which opens it in its own (signed-in) browser where the
 * user confirms the login: first through TikTok's webview deep link, then as a plain link restricted
 * to the TikTok app. Returns false if TikTok isn't installed or handles neither.
 */
private fun openInTikTok(context: Context, link: String): Boolean {
    val encoded = Uri.encode(link)
    val attempts = TIKTOK_PACKAGES.flatMap { pkg ->
        listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("snssdk1233://webview?url=$encoded")).setPackage(pkg),
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg),
        )
    }
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return false
}

/** TikTok's package names (global, and the regional "trill" build). */
private val TIKTOK_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
