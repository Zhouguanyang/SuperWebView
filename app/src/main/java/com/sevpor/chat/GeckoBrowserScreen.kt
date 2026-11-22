package com.sevpor.chat

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val activeSession = popupSession ?: controller.mainSession
    val mainLoadError = controller.mainLoadError
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
            var geckoView by remember { mutableStateOf<GeckoView?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    geckoView?.let { controller.detachView(it) }
                    geckoView = null
                }
            }

            // Single GeckoView host switching between mainSession and popupSession avoids
            // overlapping SurfaceView Z-order issues on Android.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    GeckoView(context).apply {
                        setBackgroundColor(backgroundColorArgb)
                        coverUntilFirstPaint(backgroundColorArgb)
                        autofillEnabled = true
                        setActivityContextDelegate { activity }
                        geckoView = this
                        controller.attachView(this, activeSession)
                    }
                },
                update = { view ->
                    view.setBackgroundColor(backgroundColorArgb)
                    controller.attachView(view, activeSession)
                },
            )

            if (popupSession == null && mainLoadError != null) {
                LoadErrorOverlay(
                    onRetry = controller::retryMainLoad,
                )
            }
        }
    }
}

@Composable
private fun LoadErrorOverlay(
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_error),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.error_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.error_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(id = R.string.button_retry))
            }
        }
    }
}
