package com.sevpor.chat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension

internal const val WEBSITE = "https://chat.sevpor.com"
internal const val TAG = "SevporGeckoView"
internal const val EXTENSION_ID = "sevpor-bridge@sevpor.com"
internal const val EXTENSION_LOCATION = "resource://android/assets/extensions/sevpor_bridge/"
internal const val EXTENSION_NATIVE_APP = "sevpor_bridge"

internal fun isTrustedWebUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    val trustedHost = Uri.parse(WEBSITE).host?.lowercase() ?: return false
    return host == trustedHost || host.endsWith(".$trustedHost")
}

object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var builtInExtension: WebExtension? = null

    private var extensionInstallStarted = false
    private val extensionCallbacks = mutableListOf<(WebExtension?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getOrNull(): GeckoRuntime? = runtime

    fun getOrCreate(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime?.let { return it }

            val appContext = context.applicationContext
            val contentBlocking = ContentBlocking.Settings.Builder()
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .build()

            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                .consoleOutput(BuildConfig.DEBUG)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                .forceUserScalableEnabled(false)
                .doubleTapZoomingEnabled(false)
                .inputAutoZoomEnabled(false)
                .enterpriseRootsEnabled(true)
                .contentBlocking(contentBlocking)
                .build()

            val created = GeckoRuntime.create(appContext, settings)
            created.delegate = GeckoRuntime.Delegate {
                Log.w(TAG, "GeckoRuntime shut down unexpectedly; clearing holder state")
                val pending = synchronized(this) {
                    if (runtime === created) {
                        runtime = null
                        builtInExtension = null
                        extensionInstallStarted = false
                    }
                    val list = extensionCallbacks.toList()
                    extensionCallbacks.clear()
                    list
                }
                if (pending.isNotEmpty()) {
                    runOnMain { pending.forEach { it(null) } }
                }
            }
            runtime = created
            ensureBuiltInExtensionLocked(created)
            created
        }
    }

    fun withBuiltInExtensionReady(callback: (WebExtension?) -> Unit) {
        val existing = builtInExtension
        if (existing != null) {
            runOnMain { callback(existing) }
            return
        }

        val currentRuntime = synchronized(this) {
            builtInExtension?.let { ext ->
                runOnMain { callback(ext) }
                return
            }
            extensionCallbacks += callback
            runtime
        }

        if (currentRuntime != null) {
            synchronized(this) {
                if (!extensionInstallStarted) {
                    ensureBuiltInExtensionLocked(currentRuntime)
                }
            }
        } else {
            val removed = synchronized(this) { extensionCallbacks.remove(callback) }
            if (removed) {
                runOnMain { callback(null) }
            }
        }
    }

    fun defaultSessionSettings(): GeckoSessionSettings =
        GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .useTrackingProtection(false)
            .suspendMediaWhenInactive(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

    private fun ensureBuiltInExtensionLocked(geckoRuntime: GeckoRuntime) {
        if (extensionInstallStarted) return
        extensionInstallStarted = true
        runOnMain {
            geckoRuntime.webExtensionController
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept(
                    { extension ->
                        if (extension == null) {
                            Log.e(TAG, "Built-in extension returned null")
                            val pending = synchronized(this) {
                                extensionInstallStarted = false
                                val list = extensionCallbacks.toList()
                                extensionCallbacks.clear()
                                list
                            }
                            pending.forEach { it(null) }
                            return@accept
                        }
                        val pending = synchronized(this) {
                            builtInExtension = extension
                            val list = extensionCallbacks.toList()
                            extensionCallbacks.clear()
                            list
                        }
                        pending.forEach { it(extension) }
                    },
                    { error ->
                        Log.e(TAG, "Failed to install built-in extension", error)
                        val pending = synchronized(this) {
                            extensionInstallStarted = false
                            val list = extensionCallbacks.toList()
                            extensionCallbacks.clear()
                            list
                        }
                        pending.forEach { it(null) }
                    },
                )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
