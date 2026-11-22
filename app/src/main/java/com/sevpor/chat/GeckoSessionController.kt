package com.sevpor.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaCallback
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val BRIDGE_GO_BACK_TIMEOUT_MS = 300L
private const val EXTENSION_READY_TIMEOUT_MS = 800L
private const val FOREGROUND_RESUME_DISPATCH_DELAY_MS = 150L

data class MainLoadErrorState(
    val uri: String?,
    val category: Int,
    val code: Int,
)

class GeckoSessionController(
    private val activity: Activity,
    private val runtime: GeckoRuntime,
    private val promptHandler: GeckoPromptHandler,
    private val downloadHandler: DownloadHandler,
    private val requestRuntimePermissions: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit,
    private val onFullScreenChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    val mainSession: GeckoSession = GeckoSession(GeckoRuntimeHolder.defaultSessionSettings())

    var popupSession: GeckoSession? by mutableStateOf(null)
        private set

    var mainCanGoBack: Boolean by mutableStateOf(false)
        private set

    var popupCanGoBack: Boolean by mutableStateOf(false)
        private set

    var sevporNavCanGoBack: Boolean by mutableStateOf(false)
        private set

    var isFullScreen: Boolean by mutableStateOf(false)
        private set

    var mainLoadError: MainLoadErrorState? by mutableStateOf(null)
        private set

    private var attachedView: GeckoView? = null
    private var lastMainUrl: String? = WEBSITE
    private var lastPopupUrl: String? = null
    private var hasLoadedMainPageOnce = false
    private var isStarted = false
    private var isResumed = false
    private var isClosed = false

    private var mainBridgePort: WebExtension.Port? = null
    private var popupBridgePort: WebExtension.Port? = null
    private var pendingGoBackRequestId: String? = null
    private var pendingGoBackFallback: (() -> Unit)? = null
    private var pendingGoBackTimeout: Runnable? = null
    private var pendingInitialLoadTimeout: Runnable? = null
    private var pendingForegroundResumeRunnable: Runnable? = null

    init {
        configureSession(mainSession, isPopup = false)
        mainSession.open(runtime)
        startInitialLoadWithExtension()
    }

    fun attachView(view: GeckoView, session: GeckoSession) {
        if (isClosed) return
        if (attachedView !== view) {
            attachedView?.let { previous ->
                if (previous.session != null) {
                    previous.releaseSession()
                }
            }
            attachedView = view
        }
        if (view.session !== session) {
            if (view.session != null) {
                view.releaseSession()
            }
            view.setSession(session)
        }
        syncActiveState()
    }

    fun detachView(view: GeckoView) {
        if (view.session != null) {
            view.releaseSession()
        }
        if (attachedView === view) {
            attachedView = null
        }
    }

    fun setActive(active: Boolean) {
        isStarted = active
        syncActiveState()
    }

    fun setFocused(focused: Boolean) {
        isResumed = focused
        syncActiveState()
    }

    fun scheduleForegroundResume() {
        cancelForegroundResume()
        val runnable = Runnable {
            pendingForegroundResumeRunnable = null
            if (isClosed || !isResumed) return@Runnable
            val port = mainBridgePort ?: return@Runnable
            if (!isTrustedWebUrl(lastMainUrl)) return@Runnable
            runCatching {
                port.postMessage(JSONObject().put("type", "foregroundResume"))
            }.onFailure { Log.w(TAG, "Unable to send foregroundResume to sevpor_bridge", it) }
        }
        pendingForegroundResumeRunnable = runnable
        mainHandler.postDelayed(runnable, FOREGROUND_RESUME_DISPATCH_DELAY_MS)
    }

    fun cancelForegroundResume() {
        pendingForegroundResumeRunnable?.let(mainHandler::removeCallbacks)
        pendingForegroundResumeRunnable = null
    }

    fun retryMainLoad() {
        if (isClosed) return
        val targetUrl = mainLoadError?.uri?.takeIf { it.isNotBlank() } ?: lastMainUrl ?: WEBSITE
        mainLoadError = null
        if (!mainSession.isOpen) {
            mainSession.open(currentRuntime())
        }
        mainSession.loadUri(targetUrl)
        syncActiveState()
    }

    fun exitFullScreen() {
        val target = popupSession ?: mainSession
        if (target.isOpen) {
            target.exitFullScreen()
        }
        if (isFullScreen) {
            isFullScreen = false
            onFullScreenChanged(false)
        }
    }

    fun navigateBackPopup() {
        val popup = popupSession ?: return
        if (popupCanGoBack && popup.isOpen) {
            popup.goBack()
        } else {
            closePopupSession()
        }
    }

    fun navigateBackMain(onNoBackStack: () -> Unit) {
        val currentError = mainLoadError
        if (currentError != null) {
            if (mainCanGoBack && mainSession.isOpen) {
                mainLoadError = null
                sevporNavCanGoBack = false
                mainSession.goBack()
                return
            }
            if (hasLoadedMainPageOnce && currentError.uri != lastMainUrl) {
                mainLoadError = null
                return
            }
            val homeTarget = lastMainUrl?.takeIf { it.isNotBlank() } ?: WEBSITE
            if (currentError.uri != homeTarget && mainSession.isOpen) {
                mainLoadError = null
                mainSession.loadUri(homeTarget)
                return
            }
            onNoBackStack()
            return
        }

        val fallback = {
            if (mainCanGoBack && mainSession.isOpen) {
                mainLoadError = null
                sevporNavCanGoBack = false
                mainSession.goBack()
            } else {
                onNoBackStack()
            }
        }

        val port = mainBridgePort
        if (sevporNavCanGoBack && port != null) {
            cancelPendingGoBack()
            val requestId = UUID.randomUUID().toString()
            pendingGoBackRequestId = requestId
            pendingGoBackFallback = fallback

            val timeoutRunnable = Runnable {
                if (pendingGoBackRequestId == requestId) {
                    val cb = pendingGoBackFallback
                    cancelPendingGoBack()
                    sevporNavCanGoBack = false
                    cb?.invoke()
                }
            }
            pendingGoBackTimeout = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, BRIDGE_GO_BACK_TIMEOUT_MS)

            runCatching {
                port.postMessage(
                    JSONObject()
                        .put("type", "goBack")
                        .put("requestId", requestId),
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to send goBack message to sevpor_bridge", error)
                cancelPendingGoBack()
                sevporNavCanGoBack = false
                fallback()
            }
            return
        }

        fallback()
    }

    fun closePopupSession() {
        val popup = popupSession ?: return
        if (isFullScreen) {
            isFullScreen = false
            onFullScreenChanged(false)
        }
        runCatching { popupBridgePort?.disconnect() }
        popupBridgePort = null
        lastPopupUrl = null

        val view = attachedView
        if (view != null && view.session === popup) {
            view.releaseSession()
        }
        popupSession = null
        popupCanGoBack = false
        if (popup.isOpen) {
            runCatching { popup.close() }
        }
        if (!isClosed && view != null && mainSession.isOpen && view.session !== mainSession) {
            view.setSession(mainSession)
        }
        syncActiveState()
    }

    fun closeAll() {
        isClosed = true
        pendingInitialLoadTimeout?.let(mainHandler::removeCallbacks)
        pendingInitialLoadTimeout = null
        cancelForegroundResume()
        cancelPendingGoBack()
        runCatching { mainBridgePort?.disconnect() }
        mainBridgePort = null
        runCatching { popupBridgePort?.disconnect() }
        popupBridgePort = null

        closePopupSession()

        attachedView?.let { view ->
            if (view.session != null) {
                view.releaseSession()
            }
        }
        attachedView = null
        if (mainSession.isOpen) {
            runCatching { mainSession.close() }
        }
    }

    private fun startInitialLoadWithExtension() {
        val initialLoadStarted = AtomicBoolean(false)
        val startLoadOnce = {
            if (!isClosed && initialLoadStarted.compareAndSet(false, true)) {
                pendingInitialLoadTimeout?.let(mainHandler::removeCallbacks)
                pendingInitialLoadTimeout = null
                if (mainSession.isOpen) {
                    mainSession.loadUri(WEBSITE)
                }
            }
        }

        val timeoutRunnable = Runnable {
            if (!isClosed && initialLoadStarted.get().not()) {
                Log.w(TAG, "Built-in extension install timed out; loading homepage without waiting")
                startLoadOnce()
            }
        }
        pendingInitialLoadTimeout = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, EXTENSION_READY_TIMEOUT_MS)

        attachBuiltInExtensionBridge(mainSession, isPopup = false) {
            startLoadOnce()
        }
    }

    private fun syncActiveState() {
        if (isClosed) return
        val popup = popupSession
        if (popup != null) {
            if (mainSession.isOpen) {
                mainSession.setActive(false)
                mainSession.setFocused(false)
            }
            if (popup.isOpen) {
                popup.setActive(isStarted)
                popup.setFocused(isResumed)
            }
        } else if (mainSession.isOpen) {
            mainSession.setActive(isStarted)
            mainSession.setFocused(isResumed)
        }
    }

    private fun configureSession(session: GeckoSession, isPopup: Boolean) {
        session.promptDelegate = promptHandler
        session.navigationDelegate = createNavigationDelegate(isPopup)
        session.contentDelegate = createContentDelegate(isPopup)
        session.progressDelegate = createProgressDelegate(isPopup)
        session.permissionDelegate = createPermissionDelegate()
    }

    private fun createNavigationDelegate(isPopup: Boolean): NavigationDelegate =
        object : NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                    ?: return GeckoResult.allow()
                return when (uri.scheme?.lowercase(Locale.US)) {
                    "http", "https", "about", "data", "blob", "javascript" -> GeckoResult.allow()
                    "intent" -> {
                        openIntentUri(activity, session, uri)
                        GeckoResult.deny()
                    }
                    else -> {
                        openExternalUri(activity, uri)
                        GeckoResult.deny()
                    }
                }
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession> {
                closePopupSession()
                val newPopup = GeckoSession(GeckoRuntimeHolder.defaultSessionSettings())
                configureSession(newPopup, isPopup = true)
                attachBuiltInExtensionBridge(newPopup, isPopup = true)
                popupCanGoBack = false
                lastPopupUrl = uri
                popupSession = newPopup
                if (mainSession.isOpen) {
                    mainSession.setActive(false)
                    mainSession.setFocused(false)
                }
                // GeckoView opens the returned session automatically; do not call open(runtime) here.
                return GeckoResult.fromValue(newPopup)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (isPopup) {
                    if (session === popupSession) popupCanGoBack = canGoBack
                } else {
                    mainCanGoBack = canGoBack
                }
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url.isNullOrBlank()) return
                if (isPopup) {
                    if (session === popupSession) lastPopupUrl = url
                } else {
                    lastMainUrl = url
                    if (!isTrustedWebUrl(url)) {
                        sevporNavCanGoBack = false
                    }
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                Log.e(TAG, "Load error category=${error.category} code=${error.code} uri=$uri")
                if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) {
                    showSslErrorDialog(uri)
                }
                if (!isPopup && session === mainSession) {
                    mainLoadError = MainLoadErrorState(
                        uri = uri ?: lastMainUrl ?: WEBSITE,
                        category = error.category,
                        code = error.code,
                    )
                }
                return null
            }
        }

    private fun createContentDelegate(isPopup: Boolean): ContentDelegate =
        object : ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val requestPageDownload: (String, String) -> Unit = { blobUrl, fileName ->
                    val port = if (isPopup) popupBridgePort else mainBridgePort
                    val currentUrl = if (isPopup) lastPopupUrl else lastMainUrl
                    if (port != null && isTrustedWebUrl(currentUrl)) {
                        runCatching {
                            port.postMessage(
                                JSONObject()
                                    .put("type", "downloadPageUrl")
                                    .put("url", blobUrl)
                                    .put("fileName", fileName),
                            )
                        }.onFailure { Log.w(TAG, "Unable to trigger page blob download", it) }
                    }
                }
                downloadHandler.handleExternalResponse(response, requestPageDownload)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                isFullScreen = fullScreen
                onFullScreenChanged(fullScreen)
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (isPopup && session === popupSession) {
                    closePopupSession()
                }
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "Gecko content process crashed (isPopup=$isPopup)")
                recoverSession(session, isPopup)
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "Gecko content process killed (isPopup=$isPopup)")
                recoverSession(session, isPopup)
            }
        }

    private fun createProgressDelegate(isPopup: Boolean): ProgressDelegate =
        object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                Log.d(TAG, "Loading $url (isPopup=$isPopup)")
                if (isPopup && session === popupSession) {
                    syncActiveState()
                } else if (!isPopup) {
                    mainLoadError = null
                    sevporNavCanGoBack = false
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    if (!isPopup && session === mainSession) {
                        hasLoadedMainPageOnce = true
                    }
                } else {
                    Log.w(TAG, "Page load stopped unsuccessfully (isPopup=$isPopup)")
                }
            }
        }

    private fun createPermissionDelegate(): PermissionDelegate =
        object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: PermissionDelegate.Callback,
            ) {
                val requested = permissions?.filter { it.isNotBlank() }.orEmpty().toTypedArray()
                if (requested.isEmpty() || requested.all { isPermissionGranted(it) }) {
                    callback.grant()
                    return
                }

                requestRuntimePermissions(requested) { result ->
                    if (requested.all { isPermissionGranted(it, result) }) {
                        callback.grant()
                    } else {
                        callback.reject()
                    }
                }
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: ContentPermission,
            ): GeckoResult<Int> {
                return when (perm.permission) {
                    PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                        GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                    }
                    PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> {
                        val value = if (isTrustedWebUrl(perm.uri)) {
                            ContentPermission.VALUE_ALLOW
                        } else {
                            ContentPermission.VALUE_DENY
                        }
                        GeckoResult.fromValue(value)
                    }
                    PermissionDelegate.PERMISSION_GEOLOCATION -> {
                        if (locationPermissions.any { isPermissionGranted(it) }) {
                            GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                        } else {
                            val result = GeckoResult<Int>()
                            requestRuntimePermissions(locationPermissions) { grantResults ->
                                val granted = locationPermissions.any { isPermissionGranted(it, grantResults) }
                                result.complete(
                                    if (granted) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
                                )
                            }
                            result
                        }
                    }
                    else -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out MediaSource>?,
                audio: Array<out MediaSource>?,
                callback: MediaCallback,
            ) {
                val videoSources = video?.takeIf { it.isNotEmpty() }
                val audioSources = audio?.takeIf { it.isNotEmpty() }
                val neededPermissions = buildList {
                    if (videoSources != null) add(Manifest.permission.CAMERA)
                    if (audioSources != null) add(Manifest.permission.RECORD_AUDIO)
                }.toTypedArray()

                val finishGrant: (Map<String, Boolean>) -> Unit = { results ->
                    val selectedVideo = if (
                        videoSources != null && isPermissionGranted(Manifest.permission.CAMERA, results)
                    ) {
                        videoSources.firstOrNull { it.source == MediaSource.SOURCE_CAMERA }
                            ?: videoSources.firstOrNull()
                    } else {
                        null
                    }
                    val selectedAudio = if (
                        audioSources != null && isPermissionGranted(Manifest.permission.RECORD_AUDIO, results)
                    ) {
                        audioSources.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
                            ?: audioSources.firstOrNull()
                    } else {
                        null
                    }

                    if (selectedVideo != null || selectedAudio != null) {
                        callback.grant(selectedVideo, selectedAudio)
                    } else {
                        callback.reject()
                    }
                }

                if (neededPermissions.isEmpty() || neededPermissions.all { isPermissionGranted(it) }) {
                    finishGrant(emptyMap())
                    return
                }

                requestRuntimePermissions(neededPermissions, finishGrant)
            }
        }

    private fun attachBuiltInExtensionBridge(
        session: GeckoSession,
        isPopup: Boolean,
        onReady: (() -> Unit)? = null,
    ) {
        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                if (!isTrustedWebUrl(port.sender.url) || !port.sender.isTopLevel()) {
                    Log.w(TAG, "Rejected sevpor_bridge port from untrusted sender: ${port.sender.url}")
                    port.disconnect()
                    return
                }
                if (isPopup) {
                    if (popupBridgePort !== port) {
                        runCatching { popupBridgePort?.disconnect() }
                        popupBridgePort = port
                    }
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onDisconnect(p: WebExtension.Port) {
                            if (p === popupBridgePort) {
                                popupBridgePort = null
                            }
                        }
                    })
                    return
                }

                if (mainBridgePort !== port) {
                    runCatching { mainBridgePort?.disconnect() }
                    mainBridgePort = port
                }
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, p: WebExtension.Port) {
                        if (p !== mainBridgePort || !isTrustedWebUrl(p.sender.url)) return
                        val json = message as? JSONObject ?: return
                        when (json.optString("type")) {
                            "sevporNavState" -> {
                                sevporNavCanGoBack = json.optBoolean("canGoBack", false)
                            }
                            "goBackResult" -> {
                                val requestId = json.optString("requestId")
                                val handled = json.optBoolean("handled", false)
                                if (requestId == pendingGoBackRequestId) {
                                    val fallback = pendingGoBackFallback
                                    cancelPendingGoBack()
                                    if (!handled) {
                                        sevporNavCanGoBack = false
                                        fallback?.invoke()
                                    }
                                }
                            }
                        }
                    }

                    override fun onDisconnect(p: WebExtension.Port) {
                        if (p === mainBridgePort) {
                            mainBridgePort = null
                            sevporNavCanGoBack = false
                            val fallback = pendingGoBackFallback
                            cancelPendingGoBack()
                            fallback?.invoke()
                        }
                    }
                })
            }

            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ): GeckoResult<Any> {
                if (!isTrustedWebUrl(sender.url)) {
                    Log.w(TAG, "Blocked sevpor_bridge message from untrusted URL: ${sender.url}")
                    return GeckoResult.fromValue(JSONObject().put("ok", false))
                }
                val json = message as? JSONObject
                    ?: return GeckoResult.fromValue(JSONObject().put("ok", false))

                val result = GeckoResult<Any>()
                downloadHandler.handleBridgeMessage(
                    type = json.optString("type"),
                    id = json.optString("id").takeIf { it.isNotBlank() },
                    mimeType = json.optString("mimeType").takeIf { it.isNotBlank() },
                    fileName = json.optString("fileName").takeIf { it.isNotBlank() },
                    base64Data = json.optString("data").takeIf { it.isNotBlank() },
                ) { ok ->
                    result.complete(JSONObject().put("ok", ok))
                }
                return result
            }
        }

        GeckoRuntimeHolder.withBuiltInExtensionReady { extension ->
            if (!isClosed && extension != null) {
                session.webExtensionController.setMessageDelegate(
                    extension,
                    messageDelegate,
                    EXTENSION_NATIVE_APP,
                )
            }
            onReady?.invoke()
        }
    }

    private fun recoverSession(session: GeckoSession, isPopup: Boolean) {
        if (isClosed) return
        if (isPopup) {
            closePopupSession()
            return
        }

        if (isFullScreen) {
            isFullScreen = false
            onFullScreenChanged(false)
        }
        mainLoadError = null
        cancelPendingGoBack()
        mainBridgePort = null
        sevporNavCanGoBack = false

        if (!session.isOpen) {
            session.open(currentRuntime())
        }
        session.loadUri(lastMainUrl ?: WEBSITE)
        syncActiveState()
    }

    private fun currentRuntime(): GeckoRuntime =
        GeckoRuntimeHolder.getOrCreate(activity.applicationContext)

    private fun cancelPendingGoBack() {
        pendingGoBackTimeout?.let(mainHandler::removeCallbacks)
        pendingGoBackTimeout = null
        pendingGoBackRequestId = null
        pendingGoBackFallback = null
    }

    private fun showSslErrorDialog(url: String?) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            AlertDialog.Builder(activity)
                .setTitle(R.string.ssl_error_title)
                .setMessage(activity.getString(R.string.ssl_error_message, url.orEmpty()))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun isPermissionGranted(
        permission: String,
        result: Map<String, Boolean> = emptyMap(),
    ): Boolean =
        result[permission] == true || ContextCompat.checkSelfPermission(
            activity,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
}

private fun openIntentUri(context: Context, session: GeckoSession, uri: Uri) {
    try {
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
        try {
            launchExternalActivity(context, intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to browser_fallback_url if no matching app is installed.
        }
        intent.getStringExtra("browser_fallback_url")?.let { fallbackUrl ->
            if (session.isOpen) {
                session.loadUri(fallbackUrl)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open intent URI: $uri", e)
    }
}

private fun openExternalUri(context: Context, uri: Uri) {
    try {
        launchExternalActivity(context, Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app can open URI: $uri", e)
    }
}

private fun launchExternalActivity(context: Context, intent: Intent) {
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
