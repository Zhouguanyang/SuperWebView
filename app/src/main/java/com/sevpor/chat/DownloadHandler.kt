package com.sevpor.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors

private const val DEFAULT_DOWNLOAD_MIME_TYPE = "application/octet-stream"

class DownloadHandler(
    private val context: Context,
    private val requestLegacyStoragePermission: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val activeBlobDownloads = mutableMapOf<String, BlobDownloadSession>()
    private val pendingStorageDownloads = ArrayDeque<PendingDownload>()
    private var awaitingStoragePermission = false

    fun handleExternalResponse(
        response: WebResponse,
        onRequestPageBlobDownload: ((url: String, fileName: String) -> Unit)? = null,
    ) {
        val url = response.uri
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
        val contentDisposition = response.headerIgnoreCase("content-disposition")
        val contentType = response.headerIgnoreCase("content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val fileName = guessDownloadFileName(
            url = if (scheme == "data") "download" else url,
            contentDisposition = contentDisposition,
            mimeType = contentType ?: mimeTypeFromDataUrl(url),
        )
        val body = response.body

        if (body != null) {
            showToast(R.string.download_started)
            ioExecutor.execute {
                val tempFile = runCatching { createTempDownloadFile(appContext.cacheDir) }.getOrNull()
                if (tempFile == null) {
                    runCatching { body.close() }
                    showToast(R.string.download_failed)
                    return@execute
                }
                try {
                    body.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    enqueueSaveDownload(
                        PendingDownload(
                            tempFile = tempFile,
                            fileName = fileName,
                            mimeType = contentType ?: DEFAULT_DOWNLOAD_MIME_TYPE,
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to stream external response download: $url", e)
                    runCatching { tempFile.delete() }
                    showToast(R.string.download_failed)
                }
            }
            return
        }

        when (scheme) {
            "data" -> {
                showToast(R.string.download_started)
                ioExecutor.execute {
                    try {
                        val download = writeDataUrlToTempFile(
                            cacheDirectory = appContext.cacheDir,
                            dataUrl = url,
                            fileName = fileName,
                            fallbackMimeType = contentType,
                        )
                        enqueueSaveDownload(download)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to save data URL download", e)
                        showToast(R.string.download_failed)
                    }
                }
            }
            "blob" -> {
                if (onRequestPageBlobDownload != null) {
                    onRequestPageBlobDownload(url, fileName)
                } else {
                    showToast(R.string.download_not_supported)
                }
            }
            else -> {
                Log.w(TAG, "Unsupported download response with empty body: $url")
                showToast(R.string.download_not_supported)
            }
        }
    }

    @Synchronized
    fun beginBlobDownload(id: String?, mimeType: String?, fileName: String?): Boolean {
        if (id.isNullOrBlank()) return false
        runCatching { activeBlobDownloads.remove(id)?.close(deleteFile = true) }
        return try {
            val tempFile = createTempDownloadFile(appContext.cacheDir)
            activeBlobDownloads[id] = BlobDownloadSession(
                tempFile = tempFile,
                output = FileOutputStream(tempFile),
                fileName = sanitizeDownloadFileName(fileName),
                mimeType = mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_MIME_TYPE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to begin blob download", e)
            false
        }
    }

    @Synchronized
    fun appendBlobDownloadChunk(id: String?, base64Data: String?): Boolean {
        if (id.isNullOrBlank() || base64Data.isNullOrBlank()) return false
        val session = activeBlobDownloads[id] ?: return false
        try {
            session.output.write(Base64.decode(base64Data, Base64.DEFAULT))
            return true
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid base64 download chunk", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to write blob download chunk", e)
        }
        activeBlobDownloads.remove(id)?.close(deleteFile = true)
        return false
    }

    @Synchronized
    fun finishBlobDownload(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val session = activeBlobDownloads.remove(id) ?: return false
        return try {
            session.output.flush()
            session.output.close()
            enqueueSaveDownload(
                PendingDownload(
                    tempFile = session.tempFile,
                    fileName = session.fileName,
                    mimeType = session.mimeType,
                ),
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to finish blob download", e)
            session.close(deleteFile = true)
            showToast(R.string.download_failed)
            false
        }
    }

    @Synchronized
    fun cancelBlobDownload(id: String?) {
        if (id.isNullOrBlank()) return
        activeBlobDownloads.remove(id)?.close(deleteFile = true)
    }

    @Synchronized
    fun cancelAllBlobDownloads() {
        activeBlobDownloads.values.forEach { it.close(deleteFile = true) }
        activeBlobDownloads.clear()
    }

    fun onStoragePermissionResult(granted: Boolean) {
        val downloadsToProcess = synchronized(pendingStorageDownloads) {
            awaitingStoragePermission = false
            val list = pendingStorageDownloads.toList()
            pendingStorageDownloads.clear()
            list
        }
        if (downloadsToProcess.isEmpty()) return

        if (!granted) {
            downloadsToProcess.forEach { runCatching { it.tempFile.delete() } }
            showToast(R.string.download_failed)
            return
        }

        downloadsToProcess.forEach { download ->
            ioExecutor.execute { writePendingDownload(download) }
        }
    }

    fun cleanup() {
        cancelAllBlobDownloads()
        val leftovers = synchronized(pendingStorageDownloads) {
            awaitingStoragePermission = false
            val list = pendingStorageDownloads.toList()
            pendingStorageDownloads.clear()
            list
        }
        leftovers.forEach { runCatching { it.tempFile.delete() } }
        ioExecutor.shutdown()
    }

    private fun enqueueSaveDownload(download: PendingDownload) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val shouldRequest = synchronized(pendingStorageDownloads) {
                pendingStorageDownloads.addLast(download)
                if (!awaitingStoragePermission) {
                    awaitingStoragePermission = true
                    true
                } else {
                    false
                }
            }
            if (shouldRequest) {
                mainHandler.post { requestLegacyStoragePermission() }
            }
            return
        }

        ioExecutor.execute { writePendingDownload(download) }
    }

    private fun writePendingDownload(download: PendingDownload) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeWithMediaStore(download)
            } else {
                writeToPublicDownloads(download)
            }
            showToast(R.string.download_saved)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to persist download ${download.fileName}", e)
            showToast(R.string.download_failed)
        } finally {
            runCatching { download.tempFile.delete() }
        }
    }

    private fun writeWithMediaStore(download: PendingDownload) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, download.fileName)
            put(MediaStore.Downloads.MIME_TYPE, download.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore download item")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                download.tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open MediaStore download stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToPublicDownloads(download: PendingDownload) {
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
            appContext,
            arrayOf(file.absolutePath),
            arrayOf(download.mimeType),
            null,
        )
    }

    private fun showToast(resId: Int) {
        mainHandler.post {
            Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleBridgeMessage(
        type: String,
        id: String?,
        mimeType: String?,
        fileName: String?,
        base64Data: String?,
        onComplete: (Boolean) -> Unit,
    ) {
        ioExecutor.execute {
            val ok = when (type) {
                "beginDownload" -> beginBlobDownload(id, mimeType, fileName)
                "appendDownloadChunk" -> appendBlobDownloadChunk(id, base64Data)
                "finishDownload" -> finishBlobDownload(id)
                "cancelDownload" -> {
                    cancelBlobDownload(id)
                    true
                }
                else -> false
            }
            mainHandler.post { onComplete(ok) }
        }
    }

    private fun writeDataUrlToTempFile(
        cacheDirectory: File,
        dataUrl: String,
        fileName: String,
        fallbackMimeType: String?,
    ): PendingDownload {
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
            runCatching { tempFile.delete() }
            throw e
        }

        return PendingDownload(
            tempFile = tempFile,
            fileName = sanitizeDownloadFileName(fileName),
            mimeType = mimeType,
        )
    }

    private data class PendingDownload(
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
}

private fun WebResponse.headerIgnoreCase(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

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
    val directory = File(cacheDirectory, "gecko-downloads")
    if (!directory.exists() && !directory.mkdirs()) {
        error("Unable to create temporary download directory")
    }
    return File.createTempFile("download_", ".tmp", directory)
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
