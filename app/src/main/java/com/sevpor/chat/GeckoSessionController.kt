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

private const val BRIDGE_GO_BACK_TIMEOUT_MS = 300L

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

    private var mainView: GeckoView? = null
    private var popupView: GeckoView? = null
    private var lastMainUrl: String? = WEBSITE
    private var isResumed = false
    private var isClosed = false

    private var bridgePort: WebExtension.Port? = null
    private var pendingGoBackRequestId: String? = null
    private var pendingGoBackFallback: (() -> Unit)? = null
    private var pendingGoBackTimeout: Runnable? = null

    init {
        configureSession(mainSession, isPopup = false)
        attachBuiltInExtensionBridge(mainSession)
        mainSession.open(runtime)
        mainSession.loadUri(WEBSITE)
    }

    fun attachMainView(view: GeckoView) {
        if (mainView !== view) {
            mainView?.let { previous ->
                if (previous.session === mainSession) {
                    previous.releaseSession()
                }
            }
            mainView = view
        }
        if (view.session !== mainSession) {
            view.setSession(mainSession)
        }
        syncActiveState()
    }

    fun detachMainView(view: GeckoView) {
        if (view.session === mainSession) {
            view.releaseSession()
        }
        if (mainView === view) {
            mainView = null
        }
    }

    fun attachPopupView(view: GeckoView, session: GeckoSession) {
        if (popupView !== view) {
            popupView?.let { previous ->
                if (previous.session === session) {
                    previous.releaseSession()
                }
            }
            popupView = view
        }
        if (view.session !== session) {
            view.setSession(session)
        }
        syncActiveState()
    }

    fun detachPopupView(view: GeckoView, session: GeckoSession) {
        if (view.session === session) {
            view.releaseSession()
        }
        if (popupView === view) {
            popupView = null
        }
    }

    fun setActive(active: Boolean) {
        isResumed = active
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
        val fallback = {
            if (mainCanGoBack && mainSession.isOpen) {
                sevporNavCanGoBack = false
                mainSession.goBack()
            } else {
                onNoBackStack()
            }
        }

        val port = bridgePort
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
        popupView?.let { view ->
            if (view.session === popup) {
                view.releaseSession()
            }
        }
        popupView = null
        popupSession = null
        popupCanGoBack = false
        if (popup.isOpen) {
            runCatching { popup.close() }
        }
        syncActiveState()
    }

    fun closeAll() {
        isClosed = true
        cancelPendingGoBack()
        runCatching { bridgePort?.disconnect() }
        bridgePort = null

        closePopupSession()

        mainView?.let { view ->
            if (view.session === mainSession) {
                view.releaseSession()
            }
        }
        mainView = null
        if (mainSession.isOpen) {
            runCatching { mainSession.close() }
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
                popup.setActive(isResumed)
                popup.setFocused(isResumed)
            }
        } else if (mainSession.isOpen) {
            mainSession.setActive(isResumed)
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
                    "http", "https", "about", "data", "blob" -> GeckoResult.allow()
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
                popupCanGoBack = false
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
                if (!isPopup && !url.isNullOrBlank()) {
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
                return null
            }
        }

    private fun createContentDelegate(isPopup: Boolean): ContentDelegate =
        object : ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val requestPageDownload: ((String, String) -> Unit)? = if (!isPopup) {
                    { blobUrl, fileName ->
                        val port = bridgePort
                        if (port != null && isTrustedWebUrl(lastMainUrl)) {
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
                } else {
                    null
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
                    sevporNavCanGoBack = false
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
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
                val neededPermissions = buildList {
                    if (!video.isNullOrEmpty()) add(Manifest.permission.CAMERA)
                    if (!audio.isNullOrEmpty()) add(Manifest.permission.RECORD_AUDIO)
                }.toTypedArray()

                val finishGrant: (Map<String, Boolean>) -> Unit = { results ->
                    val canUseVideo = !video.isNullOrEmpty() && isPermissionGranted(Manifest.permission.CAMERA, results)
                    val canUseAudio = !audio.isNullOrEmpty() && isPermissionGranted(Manifest.permission.RECORD_AUDIO, results)

                    val selectedVideo = if (canUseVideo && video != null) {
                        video.firstOrNull { it.source == MediaSource.SOURCE_CAMERA } ?: video.firstOrNull()
                    } else {
                        null
                    }
                    val selectedAudio = if (canUseAudio && audio != null) {
                        audio.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE } ?: audio.firstOrNull()
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

    private fun attachBuiltInExtensionBridge(session: GeckoSession) {
        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                if (!isTrustedWebUrl(port.sender.url) || !port.sender.isTopLevel()) {
                    Log.w(TAG, "Rejected sevpor_bridge port from untrusted sender: ${port.sender.url}")
                    port.disconnect()
                    return
                }
                if (bridgePort !== port) {
                    runCatching { bridgePort?.disconnect() }
                    bridgePort = port
                }
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, p: WebExtension.Port) {
                        if (p !== bridgePort || !isTrustedWebUrl(p.sender.url)) return
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
                        if (p === bridgePort) {
                            bridgePort = null
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

        GeckoRuntimeHolder.withBuiltInExtension { extension ->
            if (!isClosed) {
                session.webExtensionController.setMessageDelegate(
                    extension,
                    messageDelegate,
                    EXTENSION_NATIVE_APP,
                )
            }
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
        cancelPendingGoBack()
        bridgePort = null
        sevporNavCanGoBack = false

        if (!session.isOpen) {
            session.open(runtime)
        }
        session.loadUri(lastMainUrl ?: WEBSITE)
        syncActiveState()
    }

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
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        if (intent.resolveActivity(context.packageManager) != null) {
            launchExternalActivity(context, intent)
            return
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
