package com.stabilizepro.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.stabilizepro.app.data.engine.VideoStabilizerEngine
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationProgress
import com.stabilizepro.app.domain.model.StabilizationResult
import com.stabilizepro.app.domain.model.VideoInfo
import com.stabilizepro.app.domain.repository.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class VideoRepositoryImpl(
    private val context: Context,
    private val engine: VideoStabilizerEngine = VideoStabilizerEngine(context)
) : VideoRepository {

    override suspend fun extractVideoInfo(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var name = "video.mp4"
        var sizeBytes = 0L

        // Try getting name and size from content resolver
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex) ?: name
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } else if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                name = file.name
                sizeBytes = file.length()
            }
        } catch (ignored: Exception) {}

        retriever.setDataSource(context, uri)

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L

        val origWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val origHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

        val (width, height) = if (rotation == 90 || rotation == 270) {
            Pair(origHeight, origWidth)
        } else {
            Pair(origWidth, origHeight)
        }

        var fps = 30f
        var codecName = "H.264 (AVC)"
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    }
                    codecName = when {
                        mime.contains("avc", ignoreCase = true) -> "H.264 (AVC)"
                        mime.contains("hevc", ignoreCase = true) -> "H.265 (HEVC)"
                        mime.contains("vp9", ignoreCase = true) -> "VP9"
                        mime.contains("av01", ignoreCase = true) -> "AV1"
                        mime.contains("mp4v", ignoreCase = true) -> "MPEG-4"
                        else -> mime.substringAfter("video/")
                    }
                    break
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try { extractor.release() } catch (ignored: Exception) {}
            try { retriever.release() } catch (ignored: Exception) {}
        }

        if (fps <= 0f) fps = 30f

        VideoInfo(
            uri = uri,
            name = name,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            sizeBytes = sizeBytes,
            bitrate = bitrate,
            rotation = rotation,
            codec = codecName
        )
    }

    override suspend fun stabilizeVideo(
        inputUri: Uri,
        config: StabilizationConfig,
        onProgress: (StabilizationProgress) -> Unit
    ): StabilizationResult {
        return engine.stabilize(inputUri, config, onProgress)
    }

    override suspend fun saveVideoToGallery(outputFile: File): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val filename = "STABILIZE_PRO_${System.currentTimeMillis()}.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StabilizePro")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(Exception("Falha ao criar entrada no MediaStore"))

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(outputFile).use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            Result.success(itemUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun createShareIntent(uri: Uri): Intent {
        val shareUri = if (uri.scheme == "file" || (uri.path != null && uri.path!!.startsWith("/"))) {
            val file = File(uri.path ?: "")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            uri
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
