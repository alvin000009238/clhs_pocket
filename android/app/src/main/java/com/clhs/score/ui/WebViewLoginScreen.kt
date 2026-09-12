package com.clhs.score.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import com.clhs.score.data.AuthenticatedSession
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val LOGIN_URL = "https://shcloud2.k12ea.gov.tw/CLHSTYC/Auth/Auth/CloudLogin"
private const val SCHOOL_HOME_URL = "https://shcloud2.k12ea.gov.tw/CLHSTYC/ICampus/Home/Index2"
private const val SCHOOL_DOMAIN = "shcloud2.k12ea.gov.tw"
private const val LOGIN_HOOK_LOG_PREFIX = "[ScoreLoginHook]"
private const val LOGIN_HOOK_SUCCESS_PREFIX = "$LOGIN_HOOK_LOG_PREFIX LoginSuccess "
private const val WEB_VIEW_LOGIN_TAG = "WebViewLogin"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebViewLoginScreen(
    isProcessingLogin: Boolean,
    errorMessage: String?,
    onLoginSuccess: (studentNo: String, cookieString: String) -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
) {
    var isPageLoading by remember { mutableStateOf(true) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val utilityMotion = remember { MotionScheme.standard() }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.clearSchoolWebData()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WebViewNavigationBar(pageTitle = pageTitle, webView = webViewRef, onBack = onBack)
            Box(modifier = Modifier.weight(1f)) {
                WebViewContent(
                    onWebViewCreated = { webViewRef = it },
                    onPageStarted = { isPageLoading = true },
                    onPageFinished = { isPageLoading = false },
                    onProgressChanged = { pageProgress = it / 100f },
                    onReceivedTitle = { pageTitle = it.orEmpty().trim() },
                    onLoginSuccess = onLoginSuccess,
                )

                if (isPageLoading || isProcessingLogin) {
                    if (isProcessingLogin) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { pageProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = isProcessingLogin,
                    enter = fadeIn(utilityMotion.defaultEffectsSpec()),
                    exit = fadeOut(utilityMotion.defaultEffectsSpec()),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            }
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.shapes.large,
                                )
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LoadingIndicator(modifier = Modifier.size(48.dp))
                            Text(
                                text = "正在驗證登入…",
                                modifier = Modifier.padding(top = 16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                action = {
                    TextButton(onClick = onDismissError) {
                        Text("關閉")
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun SchoolWebsiteScreen(
    session: AuthenticatedSession,
    isVisible: Boolean,
    onBack: () -> Unit,
    onAuthenticationExpired: () -> Unit = {},
) {
    var isPageLoading by remember { mutableStateOf(true) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.clearSchoolWebData()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (isVisible) 1f else 0f)
            .zIndex(if (isVisible) 1f else -1f)
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        WebViewNavigationBar(pageTitle = pageTitle, webView = webViewRef, onBack = onBack)
        Box(modifier = Modifier.weight(1f)) {
            AuthenticatedSchoolWebView(
                session = session,
                onWebViewCreated = { webViewRef = it },
                onPageStarted = { url ->
                    isPageLoading = true
                    if (isTrustedSchoolLoginUrl(url)) onAuthenticationExpired()
                },
                onPageFinished = { isPageLoading = false },
                onProgressChanged = { pageProgress = it / 100f },
                onReceivedTitle = { pageTitle = it.orEmpty().trim() },
            )

            if (isPageLoading) {
                LinearProgressIndicator(
                    progress = { pageProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WebViewNavigationBar(
    pageTitle: String,
    webView: WebView?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            shapes = IconButtonDefaults.shapes(),
        ) {
            OutlinedRoundedSymbol(
                icon = "arrow_back",
                contentDescription = "返回",
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = pageTitle.ifBlank { "正在載入…" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = SCHOOL_DOMAIN,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = { webView?.reload() },
            shapes = IconButtonDefaults.shapes(),
        ) {
            OutlinedRoundedSymbol(
                icon = "refresh",
                contentDescription = "重新載入",
            )
        }
    }
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthenticatedSchoolWebView(
    session: AuthenticatedSession,
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: (String?) -> Unit,
    onPageFinished: () -> Unit,
    onProgressChanged: (Int) -> Unit,
    onReceivedTitle: (String?) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                configureForSchoolSite()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return !isTrustedSchoolUrl(url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onPageStarted(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onPageFinished()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onReceivedTitle(title)
                    }
                }
                onWebViewCreated(this)
                loadAuthenticatedSchoolSite(session)
            }
        },
    )
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewContent(
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
    onProgressChanged: (Int) -> Unit,
    onReceivedTitle: (String?) -> Unit,
    onLoginSuccess: (studentNo: String, cookieString: String) -> Unit,
) {
    var loginHandled by remember { mutableStateOf(false) }
    var isTrustedLoginPage by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                configureForSchoolSite()

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return !isTrustedSchoolUrl(url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        isTrustedLoginPage = false
                        onPageStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onPageFinished()
                        isTrustedLoginPage = isTrustedSchoolLoginUrl(url)
                        if (isTrustedLoginPage) {
                            loginHandled = false
                            view?.evaluateJavascript(LOGIN_HOOK_JS, null)
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onReceivedTitle(title)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val message = consoleMessage?.message().orEmpty()
                        if (message.startsWith(LOGIN_HOOK_SUCCESS_PREFIX)) {
                            if (loginHandled || !isTrustedLoginPage) return true
                            loginHandled = true
                            val studentNo = URLDecoder.decode(
                                message.removePrefix(LOGIN_HOOK_SUCCESS_PREFIX),
                                StandardCharsets.UTF_8.name(),
                            )
                            val cookieString = CookieManager.getInstance()
                                .getCookie("https://$SCHOOL_DOMAIN") ?: ""
                            post { onLoginSuccess(studentNo, cookieString) }
                            return true
                        }
                        if (message.startsWith(LOGIN_HOOK_LOG_PREFIX)) {
                            Log.w(WEB_VIEW_LOGIN_TAG, message)
                            return true
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                onWebViewCreated(this)
                loadUrl(LOGIN_URL)
            }
        },
    )
}

private fun isTrustedSchoolUrl(url: String?): Boolean {
    val uri = runCatching { url?.toUri() }.getOrNull() ?: return false
    return uri.scheme == "https" &&
        uri.host.equals(SCHOOL_DOMAIN, ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443)
}

private fun isTrustedSchoolLoginUrl(url: String?): Boolean {
    val uri = runCatching { url?.toUri() }.getOrNull() ?: return false
    return isTrustedSchoolUrl(url) &&
        uri.encodedPath.orEmpty().contains("/CLHSTYC/Auth/Auth/CloudLogin")
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForSchoolSite() {
    layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = 100
        cacheMode = WebSettings.LOAD_NO_CACHE
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loadWithOverviewMode = true
        useWideViewPort = true
        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForSchoolSite, false)
    }
}

private fun WebView.loadAuthenticatedSchoolSite(session: AuthenticatedSession) {
    val cookieManager = CookieManager.getInstance()
    session.cookies.forEach { (name, value) ->
        cookieManager.setCookie(SCHOOL_HOME_URL, "$name=$value; Path=/; Secure")
    }
    cookieManager.flush()
    loadUrl(SCHOOL_HOME_URL)
}

private fun WebView.clearSchoolWebData() {
    stopLoading()
    clearHistory()
    clearFormData()
    clearCache(true)
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies(null)
}

private val LOGIN_HOOK_JS = """
(function() {
    if (window.__loginHooked) return;
    window.__loginHooked = true;

    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url) {
        this.__url = url;
        this.__method = method;
        return origOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function(body) {
        var self = this;
        if (self.__url && self.__url.indexOf('DoCloudLoginCheck') >= 0) {
            var loginId = '';
            try {
                var loginField = document.querySelector('input[name="LoginId"]');
                if (loginField) loginId = loginField.value || '';
            } catch(e) {
                console.warn('$LOGIN_HOOK_LOG_PREFIX LoginId field lookup failed: ' + (e && e.message ? e.message : e));
            }

            if (!loginId && body) {
                try {
                    var params = new URLSearchParams(body);
                    loginId = params.get('LoginId') || '';
                } catch(e) {
                    console.warn('$LOGIN_HOOK_LOG_PREFIX LoginId body parse failed: ' + (e && e.message ? e.message : e));
                }
            }

            self.addEventListener('load', function() {
                try {
                    var resp = JSON.parse(self.responseText);
                    if (resp && resp.Result && resp.Result.IsLoginSuccess === true) {
                        console.info('$LOGIN_HOOK_SUCCESS_PREFIX' + encodeURIComponent(loginId));
                    }
                } catch(e) {
                    console.warn('$LOGIN_HOOK_LOG_PREFIX Login response handling failed: ' + (e && e.message ? e.message : e));
                }
            });
        }
        return origSend.apply(this, arguments);
    };
})();
""".trimIndent()
