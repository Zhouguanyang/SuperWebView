package com.sevpor.chat

import android.Manifest
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sevpor.chat.ui.theme.SuperWebViewTheme

class MainActivity : ComponentActivity() {

    private lateinit var downloadHandler: DownloadHandler
    private lateinit var fileChooserHandler: FileChooserHandler
    private lateinit var promptHandler: GeckoPromptHandler
    private lateinit var sessionController: GeckoSessionController

    private var wasPaused = false
    private val permissionQueue = ArrayDeque<PendingPermissionRequest>()
    private var activePermissionRequest: PendingPermissionRequest? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileChooserHandler.onActivityResult(result.resultCode, result.data)
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val current = activePermissionRequest
            activePermissionRequest = null
            current?.onResult?.invoke(results)
            dispatchNextPermissionRequest()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            downloadHandler.onStoragePermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)

        val runtime = GeckoRuntimeHolder.getOrCreate(applicationContext)
        downloadHandler = DownloadHandler(
            context = this,
            requestLegacyStoragePermission = {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
        )
        fileChooserHandler = FileChooserHandler(
            activity = this,
            launchIntent = { intent -> fileChooserLauncher.launch(intent) },
            requestPermissions = ::enqueuePermissionRequest,
        )
        promptHandler = GeckoPromptHandler(
            activity = this,
            fileChooserHandler = fileChooserHandler,
        )
        sessionController = GeckoSessionController(
            activity = this,
            runtime = runtime,
            promptHandler = promptHandler,
            downloadHandler = downloadHandler,
            requestRuntimePermissions = ::enqueuePermissionRequest,
            onFullScreenChanged = ::updateFullScreenSystemBars,
        )

        setContent {
            SuperWebViewTheme {
                GeckoBrowserScreen(
                    activity = this,
                    controller = sessionController,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sessionController.setActive(true)
    }

    override fun onResume() {
        super.onResume()
        sessionController.setFocused(true)
        if (sessionController.isFullScreen) {
            updateFullScreenSystemBars(true)
        }
        if (wasPaused) {
            wasPaused = false
            sessionController.scheduleForegroundResume()
        }
    }

    override fun onPause() {
        wasPaused = true
        sessionController.cancelForegroundResume()
        sessionController.setFocused(false)
        super.onPause()
    }

    override fun onStop() {
        sessionController.setActive(false)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyEdgeToEdge()
        if (::sessionController.isInitialized && sessionController.isFullScreen) {
            updateFullScreenSystemBars(true)
        }
        GeckoRuntimeHolder.getOrNull()?.configurationChanged(newConfig)
    }

    override fun onDestroy() {
        rejectAllPendingPermissions()
        promptHandler.dismissAll()
        fileChooserHandler.cleanup()
        downloadHandler.cleanup()
        sessionController.closeAll()
        super.onDestroy()
    }

    private fun applyEdgeToEdge() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    private fun enqueuePermissionRequest(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit,
    ) {
        permissionQueue.addLast(PendingPermissionRequest(permissions, onResult))
        dispatchNextPermissionRequest()
    }

    private fun dispatchNextPermissionRequest() {
        if (activePermissionRequest != null) return
        val next = permissionQueue.removeFirstOrNull() ?: return
        activePermissionRequest = next
        runtimePermissionLauncher.launch(next.permissions)
    }

    private fun rejectAllPendingPermissions() {
        activePermissionRequest?.onResult?.invoke(emptyMap())
        activePermissionRequest = null
        while (permissionQueue.isNotEmpty()) {
            permissionQueue.removeFirst().onResult(emptyMap())
        }
    }

    private fun updateFullScreenSystemBars(fullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private data class PendingPermissionRequest(
        val permissions: Array<String>,
        val onResult: (Map<String, Boolean>) -> Unit,
    )
}
