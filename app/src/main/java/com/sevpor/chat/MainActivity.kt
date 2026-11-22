package com.sevpor.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.security.KeyChain
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sevpor.chat.ui.theme.SuperWebViewTheme
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.WeakHashMap

private const val WEBSITE = "https://chat.sevpor.com"
private const val TAG = "SevporWebView"
private const val BLOB_DOWNLOAD_BRIDGE = "SevporBlobDownloader"
private const val DEFAULT_DOWNLOAD_MIME_TYPE = "application/octet-stream"
private const val EXIT_BACK_PRESS_INTERVAL_MS = 2_000L
private const val FOREGROUND_RESUME_DISPATCH_DELAY_MS = 150L
private const val SESSION_REFRESH_CHECK_INTERVAL_MS = 60 * 60 * 1_000L
private const val BLOB_DOWNLOAD_CHUNK_BYTES = 256 * 1024
private val webViewDownloadBridges = WeakHashMap<WebView, WebViewBridge>()

class MainActivity : ComponentActivity() {

    private val activeWebViews = linkedSetOf<WebView>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingForegroundResumeRunnable: Runnable? = null
    private var pausedAtElapsedMs = 0L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserCaptureUri: Uri? = null
    private var pendingFileChooserCaptureFile: File? = null
    private var pendingFileChooserCaptureParams: WebChromeClient.FileChooserParams? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionResources: Array<String> = emptyArray()
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingBlobDownload: BlobDownload? = null
    private var isActivityResumed = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            try {
                callback.onReceiveValue(
                    parseSelectedFileUris(result.resultCode, result.data)
                        ?: parseCaptureOutputUri(result.resultCode)
                        ?: WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to parse selected files", e)
                callback.onReceiveValue(null)
            } finally {
                if (result.resultCode != Activity.RESULT_OK) {
                    clearPendingFileChooserCapture(deleteFile = true)
                } else {
                    clearPendingFileChooserCapture(deleteFile = false)
                }
                filePathCallback = null
            }
        }

    private val fileChooserCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            val params = pendingFileChooserCaptureParams ?: return@registerForActivityResult
            pendingFileChooserCaptureParams = null

            val granted = capturePermissionsFor(params).all { permission ->
                isPermissionGranted(permission, result)
            }
            if (!granted) {
                callback.onReceiveValue(null)
                filePathCallback = null
                clearPendingFileChooserCapture(deleteFile = true)
                return@registerForActivityResult
            }

