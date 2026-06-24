package com.sevpor.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sevpor.chat.ui.theme.SuperWebViewTheme
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val WEBSITE = "https://chat.sevpor.com"
private const val TAG = "SevporWebView"
private const val BLOB_DOWNLOAD_BRIDGE = "SevporBlobDownloader"
private const val DEFAULT_DOWNLOAD_MIME_TYPE = "application/octet-stream"
private const val EXIT_BACK_PRESS_INTERVAL_MS = 2_000L

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionResources: Array<String> = emptyArray()
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingBlobDownload: BlobDownload? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            val resources = pendingPermissionResources
            val grantedResources = resources.filter { resource ->
                androidPermissionsForWebResource(resource).all { permission ->
                    isPermissionGranted(permission, result)
                }
            }.toTypedArray()

            if (grantedResources.isNotEmpty()) {
                request.grant(grantedResources)
            } else {
                request.deny()
            }
            pendingPermissionRequest = null
            pendingPermissionResources = emptyArray()
        }

    private val geolocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val origin = pendingGeolocationOrigin ?: return@registerForActivityResult
            val callback = pendingGeolocationCallback ?: return@registerForActivityResult
            val granted = locationPermissions.any { isPermissionGranted(it, result) }
            callback.invoke(origin, granted, false)
            pendingGeolocationOrigin = null
            pendingGeolocationCallback = null
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val download = pendingBlobDownload ?: return@registerForActivityResult
            pendingBlobDownload = null
            if (granted) {
                writeBlobDownload(download)
            } else {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SuperWebViewTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onShowFileChooser = ::showFileChooser,
                        onPermissionRequest = ::requestWebPermission,
                        onGeolocationPermissionRequest = ::requestGeolocationPermission,
                        onBlobDownload = ::saveBlobDownload,
                    )
                }
            }
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        val origin = pendingGeolocationOrigin
        val callback = pendingGeolocationCallback
        if (origin != null && callback != null) {
            callback.invoke(origin, false, false)
        }
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
        pendingBlobDownload = null
        super.onDestroy()
    }

    private fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        return try {
            fileChooserLauncher.launch(
                Intent.createChooser(params.createIntent(), getString(R.string.file_chooser_title))
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No file chooser available", e)
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            false
        }
    }

    private fun requestWebPermission(request: PermissionRequest) {
        val permissions = request.resources
            .flatMap { androidPermissionsForWebResource(it).asIterable() }
            .distinct()
            .toTypedArray()
        if (permissions.all { isPermissionGranted(it) }) {
            request.grant(request.resources)
            return
        }

        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        pendingPermissionResources = request.resources
        webPermissionLauncher.launch(permissions)
    }

    private fun requestGeolocationPermission(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (locationPermissions.any { isPermissionGranted(it) }) {
            callback.invoke(origin, true, false)
            return
        }

        pendingGeolocationOrigin = origin
        pendingGeolocationCallback = callback
        geolocationPermissionLauncher.launch(locationPermissions)
    }

    private fun saveBlobDownload(download: BlobDownload) {
        runOnUiThread {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                !isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                pendingBlobDownload = download
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@runOnUiThread
            }
            writeBlobDownload(download)
        }
    }

    private fun writeBlobDownload(download: BlobDownload) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeBlobDownloadWithMediaStore(download)
            } else {
                writeBlobDownloadToPublicDownloads(download)
            }
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to save blob download", e)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeBlobDownloadWithMediaStore(download: BlobDownload) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, download.fileName)
            put(MediaStore.Downloads.MIME_TYPE, download.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore download item")

        resolver.openOutputStream(uri)?.use { output ->
            output.write(download.bytes)
        } ?: error("Unable to open MediaStore download stream")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    @Suppress("DEPRECATION")
    private fun writeBlobDownloadToPublicDownloads(download: BlobDownload) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            error("Unable to create Downloads directory")
        }
        val file = uniqueDownloadFile(downloadsDir, download.fileName)
        FileOutputStream(file).use { output ->
            output.write(download.bytes)
        }
        MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath),
            arrayOf(download.mimeType),
            null,
        )
    }

    private fun isPermissionGranted(permission: String, result: Map<String, Boolean> = emptyMap()) =
        result[permission] == true || ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED

    private fun androidPermissionsForWebResource(resource: String): Array<String> =
        when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> arrayOf(Manifest.permission.CAMERA)
            else -> emptyArray()
        }
}

