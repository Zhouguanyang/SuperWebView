package com.sevpor.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val CAPTURE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

class FileChooserHandler(
    private val activity: Activity,
    private val launchIntent: (Intent) -> Unit,
    private val requestPermissions: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit,
) {
    private val appContext = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var pendingPrompt: FilePrompt? = null
    private var pendingResult: GeckoResult<PromptResponse>? = null
    private var pendingCaptureUri: Uri? = null
    private var pendingCaptureFile: File? = null

    @Volatile
    private var isCleanedUp = false

    init {
        executeIoSafely { cleanupStaleCaptureFiles() }
    }

    fun handleFilePrompt(prompt: FilePrompt): GeckoResult<PromptResponse> {
        dismissPendingPrompt()

        val result = GeckoResult<PromptResponse>()
        pendingPrompt = prompt
        pendingResult = result

        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(basePrompt: BasePrompt) {
                mainHandler.post {
                    if (pendingPrompt === prompt) {
                        completePending(prompt.dismiss(), deleteCaptureFile = true)
                    }
                }
            }
        })

        if (prompt.type == FilePrompt.Type.FOLDER) {
            launchPickerSafely(prompt, createFolderChooserIntent())
            return result
        }

        val capturePermissions = capturePermissionsFor(prompt).filter { !isPermissionGranted(it) }
        if (capturePermissions.isNotEmpty()) {
            requestPermissions(capturePermissions.toTypedArray()) { grantResults ->
                if (pendingPrompt !== prompt) return@requestPermissions
                val allGranted = capturePermissions.all { isPermissionGranted(it, grantResults) }
                if (!allGranted) {
                    completePending(prompt.dismiss(), deleteCaptureFile = true)
                    return@requestPermissions
                }
                launchPickerSafely(prompt, createFileChooserIntent(prompt))
            }
            return result
        }

        launchPickerSafely(prompt, createFileChooserIntent(prompt))
        return result
    }

    fun onActivityResult(resultCode: Int, data: Intent?) {
        val prompt = pendingPrompt ?: return
        try {
            if (resultCode != Activity.RESULT_OK) {
                completePending(prompt.dismiss(), deleteCaptureFile = true)
                return
            }

            if (prompt.type == FilePrompt.Type.FOLDER) {
                val treeUri = data?.data
                if (treeUri == null) {
                    completePending(prompt.dismiss(), deleteCaptureFile = true)
                    return
                }
                confirmPromptAsync(prompt, deleteCaptureFile = true) {
                    prompt.confirm(appContext, treeUri)
                }
                return
            }

            val uris = parseSelectedFileUris(resultCode, data) ?: parseCaptureOutputUri(resultCode)
            if (uris.isNullOrEmpty()) {
                completePending(prompt.dismiss(), deleteCaptureFile = true)
                return
            }

            confirmPromptAsync(prompt, deleteCaptureFile = false) {
                if (prompt.type == FilePrompt.Type.MULTIPLE) {
                    prompt.confirm(appContext, uris)
                } else {
                    prompt.confirm(appContext, uris.first())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to parse selected files", e)
            completePending(prompt.dismiss(), deleteCaptureFile = true)
        }
    }

    fun cleanup() {
        isCleanedUp = true
        dismissPendingPrompt()
        ioExecutor.shutdown()
    }

    private fun confirmPromptAsync(
        prompt: FilePrompt,
        deleteCaptureFile: Boolean,
        buildResponse: () -> PromptResponse,
    ) {
        val queued = executeIoSafely {
            val response = try {
                buildResponse()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to confirm selected files", e)
                prompt.dismiss()
            }
            mainHandler.post {
                if (pendingPrompt === prompt) {
                    completePending(response, deleteCaptureFile = deleteCaptureFile)
                }
            }
        }
        if (!queued && pendingPrompt === prompt) {
            completePending(prompt.dismiss(), deleteCaptureFile = true)
        }
    }

    private fun executeIoSafely(block: () -> Unit): Boolean {
        if (isCleanedUp || ioExecutor.isShutdown) return false
        return try {
            ioExecutor.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "File chooser executor rejected task after shutdown", e)
            false
        }
    }

    private fun cleanupStaleCaptureFiles() {
        val cutoff = System.currentTimeMillis() - CAPTURE_CACHE_MAX_AGE_MS
        listOfNotNull(appContext.externalCacheDir, appContext.cacheDir)
            .distinct()
            .map { File(it, "gecko-captures") }
            .filter { it.isDirectory }
            .forEach { root ->
                root.walkBottomUp().forEach { entry ->
                    if (entry.isFile && entry.lastModified() < cutoff) {
                        runCatching { entry.delete() }
                    }
                }
            }
    }

    private fun launchPickerSafely(prompt: FilePrompt, intent: Intent) {
        try {
            launchIntent(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No file chooser available", e)
            completePending(prompt.dismiss(), deleteCaptureFile = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch file chooser", e)
            completePending(prompt.dismiss(), deleteCaptureFile = true)
        }
    }

    private fun dismissPendingPrompt() {
        val prompt = pendingPrompt ?: return
        completePending(prompt.dismiss(), deleteCaptureFile = true)
    }

    private fun completePending(response: PromptResponse, deleteCaptureFile: Boolean) {
        val result = pendingResult
        pendingPrompt = null
        pendingResult = null
        clearPendingCapture(deleteFile = deleteCaptureFile)
        result?.complete(response)
    }

    private fun createFolderChooserIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun createFileChooserIntent(prompt: FilePrompt): Intent {
        if (
            prompt.capture != FilePrompt.Capture.NONE &&
            prompt.type != FilePrompt.Type.MULTIPLE
        ) {
            createCaptureIntent(prompt)?.let { return it }
        }

        val normalizedMimeTypes = normalizeMimeTypes(prompt.mimeTypes)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (prompt.type == FilePrompt.Type.MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            when {
                normalizedMimeTypes.isEmpty() || normalizedMimeTypes.contains("*/*") -> {
                    type = "*/*"
                }
                normalizedMimeTypes.size == 1 -> {
                    type = normalizedMimeTypes.first()
                }
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, normalizedMimeTypes.toTypedArray())
                }
            }
        }
        return Intent.createChooser(intent, activity.getString(R.string.file_chooser_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun capturePermissionsFor(prompt: FilePrompt): Array<String> {
        if (
            prompt.capture == FilePrompt.Capture.NONE ||
            prompt.type == FilePrompt.Type.MULTIPLE
        ) {
            return emptyArray()
        }

        return when (captureMediaTypeFor(prompt.mimeTypes)) {
            CaptureMediaType.IMAGE,
            CaptureMediaType.VIDEO -> arrayOf(Manifest.permission.CAMERA)
            CaptureMediaType.AUDIO -> arrayOf(Manifest.permission.RECORD_AUDIO)
            null -> emptyArray()
        }
    }

    private fun createCaptureIntent(prompt: FilePrompt): Intent? =
        when (captureMediaTypeFor(prompt.mimeTypes)) {
            CaptureMediaType.IMAGE -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putCaptureOutput(createCaptureOutputUri("images", ".jpg"))
                applyCaptureFacingHint(prompt.capture)
            }
            CaptureMediaType.VIDEO -> Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putCaptureOutput(createCaptureOutputUri("videos", ".mp4"))
                applyCaptureFacingHint(prompt.capture)
            }
            CaptureMediaType.AUDIO -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            null -> null
        }

    private fun Intent.applyCaptureFacingHint(capture: Int) {
        if (capture == FilePrompt.Capture.USER) {
            putExtra("android.intent.extras.CAMERA_FACING", 1)
            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
    }

    private fun Intent.putCaptureOutput(outputUri: Uri) {
        putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
        clipData = ClipData.newUri(activity.contentResolver, "Capture output", outputUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun createCaptureOutputUri(directoryName: String, extension: String): Uri {
        val directory = File(activity.externalCacheDir ?: activity.cacheDir, "gecko-captures/$directoryName")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Unable to create capture directory")
        }
        val file = File.createTempFile("capture_", extension, directory)
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file,
        )
        pendingCaptureUri = uri
        return uri
    }

    private fun parseCaptureOutputUri(resultCode: Int): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        return pendingCaptureUri?.let { arrayOf(it) }
    }

    private fun clearPendingCapture(deleteFile: Boolean) {
        if (deleteFile) {
            runCatching { pendingCaptureFile?.delete() }
        }
        pendingCaptureUri = null
        pendingCaptureFile = null
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

    private fun isPermissionGranted(
        permission: String,
        result: Map<String, Boolean> = emptyMap(),
    ): Boolean =
        result[permission] == true || ContextCompat.checkSelfPermission(
            activity,
            permission,
        ) == PackageManager.PERMISSION_GRANTED

    private enum class CaptureMediaType {
        IMAGE,
        VIDEO,
        AUDIO,
    }

    private fun captureMediaTypeFor(mimeTypes: Array<String>?): CaptureMediaType? {
        val types = normalizeMimeTypes(mimeTypes)
        if (types.isEmpty() || types.any { it == "*/*" }) return CaptureMediaType.IMAGE
        return when {
            types.any { it == "image/*" || it.startsWith("image/") } -> CaptureMediaType.IMAGE
            types.any { it == "video/*" || it.startsWith("video/") } -> CaptureMediaType.VIDEO
            types.any { it == "audio/*" || it.startsWith("audio/") } -> CaptureMediaType.AUDIO
            else -> null
        }
    }

    private fun normalizeMimeTypes(rawTypes: Array<String>?): List<String> {
        if (rawTypes.isNullOrEmpty()) return emptyList()
        val mimeTypeMap = MimeTypeMap.getSingleton()
        val result = linkedSetOf<String>()
        rawTypes
            .flatMap { it.split(',') }
            .map { it.substringBefore(';').trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .forEach { token ->
                when {
                    token.contains('/') -> result += token
                    token.startsWith('.') -> {
                        val ext = token.removePrefix(".")
                        val mapped = mimeTypeMap.getMimeTypeFromExtension(ext)
                        if (!mapped.isNullOrBlank()) {
                            result += mapped
                        } else {
                            result += "*/*"
                        }
                    }
                    else -> result += "*/*"
                }
            }
        return result.toList()
    }
}
