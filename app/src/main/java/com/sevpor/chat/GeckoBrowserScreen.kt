package com.sevpor.chat

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

private const val EXIT_BACK_PRESS_INTERVAL_MS = 2_000L

@Composable
fun GeckoBrowserScreen(
    activity: Activity,
    controller: GeckoSessionController,
) {
    val backgroundColorArgb = MaterialTheme.colorScheme.background.toArgb()
    val isFullScreen = controller.isFullScreen
    val popupSession = controller.popupSession
    var lastExitBackPressedAt by remember { mutableLongStateOf(0L) }

    // BackHandlers in Compose are evaluated in reverse registration order (last registered wins).
    // Priority: Fullscreen > Popup > Main session / double-back exit.
    BackHandler(enabled = true) {
        controller.navigateBackMain {
            val now = System.currentTimeMillis()
            if (now - lastExitBackPressedAt <= EXIT_BACK_PRESS_INTERVAL_MS) {
                activity.moveTaskToBack(true)
            } else {
                lastExitBackPressedAt = now
                Toast.makeText(activity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = popupSession != null) {
        controller.navigateBackPopup()
    }

    BackHandler(enabled = isFullScreen) {
        controller.exitFullScreen()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isFullScreen) Modifier else Modifier.safeDrawingPadding()),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            var mainGeckoView by remember { mutableStateOf<GeckoView?>(null) }

            DisposableEffect(controller.mainSession) {
                onDispose {
                    mainGeckoView?.let { controller.detachMainView(it) }
                    mainGeckoView = null
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    GeckoView(context).apply {
                        setBackgroundColor(backgroundColorArgb)
                        coverUntilFirstPaint(backgroundColorArgb)
                        autofillEnabled = true
                        setActivityContextDelegate { activity }
                        mainGeckoView = this
                        controller.attachMainView(this)
                    }
                },
                update = { view ->
                    view.setBackgroundColor(backgroundColorArgb)
                    controller.attachMainView(view)
                },
            )

            if (popupSession != null) {
                key(popupSession) {
                    var popupGeckoView by remember { mutableStateOf<GeckoView?>(null) }

                    DisposableEffect(popupSession) {
                        onDispose {
                            popupGeckoView?.let { controller.detachPopupView(it, popupSession) }
                            popupGeckoView = null
                        }
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            GeckoView(context).apply {
                                setBackgroundColor(backgroundColorArgb)
                                coverUntilFirstPaint(backgroundColorArgb)
                                autofillEnabled = true
                                setActivityContextDelegate { activity }
                                popupGeckoView = this
                                controller.attachPopupView(this, popupSession)
                            }
                        },
                        update = { view ->
                            view.setBackgroundColor(backgroundColorArgb)
                            controller.attachPopupView(view, popupSession)
                        },
                    )
                }
            }
        }
    }
}