@Composable
private fun MainScreen(
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onBlobDownload: (BlobDownload) -> Unit,
) {
    var fullScreenView: View? by remember { mutableStateOf(null) }
    var popupWebView: WebView? by remember { mutableStateOf(null) }

    Box(Modifier.fillMaxSize()) {
        WebViewWithRefresher(
            modifier = Modifier.fillMaxSize(),
            onCustomViewChanged = { fullScreenView = it },
            onPopupWebViewChanged = { popupWebView = it },
            onShowFileChooser = onShowFileChooser,
            onPermissionRequest = onPermissionRequest,
            onGeolocationPermissionRequest = onGeolocationPermissionRequest,
            onBlobDownload = onBlobDownload,
        )

        popupWebView?.let { popup ->
            key(popup) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { popup })
            }
        }
    }

    BackHandler(enabled = popupWebView != null) {
        val popup = popupWebView ?: return@BackHandler
        if (popup.canGoBack()) {
            popup.goBack()
        } else {
            popup.destroy()
            popupWebView = null
        }
    }

    AnimatedVisibility(fullScreenView != null) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { fullScreenView!! })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewWithRefresher(
    modifier: Modifier = Modifier,
    onCustomViewChanged: (View?) -> Unit,
    onPopupWebViewChanged: (WebView?) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onBlobDownload: (BlobDownload) -> Unit,
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    val webViewId = remember { View.generateViewId() }
    var lastExitBackPressedAt by remember { mutableStateOf(0L) }
    var spaCanGoBack by remember { mutableStateOf(false) }
    val primaryColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryColorArgb = MaterialTheme.colorScheme.secondary.toArgb()
    val tertiaryColorArgb = MaterialTheme.colorScheme.tertiary.toArgb()
    val backgroundColorArgb = MaterialTheme.colorScheme.background.toArgb()

    BackHandler(enabled = webView != null) {
        val currentWebView = webView ?: return@BackHandler
        currentWebView.navigateBackOrElse(
            spaCanGoBack = spaCanGoBack,
            onSpaCanGoBackChanged = { spaCanGoBack = it },
        ) {
            if (!currentWebView.showExitPromptOrMoveToBack(lastExitBackPressedAt)) {
                lastExitBackPressedAt = System.currentTimeMillis()
            }
        }
    }

    AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val swipeRefreshLayout = SwipeRefreshLayout(context).apply {
            isEnabled = false
            setBackgroundColor(backgroundColorArgb)
            setColorSchemeColors(primaryColorArgb, secondaryColorArgb, tertiaryColorArgb)
        }

        webView = WebView(context).apply {
            id = webViewId
            configureLobeHubWebView(
                context = context,
                backgroundColorArgb = backgroundColorArgb,
                swipeRefreshLayout = swipeRefreshLayout,
                onCustomViewChanged = onCustomViewChanged,
                onPopupWebViewChanged = onPopupWebViewChanged,
                onShowFileChooser = onShowFileChooser,
                onPermissionRequest = onPermissionRequest,
                onGeolocationPermissionRequest = onGeolocationPermissionRequest,
                onBlobDownload = onBlobDownload,
                onSpaCanGoBackChanged = { spaCanGoBack = it },
            )
            loadUrl(WEBSITE)
        }
        swipeRefreshLayout.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        swipeRefreshLayout
    }, update = { swipeRefreshLayout ->
        val view = swipeRefreshLayout.findViewById<WebView>(webViewId)
        swipeRefreshLayout.setBackgroundColor(backgroundColorArgb)
        view?.setBackgroundColor(backgroundColorArgb)
        webView = view
    })
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureLobeHubWebView(
    context: Context,
    backgroundColorArgb: Int,
    swipeRefreshLayout: SwipeRefreshLayout?,
    onCustomViewChanged: (View?) -> Unit,
    onPopupWebViewChanged: (WebView?) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onBlobDownload: (BlobDownload) -> Unit,
    onSpaCanGoBackChanged: (Boolean) -> Unit,
) {
    setBackgroundColor(backgroundColorArgb)
    isFocusable = true
    isFocusableInTouchMode = true

    WebViewCompat.getCurrentWebViewPackage(context)?.let { webViewPackage ->
        Log.i(TAG, "Using WebView package ${webViewPackage.packageName} ${webViewPackage.versionName}")
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureLobeHubWebView, true)
    }
    addJavascriptInterface(
        WebViewBridge(
            currentUrl = { this@configureLobeHubWebView.url },
            onBlobDownload = onBlobDownload,
            onSpaCanGoBackChanged = onSpaCanGoBackChanged,
        ),
        BLOB_DOWNLOAD_BRIDGE,
    )

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            return handleExternalUri(context, view, uri)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.d(TAG, "Loading $url")
            onSpaCanGoBackChanged(false)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (isTrustedWebUrl(url)) {
                view?.evaluateJavascript(BLOB_DOWNLOAD_SCRIPT, null)
                view?.evaluateJavascript(SPA_NAVIGATION_BRIDGE_SCRIPT, null)
            } else {
                onSpaCanGoBackChanged(false)
            }
            swipeRefreshLayout?.isRefreshing = false
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(TAG, "Main frame error ${error?.errorCode}: ${error?.description}")
                swipeRefreshLayout?.isRefreshing = false
            }
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress == 100) swipeRefreshLayout?.isRefreshing = false
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val message = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
            Log.d(
                TAG,
                "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}"
            )
            return true
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(view?.context ?: context).apply {
                configureLobeHubWebView(
                    context = context,
                    backgroundColorArgb = backgroundColorArgb,
                    swipeRefreshLayout = null,
                    onCustomViewChanged = onCustomViewChanged,
                    onPopupWebViewChanged = onPopupWebViewChanged,
                    onShowFileChooser = onShowFileChooser,
                    onPermissionRequest = onPermissionRequest,
                    onGeolocationPermissionRequest = onGeolocationPermissionRequest,
                    onBlobDownload = onBlobDownload,
                    onSpaCanGoBackChanged = {},
                )
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            onPopupWebViewChanged(popup)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            onPopupWebViewChanged(null)
            window?.destroy()
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = onShowFileChooser(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            onPermissionRequest(request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            if (origin == null || callback == null) return
            onGeolocationPermissionRequest(origin, callback)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            onCustomViewChanged(view)
            super.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            onCustomViewChanged(null)
            super.onHideCustomView()
        }
    }

    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        handleDownload(
            context = context,
            webView = this@configureLobeHubWebView,
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
        )
    }

    with(settings) {
        domStorageEnabled = true
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        loadsImagesAutomatically = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        textZoom = 100
        safeBrowsingEnabled = true
        setSupportMultipleWindows(true)
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        userAgentString = browserLikeUserAgent(userAgentString)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
        }
    }
}