            try {
                fileChooserLauncher.launch(createFileChooserIntent(params))
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch capture intent", e)
                callback.onReceiveValue(null)
                filePathCallback = null
                clearPendingFileChooserCapture(deleteFile = true)
            }
        }

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            val resources = pendingPermissionResources
            val grantedResources = resources.filter { resource ->
                val permissions = androidPermissionsForWebResource(resource) ?: return@filter false
                permissions.all { permission ->
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
                download.tempFile.delete()
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
                        onWebViewCreated = ::registerWebView,
                        onWebViewDestroyed = ::unregisterWebView,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        val wasPaused = pausedAtElapsedMs > 0L
        pausedAtElapsedMs = 0L
        activeWebViews.toList().forEach { webView ->
            runCatching {
                webView.onResume()
            }.onFailure { Log.w(TAG, "Unable to resume WebView", it) }
        }
        if (wasPaused) {
            scheduleForegroundResumeWakeup()
        }
    }

    override fun onPause() {
        cancelPendingForegroundResumeWakeup()
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        activeWebViews.toList().forEach { webView ->
            runCatching {
                webView.onPause()
            }.onFailure { Log.w(TAG, "Unable to pause WebView", it) }
        }
        isActivityResumed = false
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        cancelPendingForegroundResumeWakeup()
        CookieManager.getInstance().flush()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        clearPendingFileChooserCapture(deleteFile = true)
        pendingFileChooserCaptureParams = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        val origin = pendingGeolocationOrigin
        val callback = pendingGeolocationCallback
        if (origin != null && callback != null) {
            callback.invoke(origin, false, false)
        }
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
        pendingBlobDownload?.tempFile?.delete()
        pendingBlobDownload = null
        activeWebViews.toList().forEach { webView ->
            unregisterWebView(webView)
            webView.destroySafely()
        }
        super.onDestroy()
    }

    private fun scheduleForegroundResumeWakeup() {
        cancelPendingForegroundResumeWakeup()
        val runnable = Runnable {
            pendingForegroundResumeRunnable = null
            if (!isActivityResumed) return@Runnable
            activeWebViews.toList().forEach { webView ->
                if (!isTrustedWebUrl(webView.url)) return@forEach
                runCatching {
                    webView.evaluateJavascript(FOREGROUND_RESUME_WAKEUP_SCRIPT, null)
                }.onFailure { Log.w(TAG, "Unable to dispatch foreground resume wakeup", it) }
            }
        }
        pendingForegroundResumeRunnable = runnable
        mainHandler.postDelayed(runnable, FOREGROUND_RESUME_DISPATCH_DELAY_MS)
    }

    private fun cancelPendingForegroundResumeWakeup() {
        pendingForegroundResumeRunnable?.let(mainHandler::removeCallbacks)
        pendingForegroundResumeRunnable = null
    }

    private fun registerWebView(webView: WebView) {
        activeWebViews += webView
        if (isActivityResumed) {
            runCatching {
                webView.onResume()
            }.onFailure { Log.w(TAG, "Unable to resume registered WebView", it) }
        }
    }

    private fun unregisterWebView(webView: WebView?) {
        if (webView != null) activeWebViews.remove(webView)
    }

    private fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        clearPendingFileChooserCapture(deleteFile = true)
        pendingFileChooserCaptureParams = null
        filePathCallback = callback

        val capturePermissions = capturePermissionsFor(params).filter { !isPermissionGranted(it) }
        if (capturePermissions.isNotEmpty()) {
            pendingFileChooserCaptureParams = params
            fileChooserCapturePermissionLauncher.launch(capturePermissions.toTypedArray())
            return true
        }

        return try {
            fileChooserLauncher.launch(createFileChooserIntent(params))
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No file chooser available", e)
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            pendingFileChooserCaptureParams = null
            clearPendingFileChooserCapture(deleteFile = true)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create file chooser", e)
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            pendingFileChooserCaptureParams = null
            clearPendingFileChooserCapture(deleteFile = true)
            false
        }
    }

    private fun capturePermissionsFor(params: WebChromeClient.FileChooserParams): Array<String> {
        if (
            !params.isCaptureEnabled ||
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        ) {
            return emptyArray()
        }

        return when (captureMediaTypeFor(params.acceptTypes)) {
            CaptureMediaType.IMAGE,
            CaptureMediaType.VIDEO -> arrayOf(Manifest.permission.CAMERA)
            CaptureMediaType.AUDIO -> arrayOf(Manifest.permission.RECORD_AUDIO)
            null -> emptyArray()
        }
    }

    private fun createFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        if (
            params.isCaptureEnabled &&
            params.mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        ) {
            createCaptureIntent(params)?.let { return it }
        }

        val intent = params.createIntent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        return Intent.createChooser(intent, getString(R.string.file_chooser_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createCaptureIntent(params: WebChromeClient.FileChooserParams): Intent? =
        when (captureMediaTypeFor(params.acceptTypes)) {
            CaptureMediaType.IMAGE -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putCaptureOutput(createCaptureOutputUri("images", ".jpg"))
            }
            CaptureMediaType.VIDEO -> Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putCaptureOutput(createCaptureOutputUri("videos", ".mp4"))
            }
            CaptureMediaType.AUDIO -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            null -> null
        }

    private fun Intent.putCaptureOutput(outputUri: Uri) {
        putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
        clipData = ClipData.newUri(contentResolver, "Capture output", outputUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun createCaptureOutputUri(directoryName: String, extension: String): Uri {
        val directory = File(externalCacheDir ?: cacheDir, "webview-captures/$directoryName")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Unable to create capture directory")
        }
        val file = File.createTempFile("capture_", extension, directory)
        pendingFileChooserCaptureFile = file
        pendingFileChooserCaptureUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        return pendingFileChooserCaptureUri ?: error("Unable to create capture URI")
    }

    private fun parseCaptureOutputUri(resultCode: Int): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        return pendingFileChooserCaptureUri?.let { arrayOf(it) }
    }

    private fun clearPendingFileChooserCapture(deleteFile: Boolean) {
        if (deleteFile) {
            runCatching { pendingFileChooserCaptureFile?.delete() }
        }
        pendingFileChooserCaptureUri = null
        pendingFileChooserCaptureFile = null
    }

    private fun parseSelectedFileUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let { uri ->
                    if (uri !in uris) uris += uri
                }
            }
        }
        data.data?.let { uri ->
            if (uri !in uris) uris += uri
        }
        if (uris.isEmpty()) {
            data.dataString
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.parse(it) }
                ?.let { uris += it }
        }

        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun requestWebPermission(request: PermissionRequest) {
        val supportedResources = request.resources
            .filter { androidPermissionsForWebResource(it) != null }
            .toTypedArray()
        if (supportedResources.isEmpty()) {
            Log.w(TAG, "Denying unsupported WebView permission resources: ${request.resources.joinToString()}")
            request.deny()
            return
        }

        val unsupportedResources = request.resources.filter { it !in supportedResources }
        if (unsupportedResources.isNotEmpty()) {
            Log.w(TAG, "Ignoring unsupported WebView permission resources: ${unsupportedResources.joinToString()}")
        }

        val permissions = supportedResources
            .flatMap { androidPermissionsForWebResource(it).orEmpty().asIterable() }
            .distinct()
            .toTypedArray()
        if (permissions.all { isPermissionGranted(it) }) {
            request.grant(supportedResources)
            return
        }

        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        pendingPermissionResources = supportedResources
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
        } finally {
            download.tempFile.delete()
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
            download.tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
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
            download.tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
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

    private fun androidPermissionsForWebResource(resource: String): Array<String>? =
        when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> arrayOf(Manifest.permission.CAMERA)
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> emptyArray()
            PermissionRequest.RESOURCE_MIDI_SYSEX -> null
            else -> null
        }
}

