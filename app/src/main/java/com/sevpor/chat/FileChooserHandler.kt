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
import android.provider.OpenableColumns
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
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val FILE_CHOOSER_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

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
        executeIoSafely { cleanupStaleCacheFiles() }
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
                        dismissPromptSafely(prompt, deleteCaptureFile = true)
                    }
                }
            }
        })

        if (prompt.type == FilePrompt.Type.FOLDER) {
            launchPickerSafely(prompt) { createFolderChooserIntent() }
            return result
        }

        val capturePermissions = capturePermissionsFor(prompt).filter { !isPermissionGranted(it) }
        if (capturePermissions.isNotEmpty()) {
            requestPermissions(capturePermissions.toTypedArray()) { grantResults ->
                if (pendingPrompt !== prompt) return@requestPermissions
                val allGranted = capturePermissions.all { isPermissionGranted(it, grantResults) }
                if (!allGranted) {
                    dismissPromptSafely(prompt, deleteCaptureFile = true)
                    return@requestPermissions
                }
                launchPickerSafely(prompt) { createFileChooserIntent(prompt) }
            }
            return result
        }

        launchPickerSafely(prompt) { createFileChooserIntent(prompt) }
        return result
    }

    fun onActivityResult(resultCode: Int, data: Intent?) {
        val prompt = pendingPrompt ?: return
        try {
            if (resultCode != Activity.RESULT_OK) {
                dismissPromptSafely(prompt, deleteCaptureFile = true)
                return
            }

            if (prompt.type == FilePrompt.Type.FOLDER) {
                val treeUri = data?.data
                if (treeUri == null) {
                    dismissPromptSafely(prompt, deleteCaptureFile = true)
                    return
                }
                // Folder prompts require the SAF treeUri directly so GeckoView can traverse it.
                val response = if (!prompt.isComplete) prompt.confirm(appContext, treeUri) else null
                completePending(response, deleteCaptureFile = true)
                return
            }

            val selectedUris = parseSelectedFileUris(resultCode, data)
            val captureFile = pendingCaptureFile
            val uris = selectedUris ?: parseCaptureOutputUri(resultCode)
            if (uris.isNullOrEmpty()) {
                dismissPromptSafely(prompt, deleteCaptureFile = true)
                return
            }

            // If this came directly from our local capture output file, pass file:// directly
            // on the UI thread without any copy (also handle camera apps that echo pendingCaptureUri).
            val isDirectCaptureResult =
                captureFile != null &&
                    captureFile.exists() &&
                    captureFile.length() > 0L &&
                    (selectedUris == null || (selectedUris.size == 1 && selectedUris[0] == pendingCaptureUri))
            if (isDirectCaptureResult) {
                val fileUri = Uri.fromFile(captureFile)
                val response = if (!prompt.isComplete) {
                    if (prompt.type == FilePrompt.Type.MULTIPLE) {
                        prompt.confirm(appContext, arrayOf(fileUri))
                    } else {
                        prompt.confirm(appContext, fileUri)
                    }
                } else {
                    null
                }
                completePending(response, deleteCaptureFile = false)
                return
            }

            // Copy content:// URIs to temporary local files on a background IO thread first,
            // then invoke BasePrompt.confirm() strictly on the UI thread using file:// URIs
            // (which GeckoView resolves in O(1) without touching disk on the main thread).
            val queued = executeIoSafely {
                val stagedUris = stageUrisToLocalFiles(uris)
                mainHandler.post {
                    if (pendingPrompt !== prompt) return@post
                    if (stagedUris.isEmpty()) {
                        dismissPromptSafely(prompt, deleteCaptureFile = true)
                        return@post
                    }
                    val response = try {
                        if (prompt.isComplete) {
                            null
                        } else if (prompt.type == FilePrompt.Type.MULTIPLE) {
                            prompt.confirm(appContext, stagedUris)
                        } else {
                            prompt.confirm(appContext, stagedUris.first())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to confirm selected files", e)
                        if (!prompt.isComplete) prompt.dismiss() else null
                    }
                    completePending(response, deleteCaptureFile = true)
                }
            }
            if (!queued && pendingPrompt === prompt) {
                dismissPromptSafely(prompt, deleteCaptureFile = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to parse selected files", e)
            dismissPromptSafely(prompt, deleteCaptureFile = true)
        }
    }

    fun cleanup() {
        isCleanedUp = true
        dismissPendingPrompt()
        ioExecutor.shutdown()
    }

    private fun stageUrisToLocalFiles(uris: Array<Uri>): Array<Uri> {
        val staged = mutableListOf<Uri>()
        uris.forEach { uri ->
            if (uri.scheme.equals("file", ignoreCase = true)) {
                staged += uri
                return@forEach
            }
            val copied = copyContentUriToTempFile(uri)
            if (copied != null) {
                staged += Uri.fromFile(copied)
            } else {
                // Fallback to original content:// URI if staging failed unexpectedly.
                staged += uri
            }
        }
        return staged.toTypedArray()
    }

    private fun copyContentUriToTempFile(uri: Uri): File? {
        var batchDir: File? = null
        return try {
            val resolver = appContext.contentResolver
            val displayName = queryDisplayName(uri) ?: "upload"
            val safeName = sanitizeUploadFileName(displayName)
            val dir = File(appContext.cacheDir, "gecko-uploads/${UUID.randomUUID()}")
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            batchDir = dir
            val target = File(dir, safeName)
            val stream = resolver.openInputStream(uri)
            if (stream == null) {
                runCatching { dir.deleteRecursively() }
                return null
            }
            stream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stage content URI to cache: $uri", e)
            runCatching { batchDir?.deleteRecursively() }
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitizeUploadFileName(raw: String): String {
        val clean = raw
            .substringAfterLast('/')
            .replace(Regex("""[\\:*?"<>|]"""), "_")
            .trim()
            .takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "upload"
        return clean.take(180)
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

    private fun cleanupStaleCacheFiles() {
        val cutoff = System.currentTimeMillis() - FILE_CHOOSER_CACHE_MAX_AGE_MS
        val roots = buildList {
            appContext.externalCacheDir?.let { add(File(it, "gecko-captures")) }
            add(File(appContext.cacheDir, "gecko-captures"))
            add(File(appContext.cacheDir, "gecko-uploads"))
        }.distinct()

        roots.filter { it.isDirectory }.forEach { root ->
            root.walkBottomUp().forEach { entry ->
                if (entry == root) return@forEach
                if (entry.isFile && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                } else if (entry.isDirectory && entry.listFiles().isNullOrEmpty() && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                }
            }
        }
    }

    private fun launchPickerSafely(prompt: FilePrompt, createIntent: () -> Intent) {
        try {
            launchIntent(createIntent())
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No file chooser available", e)
            dismissPromptSafely(prompt, deleteCaptureFile = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch file chooser", e)
            dismissPromptSafely(prompt, deleteCaptureFile = true)
        }
    }

    private fun dismissPendingPrompt() {
        val prompt = pendingPrompt ?: return
        dismissPromptSafely(prompt, deleteCaptureFile = true)
    }

    private fun dismissPromptSafely(prompt: FilePrompt, deleteCaptureFile: Boolean) {
        val response = if (!prompt.isComplete) prompt.dismiss() else null
        completePending(response, deleteCaptureFile = deleteCaptureFile)
    }

    private fun completePending(response: PromptResponse?, deleteCaptureFile: Boolean) {
        val result = pendingResult
        pendingPrompt = null
        pendingResult = null
        clearPendingCapture(deleteFile = deleteCaptureFile)
        if (response != null) {
            result?.complete(response)
        }
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
        val file = pendingCaptureFile ?: return null
        if (!file.exists() || file.length() <= 0L) return null
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