private fun handleExternalUri(context: Context, webView: WebView?, uri: Uri): Boolean {
    return when (uri.scheme?.lowercase()) {
        "http", "https", "about", "data", "blob" -> false
        "intent" -> {
            openIntentUri(context, webView, uri)
            true
        }
        else -> {
            openExternalUri(context, uri)
            true
        }
    }
}

private fun WebView.navigateBackOrElse(
    spaCanGoBack: Boolean,
    onSpaCanGoBackChanged: (Boolean) -> Unit,
    onNoBackStack: () -> Unit,
) {
    if (spaCanGoBack) {
        evaluateJavascript(SPA_GO_BACK_SCRIPT) { wentBack ->
            if (wentBack == "true") return@evaluateJavascript

            onSpaCanGoBackChanged(false)
            if (canGoBack()) {
                goBack()
            } else {
                onNoBackStack()
            }
        }
        return
    }

    if (canGoBack()) {
        onSpaCanGoBackChanged(false)
        goBack()
        return
    }

    onNoBackStack()
}

private fun WebView.showExitPromptOrMoveToBack(lastExitBackPressedAt: Long): Boolean {
    val now = System.currentTimeMillis()
    val activity = context as? Activity
    if (activity != null && now - lastExitBackPressedAt <= EXIT_BACK_PRESS_INTERVAL_MS) {
        activity.moveTaskToBack(true)
        return true
    }

    Toast.makeText(context, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
    return false
}

private fun openIntentUri(context: Context, webView: WebView?, uri: Uri) {
    try {
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        if (intent.resolveActivity(context.packageManager) != null) {
            launchActivity(context, intent)
            return
        }
        intent.getStringExtra("browser_fallback_url")?.let { fallbackUrl ->
            webView?.loadUrl(fallbackUrl)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open intent URI: $uri", e)
    }
}

private fun openExternalUri(context: Context, uri: Uri) {
    try {
        launchActivity(context, Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app can open URI: $uri", e)
    }
}

private fun launchActivity(context: Context, intent: Intent) {
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun handleDownload(
    context: Context,
    webView: WebView,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    val uri = Uri.parse(url)
    if (uri.scheme == "blob") {
        downloadBlobFromPage(webView, url, guessDownloadFileName(url, contentDisposition, mimeType))
        return
    }

    if (uri.scheme != "http" && uri.scheme != "https") {
        Log.w(TAG, "Unsupported WebView download URL: $url")
        Toast.makeText(context, R.string.download_not_supported, Toast.LENGTH_SHORT).show()
        return
    }

    val fileName = guessDownloadFileName(url, contentDisposition, mimeType)
    val request = DownloadManager.Request(uri).apply {
        setTitle(fileName)
        setDescription(url)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
    }

    try {
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "Unable to enqueue download: $url", e)
        Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
        openExternalUri(context, uri)
    }
}

private fun browserLikeUserAgent(defaultUserAgent: String): String =
    defaultUserAgent
        .replace("; wv", "")
        .replace("Version/4.0 ", "")

private val SPA_GO_BACK_SCRIPT = """
    (function () {
      try {
        if (window.SevporNav && typeof window.SevporNav.goBack === 'function') {
          if (typeof window.SevporNav.canGoBack === 'function' && !window.SevporNav.canGoBack()) {
            return false;
          }
          window.SevporNav.goBack();
          if (window.AndroidBackBridge && typeof window.AndroidBackBridge.syncCanGoBack === 'function') {
            window.setTimeout(window.AndroidBackBridge.syncCanGoBack, 0);
          }
          return true;
        }

        var state = window.__sevporSpaNavigationState;
        if (state && state.depth > 0) {
          window.history.back();
          if (window.AndroidBackBridge && typeof window.AndroidBackBridge.syncCanGoBack === 'function') {
            window.setTimeout(window.AndroidBackBridge.syncCanGoBack, 0);
          }
          return true;
        }

        return false;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private val SPA_NAVIGATION_BRIDGE_SCRIPT = """
    (function () {
      function getBridge() {
        return window.$BLOB_DOWNLOAD_BRIDGE;
      }

      function notifyCanGoBack(canGoBack) {
        var bridge = getBridge();
        if (!bridge || typeof bridge.updateCanGoBack !== 'function') return;
        bridge.updateCanGoBack(!!canGoBack);
      }

      if (!window.__sevporSpaNavigationState) {
        window.__sevporSpaNavigationState = {
          depth: 0,
          lastHref: window.location.href
        };
      }

      function fallbackCanGoBack() {
        return !!(window.__sevporSpaNavigationState && window.__sevporSpaNavigationState.depth > 0);
      }

      function syncCanGoBack() {
        var canGoBack = false;
        try {
          if (window.SevporNav && typeof window.SevporNav.canGoBack === 'function') {
            canGoBack = !!window.SevporNav.canGoBack();
          } else {
            canGoBack = fallbackCanGoBack();
          }
        } catch (e) {
          canGoBack = false;
        }
        notifyCanGoBack(canGoBack);
      }

      if (!window.AndroidBackBridge) {
        window.AndroidBackBridge = {};
      }
      window.AndroidBackBridge.syncCanGoBack = syncCanGoBack;

      if (!window.__sevporSpaNavigationBridgeAttached) {
        function trackLocationChange(countAsBackStack) {
          var state = window.__sevporSpaNavigationState;
          if (!state) return;

          if (window.location.href !== state.lastHref) {
            if (countAsBackStack) {
              state.depth += 1;
            }
            state.lastHref = window.location.href;
          }
          window.setTimeout(syncCanGoBack, 0);
        }

        function wrapHistoryMethod(methodName, countAsBackStack) {
          var original = window.history[methodName];
          if (typeof original !== 'function') return;

          window.history[methodName] = function () {
            var result = original.apply(this, arguments);
            trackLocationChange(countAsBackStack);
            return result;
          };
        }

        wrapHistoryMethod('pushState', true);
        wrapHistoryMethod('replaceState', false);

        window.addEventListener('popstate', function () {
          var state = window.__sevporSpaNavigationState;
          if (state) {
            state.depth = Math.max(0, state.depth - 1);
            state.lastHref = window.location.href;
          }
          window.setTimeout(syncCanGoBack, 0);
        });
        window.addEventListener('hashchange', function () {
          var state = window.__sevporSpaNavigationState;
          if (state) {
            state.lastHref = window.location.href;
          }
          window.setTimeout(syncCanGoBack, 0);
        });
        window.addEventListener('pageshow', syncCanGoBack);
        window.addEventListener('focus', syncCanGoBack);

        if (window.__sevporSpaNavigationTimer) {
          window.clearInterval(window.__sevporSpaNavigationTimer);
        }
        window.__sevporSpaNavigationTimer = window.setInterval(syncCanGoBack, 500);
        window.__sevporSpaNavigationBridgeAttached = true;
      }

      syncCanGoBack();
    })();
""".trimIndent()

private fun downloadBlobFromPage(webView: WebView, blobUrl: String, fileName: String) {
    if (!isTrustedWebUrl(webView.url)) {
        Log.w(TAG, "Blocked blob download from untrusted page: ${webView.url}")
        return
    }
    val script = "window.__sevporDownloadBlobUrl && window.__sevporDownloadBlobUrl(" +
        "${JSONObject.quote(blobUrl)}, ${JSONObject.quote(fileName)});"
    webView.evaluateJavascript(script, null)
}

private class WebViewBridge(
    private val currentUrl: () -> String?,
    private val onBlobDownload: (BlobDownload) -> Unit,
    private val onSpaCanGoBackChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun downloadBlob(base64Data: String?, mimeType: String?, fileName: String?) {
        if (!isTrustedWebUrl(currentUrl())) {
            Log.w(TAG, "Blocked blob download bridge call from untrusted page: ${currentUrl()}")
            return
        }
        if (base64Data.isNullOrBlank()) {
            Log.w(TAG, "Blocked empty blob download")
            return
        }
        try {
            onBlobDownload(
                BlobDownload(
                    bytes = Base64.decode(base64Data, Base64.DEFAULT),
                    fileName = sanitizeDownloadFileName(fileName),
                    mimeType = mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_MIME_TYPE,
                )
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid blob download data", e)
        }
    }

    @JavascriptInterface
    fun updateCanGoBack(canGoBack: Boolean) {
        if (!isTrustedWebUrl(currentUrl())) {
            Log.w(TAG, "Blocked navigation bridge call from untrusted page: ${currentUrl()}")
            return
        }
        mainHandler.post {
            onSpaCanGoBackChanged(canGoBack)
        }
    }
}

private data class BlobDownload(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
)

private fun guessDownloadFileName(
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): String = sanitizeDownloadFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))

private fun sanitizeDownloadFileName(fileName: String?): String {
    val cleanName = fileName
        ?.substringAfterLast('/')
        ?.replace(Regex("""[\\:*?"<>|]"""), "_")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        ?: "download"
    return cleanName.take(180)
}

private fun uniqueDownloadFile(directory: File, fileName: String): File {
    var candidate = File(directory, fileName)
    if (!candidate.exists()) return candidate

    val dotIndex = fileName.lastIndexOf('.')
    val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "$baseName ($index)$extension")
        index++
    }
    return candidate
}