@Composable
private fun MainScreen(
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onBlobDownload: (BlobDownload) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    onWebViewDestroyed: (WebView?) -> Unit,
) {
    var fullScreenView: View? by remember { mutableStateOf(null) }
    var popupWebView: WebView? by remember { mutableStateOf(null) }

    Box(Modifier.fillMaxSize()) {
        WebViewWithRefresher(
            modifier = Modifier.fillMaxSize(),
            onCustomViewChanged = { fullScreenView = it },
            onShowFileChooser = onShowFileChooser,
            onPermissionRequest = onPermissionRequest,
            onGeolocationPermissionRequest = onGeolocationPermissionRequest,
            onBlobDownload = onBlobDownload,
            onWebViewCreated = onWebViewCreated,
            onWebViewDestroyed = onWebViewDestroyed,
            onPopupWebViewChanged = { nextPopup ->
                val previousPopup = popupWebView
                if (previousPopup != null && previousPopup !== nextPopup) {
                    onWebViewDestroyed(previousPopup)
                    previousPopup.destroySafely()
                }
                popupWebView = nextPopup
            },
        )

        popupWebView?.let { popup ->
            key(popup) {
                DisposableEffect(popup) {
                    onDispose {
                        onWebViewDestroyed(popup)
                        popup.destroySafely()
                    }
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { popup })
            }
        }
    }

    BackHandler(enabled = popupWebView != null) {
        val popup = popupWebView ?: return@BackHandler
        if (popup.canGoBack()) {
            popup.goBack()
        } else {
            onWebViewDestroyed(popup)
            popup.destroySafely()
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
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onBlobDownload: (BlobDownload) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    onWebViewDestroyed: (WebView?) -> Unit,
    onPopupWebViewChanged: (WebView?) -> Unit,
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    val webViewId = remember { View.generateViewId() }
    var webViewGeneration by remember { mutableStateOf(0) }
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

    key(webViewGeneration) {
        DisposableEffect(webViewGeneration) {
            onDispose {
                val currentWebView = webView
                if (currentWebView != null) {
                    onWebViewDestroyed(currentWebView)
                    currentWebView.destroySafely()
                    if (webView === currentWebView) webView = null
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
                onWebViewCreated(this)
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
                    onWebViewCreated = onWebViewCreated,
                    onWebViewDestroyed = onWebViewDestroyed,
                    onRenderProcessGone = { deadWebView ->
                        onCustomViewChanged(null)
                        if (deadWebView != null) {
                            onWebViewDestroyed(deadWebView)
                            deadWebView.destroySafely(afterRenderProcessGone = true)
                        }
                        if (deadWebView == null || deadWebView === webView) {
                            webView = null
                            spaCanGoBack = false
                            webViewGeneration += 1
                        } else {
                            onPopupWebViewChanged(null)
                        }
                    },
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
    onWebViewCreated: (WebView) -> Unit,
    onWebViewDestroyed: (WebView?) -> Unit,
    onRenderProcessGone: (WebView?) -> Unit,
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
    val webViewBridge = WebViewBridge(
        cacheDirectory = context.cacheDir,
        currentUrl = { this@configureLobeHubWebView.url },
        onBlobDownload = onBlobDownload,
        onSpaCanGoBackChanged = onSpaCanGoBackChanged,
    )
    webViewDownloadBridges[this] = webViewBridge
    addJavascriptInterface(webViewBridge, BLOB_DOWNLOAD_BRIDGE)

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            return handleExternalUri(context, view, uri)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            request?.url?.let { uri ->
                if (isTrustedAuthUrl(uri)) scheduleCookieFlush()
            }
            return super.shouldInterceptRequest(view, request)
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
                view?.evaluateJavascript(FOREGROUND_RESUME_BRIDGE_SCRIPT, null)
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

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            Log.e(
                TAG,
                "WebView renderer gone, didCrash=${detail?.didCrash()}, priority=${detail?.rendererPriorityAtExit()}"
            )
            swipeRefreshLayout?.isRefreshing = false
            onRenderProcessGone(view)
            return true
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?,
        ) {
            if (handler == null) return
            showHttpAuthDialog(context, view, handler, host, realm)
        }

        override fun onReceivedClientCertRequest(
            view: WebView?,
            request: ClientCertRequest?,
        ) {
            if (request == null) return
            chooseClientCertificate(view?.context ?: context, request)
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            if (handler == null) return
            showSslErrorDialog(view?.context ?: context, handler, error)
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
                onWebViewCreated(this)
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
                    onWebViewCreated = onWebViewCreated,
                    onWebViewDestroyed = onWebViewDestroyed,
                    onRenderProcessGone = onRenderProcessGone,
                )
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            onPopupWebViewChanged(popup)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            onPopupWebViewChanged(null)
            onWebViewDestroyed(window)
            window?.destroySafely()
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
            onBlobDownload = onBlobDownload,
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                this,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
            )
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
    onBlobDownload: (BlobDownload) -> Unit,
) {
    val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
    if (scheme == "blob") {
        downloadPageUrlFromPage(webView, url, guessDownloadFileName(url, contentDisposition, mimeType))
        return
    }

    if (scheme == "data") {
        downloadDataUrl(
            context = context,
            dataUrl = url,
            fileName = guessDownloadFileName("download", contentDisposition, mimeTypeFromDataUrl(url) ?: mimeType),
            fallbackMimeType = mimeType,
            onBlobDownload = onBlobDownload,
        )
        return
    }

    if (scheme != "http" && scheme != "https") {
        Log.w(TAG, "Unsupported WebView download URL: $url")
        Toast.makeText(context, R.string.download_not_supported, Toast.LENGTH_SHORT).show()
        return
    }

    val uri = Uri.parse(url)
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

private fun scheduleCookieFlush() {
    val handler = Handler(Looper.getMainLooper())
    handler.postDelayed({ CookieManager.getInstance().flush() }, 1_000L)
    handler.postDelayed({ CookieManager.getInstance().flush() }, 5_000L)
}

private fun downloadDataUrl(
    context: Context,
    dataUrl: String,
    fileName: String,
    fallbackMimeType: String?,
    onBlobDownload: (BlobDownload) -> Unit,
) {
    Thread {
        try {
            onBlobDownload(writeDataUrlToTempFile(context.cacheDir, dataUrl, fileName, fallbackMimeType))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to save data URL download", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

private fun writeDataUrlToTempFile(
    cacheDirectory: File,
    dataUrl: String,
    fileName: String,
    fallbackMimeType: String?,
): BlobDownload {
    require(dataUrl.startsWith("data:", ignoreCase = true)) { "Not a data URL" }
    val commaIndex = dataUrl.indexOf(',')
    require(commaIndex >= 0) { "Invalid data URL" }

    val metadata = dataUrl.substring(5, commaIndex)
    val payload = dataUrl.substring(commaIndex + 1)
    val mimeType = mimeTypeFromDataUrl(dataUrl)
        ?: fallbackMimeType?.takeIf { it.isNotBlank() }
        ?: DEFAULT_DOWNLOAD_MIME_TYPE
    val tempFile = createTempDownloadFile(cacheDirectory)

    try {
        FileOutputStream(tempFile).use { output ->
            if (metadata.split(';').any { it.equals("base64", ignoreCase = true) }) {
                Base64InputStream(
                    ByteArrayInputStream(payload.toByteArray(Charsets.US_ASCII)),
                    Base64.DEFAULT,
                ).use { input ->
                    input.copyTo(output)
                }
            } else {
                writePercentDecodedBytes(payload, output)
            }
        }
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }

    return BlobDownload(
        tempFile = tempFile,
        fileName = sanitizeDownloadFileName(fileName),
        mimeType = mimeType,
    )
}

private fun writePercentDecodedBytes(payload: String, output: OutputStream) {
    var index = 0
    while (index < payload.length) {
        val char = payload[index]
        if (char == '%' && index + 2 < payload.length) {
            val high = hexValue(payload[index + 1])
            val low = hexValue(payload[index + 2])
            if (high >= 0 && low >= 0) {
                output.write((high shl 4) or low)
                index += 3
                continue
            }
        }

        if (char.code <= 0x7F) {
            output.write(char.code)
        } else {
            output.write(char.toString().toByteArray(Charsets.UTF_8))
        }
        index++
    }
}

private fun hexValue(char: Char): Int =
    when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

private fun createTempDownloadFile(cacheDirectory: File): File {
    val directory = File(cacheDirectory, "webview-downloads")
    if (!directory.exists() && !directory.mkdirs()) {
        error("Unable to create temporary download directory")
    }
    return File.createTempFile("download_", ".tmp", directory)
}

private fun destroyWebViewSafely(webView: WebView?, afterRenderProcessGone: Boolean = false) {
    if (webView == null) return
    webViewDownloadBridges.remove(webView)?.cancelAllDownloads()
    runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
    if (!afterRenderProcessGone) {
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
    }
    runCatching {
        webView.removeJavascriptInterface(BLOB_DOWNLOAD_BRIDGE)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }.onFailure { Log.w(TAG, "Unable to destroy WebView", it) }
}

private fun WebView.destroySafely(afterRenderProcessGone: Boolean = false) {
    destroyWebViewSafely(this, afterRenderProcessGone)
}

private fun showHttpAuthDialog(
    context: Context,
    webView: WebView?,
    handler: HttpAuthHandler,
    host: String?,
    realm: String?,
) {
    val activity = context as? Activity
    if (activity == null || activity.isFinishing) {
        handler.cancel()
        return
    }

    val savedCredentials = if (!host.isNullOrBlank() && !realm.isNullOrBlank()) {
        @Suppress("DEPRECATION")
        webView?.getHttpAuthUsernamePassword(host, realm)
    } else {
        null
    }

    val density = activity.resources.displayMetrics.density
    val padding = (24 * density).toInt()
    val usernameInput = EditText(activity).apply {
        hint = activity.getString(R.string.http_auth_username_hint)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        setText(savedCredentials?.getOrNull(0).orEmpty())
    }
    val passwordInput = EditText(activity).apply {
        hint = activity.getString(R.string.http_auth_password_hint)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(savedCredentials?.getOrNull(1).orEmpty())
    }
    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
        addView(usernameInput)
        addView(passwordInput)
    }

    AlertDialog.Builder(activity)
        .setTitle(R.string.http_auth_title)
        .setMessage(activity.getString(R.string.http_auth_message, host.orEmpty(), realm.orEmpty()))
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            if (!host.isNullOrBlank() && !realm.isNullOrBlank()) {
                @Suppress("DEPRECATION")
                webView?.setHttpAuthUsernamePassword(host, realm, username, password)
            }
            handler.proceed(username, password)
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            handler.cancel()
        }
        .setOnCancelListener {
            handler.cancel()
        }
        .show()
}

private fun chooseClientCertificate(context: Context, request: ClientCertRequest) {
    val activity = context as? Activity
    if (activity == null || activity.isFinishing) {
        request.ignore()
        return
    }

    KeyChain.choosePrivateKeyAlias(
        activity,
        { alias ->
            if (alias.isNullOrBlank()) {
                request.ignore()
                return@choosePrivateKeyAlias
            }

            Thread {
                try {
                    val privateKey = KeyChain.getPrivateKey(activity, alias)
                    val certificateChain = KeyChain.getCertificateChain(activity, alias)
                    Handler(Looper.getMainLooper()).post {
                        if (privateKey != null && certificateChain != null) {
                            request.proceed(privateKey, certificateChain)
                        } else {
                            request.ignore()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to load client certificate", e)
                    Handler(Looper.getMainLooper()).post { request.ignore() }
                }
            }.start()
        },
        request.keyTypes,
        request.principals,
        request.host,
        request.port,
        null,
    )
}

private fun showSslErrorDialog(context: Context, handler: SslErrorHandler, error: SslError?) {
    val activity = context as? Activity
    if (activity == null || activity.isFinishing) {
        handler.cancel()
        return
    }

    Log.w(TAG, "SSL error ${error?.primaryError} for ${error?.url}")
    Handler(Looper.getMainLooper()).post {
        if (activity.isFinishing || activity.isDestroyed) {
            handler.cancel()
            return@post
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.ssl_error_title)
            .setMessage(activity.getString(R.string.ssl_error_message, error?.url.orEmpty()))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handler.cancel()
            }
            .setOnCancelListener {
                handler.cancel()
            }
            .show()
    }
}

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

private val FOREGROUND_RESUME_BRIDGE_SCRIPT = """
    (function () {
      if (window.__sevporForegroundBridgeInstalled) return;
      window.__sevporForegroundBridgeInstalled = true;
      window.__sevporLastVisibleAt = document.visibilityState === 'visible' ? Date.now() : 0;
      window.__sevporLastSessionCheckAt = Date.now();
      document.addEventListener(
        'visibilitychange',
        function () {
          if (document.visibilityState === 'visible') {
            window.__sevporLastVisibleAt = Date.now();
          }
        },
        true
      );
    })();
""".trimIndent()

private val FOREGROUND_RESUME_WAKEUP_SCRIPT = """
    (function () {
      try {
        var now = Date.now();
        if (
          document.visibilityState === 'visible' &&
          now - (window.__sevporLastVisibleAt || 0) > 1000
        ) {
          window.__sevporLastVisibleAt = now;
          document.dispatchEvent(new Event('visibilitychange'));
        }

        var SESSION_CHECK_INTERVAL_MS = $SESSION_REFRESH_CHECK_INTERVAL_MS;
        if (
          window.__sevporSessionRefreshInFlight ||
          now - (window.__sevporLastSessionCheckAt || 0) < SESSION_CHECK_INTERVAL_MS
        ) {
          return;
        }

        window.__sevporSessionRefreshInFlight = true;
        function clearInFlight() {
          window.__sevporSessionRefreshInFlight = false;
        }

        fetch('/api/auth/get-session', {
          method: 'GET',
          credentials: 'include',
          cache: 'no-store'
        })
          .then(function (response) {
            if (!response.ok) return null;
            window.__sevporLastSessionCheckAt = Date.now();
            return response.json().catch(function () {
              return null;
            });
          })
          .then(function (data) {
            if (data && data.needsRefresh === true) {
              return fetch('/api/auth/get-session', {
                method: 'POST',
                credentials: 'include',
                cache: 'no-store'
              });
            }
          })
          .catch(function () {})
          .then(clearInFlight, clearInFlight);
      } catch (e) {}
    })();
""".trimIndent()

private fun downloadPageUrlFromPage(webView: WebView, pageUrl: String, fileName: String) {
    if (!isTrustedWebUrl(webView.url)) {
        Log.w(TAG, "Blocked page download from untrusted page: ${webView.url}")
        return
    }
    val script = "window.__sevporDownloadPageUrl && window.__sevporDownloadPageUrl(" +
        "${JSONObject.quote(pageUrl)}, ${JSONObject.quote(fileName)});"
    webView.evaluateJavascript(script, null)
}

private class WebViewBridge(
    private val cacheDirectory: File,
    private val currentUrl: () -> String?,
    private val onBlobDownload: (BlobDownload) -> Unit,
    private val onSpaCanGoBackChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeDownloads = mutableMapOf<String, BlobDownloadSession>()

    @JavascriptInterface
    @Synchronized
    fun beginDownload(id: String?, mimeType: String?, fileName: String?): Boolean {
        if (!isTrustedWebUrl(currentUrl())) {
            Log.w(TAG, "Blocked download bridge call from untrusted page: ${currentUrl()}")
            return false
        }
        if (id.isNullOrBlank()) return false

        runCatching { activeDownloads.remove(id)?.close(deleteFile = true) }
        return try {
            val tempFile = createTempDownloadFile(cacheDirectory)
            activeDownloads[id] = BlobDownloadSession(
                tempFile = tempFile,
                output = FileOutputStream(tempFile),
                fileName = sanitizeDownloadFileName(fileName),
                mimeType = mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_MIME_TYPE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to begin page download", e)
            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun appendDownloadChunk(id: String?, base64Data: String?): Boolean {
        if (!isTrustedWebUrl(currentUrl())) {
            cancelDownload(id)
            Log.w(TAG, "Blocked download chunk from untrusted page: ${currentUrl()}")
            return false
        }
        if (id.isNullOrBlank() || base64Data.isNullOrBlank()) return false
        val session = activeDownloads[id] ?: return false

        try {
            session.output.write(Base64.decode(base64Data, Base64.DEFAULT))
            return true
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid download chunk", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to append download chunk", e)
        }

        activeDownloads.remove(id)?.close(deleteFile = true)
        return false
    }

    @JavascriptInterface
    @Synchronized
    fun finishDownload(id: String?): Boolean {
        if (!isTrustedWebUrl(currentUrl())) {
            cancelDownload(id)
            Log.w(TAG, "Blocked download finish from untrusted page: ${currentUrl()}")
            return false
        }
        if (id.isNullOrBlank()) return false
        val session = activeDownloads.remove(id) ?: return false

        return try {
            session.output.flush()
            session.output.close()
            onBlobDownload(
                BlobDownload(
                    tempFile = session.tempFile,
                    fileName = session.fileName,
                    mimeType = session.mimeType,
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to finish page download", e)
            session.close(deleteFile = true)
            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun cancelDownload(id: String?) {
        if (id.isNullOrBlank()) return
        activeDownloads.remove(id)?.close(deleteFile = true)
    }

    @Synchronized
    fun cancelAllDownloads() {
        activeDownloads.values.forEach { it.close(deleteFile = true) }
        activeDownloads.clear()
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
    val tempFile: File,
    val fileName: String,
    val mimeType: String,
)

private data class BlobDownloadSession(
    val tempFile: File,
    val output: FileOutputStream,
    val fileName: String,
    val mimeType: String,
) {
    fun close(deleteFile: Boolean) {
        runCatching { output.close() }
        if (deleteFile) runCatching { tempFile.delete() }
    }
}

private enum class CaptureMediaType {
    IMAGE,
    VIDEO,
    AUDIO,
}

private fun captureMediaTypeFor(acceptTypes: Array<String>): CaptureMediaType? {
    val types = acceptTypes
        .flatMap { it.split(',') }
        .map { it.substringBefore(';').trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() }

    if (types.isEmpty() || types.any { it == "*/*" }) return CaptureMediaType.IMAGE
    return when {
        types.any { it == "image/*" || it.startsWith("image/") } -> CaptureMediaType.IMAGE
        types.any { it == "video/*" || it.startsWith("video/") } -> CaptureMediaType.VIDEO
        types.any { it == "audio/*" || it.startsWith("audio/") } -> CaptureMediaType.AUDIO
        else -> null
    }
}

private fun guessDownloadFileName(
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): String = sanitizeDownloadFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))

private fun mimeTypeFromDataUrl(dataUrl: String): String? {
    if (!dataUrl.startsWith("data:", ignoreCase = true)) return null
    val commaIndex = dataUrl.indexOf(',')
    if (commaIndex < 0) return null

    return dataUrl.substring(5, commaIndex)
        .split(';')
        .firstOrNull { it.contains('/') }
        ?.takeIf { it.isNotBlank() }
}

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

private fun isTrustedAuthUrl(uri: Uri): Boolean =
    isTrustedWebUrl(uri.toString()) && uri.path?.startsWith("/api/auth/") == true

private val BLOB_DOWNLOAD_SCRIPT = """
    (function () {
      if (window.__sevporBlobDownloadInstalled) return;
      window.__sevporBlobDownloadInstalled = true;
      var CHUNK_BYTES = $BLOB_DOWNLOAD_CHUNK_BYTES;
      var BYTE_STRING_BATCH = 32768;

      function fileNameFromAnchor(anchor) {
        var fileName = anchor.getAttribute('download') || '';
        if (!fileName) fileName = 'download';
        return fileName;
      }

      function bridge() {
        return window.$BLOB_DOWNLOAD_BRIDGE;
      }

      function nextDownloadId() {
        return String(Date.now()) + '_' + String(Math.random()).slice(2);
      }

      function bytesToBase64(bytes) {
        var binary = '';
        for (var offset = 0; offset < bytes.length; offset += BYTE_STRING_BATCH) {
          var batch = bytes.subarray(offset, offset + BYTE_STRING_BATCH);
          binary += String.fromCharCode.apply(null, batch);
        }
        return btoa(binary);
      }

      function sendBytes(id, bytes) {
        var nativeBridge = bridge();
        for (var offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
          var chunk = bytes.subarray(offset, offset + CHUNK_BYTES);
          if (!nativeBridge.appendDownloadChunk(id, bytesToBase64(chunk))) {
            throw new Error('Native download chunk rejected');
          }
        }
      }

      function blobSlice(blob, start, end) {
        var slice = blob.slice || blob.webkitSlice || blob.mozSlice;
        if (typeof slice !== 'function') {
          throw new Error('Blob slicing unsupported');
        }
        return slice.call(blob, start, end);
      }

      function readBlobSlice(blob, start, end) {
        return new Promise(function (resolve, reject) {
          var reader = new FileReader();
          reader.onload = function () {
            resolve(reader.result);
          };
          reader.onerror = function () {
            reject(reader.error || new Error('FileReader failed'));
          };
          reader.onabort = function () {
            reject(new Error('FileReader aborted'));
          };
          reader.readAsArrayBuffer(blobSlice(blob, start, end));
        });
      }

      function sendWithFileReader(id, blob) {
        var offset = 0;
        function readNext() {
          if (offset >= blob.size) return Promise.resolve();
          var end = Math.min(offset + CHUNK_BYTES, blob.size);
          return readBlobSlice(blob, offset, end).then(function (buffer) {
            sendBytes(id, new Uint8Array(buffer));
            offset = end;
            return readNext();
          });
        }
        return readNext();
      }

      function sendStream(id, blob) {
        if (blob.stream && window.ReadableStream) {
          var reader = blob.stream().getReader();
          function pump() {
            return reader.read().then(function (result) {
              if (result.done) return;
              if (result.value) sendBytes(id, result.value);
              return pump();
            });
          }
          return pump();
        }

        if (typeof blob.arrayBuffer === 'function') {
          return blob.arrayBuffer().then(function (buffer) {
            sendBytes(id, new Uint8Array(buffer));
          });
        }

        return sendWithFileReader(id, blob);
      }

      function sendPageDownload(pageUrl, fileName) {
        return fetch(pageUrl)
          .then(function (response) {
            return response.blob();
          })
          .then(function (blob) {
            var id = nextDownloadId();
            var nativeBridge = bridge();
            if (!nativeBridge || typeof nativeBridge.beginDownload !== 'function') {
              throw new Error('Native download bridge missing');
            }
            if (!nativeBridge.beginDownload(
              id,
              blob.type || '$DEFAULT_DOWNLOAD_MIME_TYPE',
              fileName || 'download'
            )) {
              throw new Error('Native download rejected');
            }
            return sendStream(id, blob)
              .then(function () {
                nativeBridge.finishDownload(id);
              })
              .catch(function (error) {
                nativeBridge.cancelDownload(id);
                throw error;
              });
          })
          .catch(function (error) {
            console.error('Page download failed', error);
          });
      }

      window.__sevporDownloadPageUrl = sendPageDownload;

      document.addEventListener(
        'click',
        function (event) {
          var target = event.target;
          if (!target || !target.closest) return;
          var anchor =
            target.closest('a[href^="blob:"]') ||
            target.closest('a[href^="data:"][download]');
          if (!anchor) return;
          event.preventDefault();
          sendPageDownload(anchor.href, fileNameFromAnchor(anchor));
        },
        true
      );
    })();
""".trimIndent()

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