private fun isTrustedWebUrl(url: String?): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
    val trustedHost = Uri.parse(WEBSITE).host ?: return false
    return host == trustedHost || host.endsWith(".$trustedHost")
}

private val BLOB_DOWNLOAD_SCRIPT = """
    (function () {
      if (window.__sevporBlobDownloadInstalled) return;
      window.__sevporBlobDownloadInstalled = true;

      function fileNameFromAnchor(anchor) {
        var fileName = anchor.getAttribute('download') || '';
        if (!fileName) fileName = 'download';
        return fileName;
      }

      function sendBlobDownload(blobUrl, fileName) {
        return fetch(blobUrl)
          .then(function (response) {
            return response.blob();
          })
          .then(function (blob) {
            var reader = new FileReader();
            reader.onloadend = function () {
              var result = String(reader.result || '');
              var commaIndex = result.indexOf(',');
              var base64Data = commaIndex >= 0 ? result.substring(commaIndex + 1) : '';
              window.$BLOB_DOWNLOAD_BRIDGE.downloadBlob(
                base64Data,
                blob.type || '$DEFAULT_DOWNLOAD_MIME_TYPE',
                fileName || 'download'
              );
            };
            reader.readAsDataURL(blob);
          })
          .catch(function (error) {
            console.error('Blob download failed', error);
          });
      }

      window.__sevporDownloadBlobUrl = sendBlobDownload;

      document.addEventListener(
        'click',
        function (event) {
          var target = event.target;
          if (!target || !target.closest) return;
          var anchor = target.closest('a[href^="blob:"]');
          if (!anchor) return;
          event.preventDefault();
          sendBlobDownload(anchor.href, fileNameFromAnchor(anchor));
        },
        true
      );
    })();
""".trimIndent()

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
