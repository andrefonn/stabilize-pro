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
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import android.util.Log
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

    override suspend fun saveVideoToGallery(outputFile: File): Result<Uri> {
        // Validação pré-MediaStore estrita obrigatória conforme Regras 4 e 5
        if (!outputFile.exists()) {
            val err = IllegalStateException("Arquivo de vídeo não existe: ${outputFile.absolutePath}")
            DebugCenter.log(LogModule.General, LogLevel.ERROR, err.message ?: "", errorCode = "#MP4_0B")
            return Result.failure(err)
        }
        if (outputFile.length() <= 1024L) {
            val length = outputFile.length()
            try { outputFile.delete() } catch (ignored: Exception) {}
            val err = IllegalStateException("Arquivo de vídeo corrompido ou com 0 bytes ($length bytes <= 1024L). Publicação no MediaStore cancelada.")
            DebugCenter.log(LogModule.General, LogLevel.ERROR, err.message ?: "", errorCode = "#MP4_0B")
            return Result.failure(err)
        }
        return saveVideoToGallery(Uri.fromFile(outputFile), outputFile.name)
    }

    override suspend fun saveVideoToGallery(uri: Uri, fileName: String?): Result<Uri> = withContext(Dispatchers.IO) {
        var itemUri: Uri? = null
        try {
            val localPath = uri.path
            val isLocalFile = uri.scheme == "file" || (localPath != null && File(localPath).exists())
            val sourceFile = if (isLocalFile) File(localPath ?: "") else null

            // Validação estrita de arquivo local antes de criar qualquer linha no MediaStore
            if (sourceFile != null) {
                if (!sourceFile.exists()) {
                    throw IllegalStateException("Arquivo fonte local não existe: ${sourceFile.absolutePath}")
                }
                if (sourceFile.length() <= 1024L) {
                    val len = sourceFile.length()
                    try { sourceFile.delete() } catch (ignored: Exception) {}
                    throw IllegalStateException("Arquivo fonte local tem $len bytes (<= 1024L). Publicação no MediaStore abortada.")
                }
            }

            val baseName = fileName ?: "STABILIZE_PRO_${System.currentTimeMillis()}.mp4"
            val sanitizedName = if (baseName.endsWith(".mp4", ignoreCase = true)) baseName else "$baseName.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, sanitizedName)
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

            val insertedUri = resolver.insert(collectionUri, contentValues)
                ?: throw IllegalStateException("Falha ao criar entrada no MediaStore")
            itemUri = insertedUri

            var bytesWritten = 0L
            val buffer = ByteArray(64 * 1024)

            resolver.openOutputStream(insertedUri)?.use { out ->
                val inputStream = if (sourceFile != null) {
                    FileInputStream(sourceFile)
                } else {
                    resolver.openInputStream(uri)
                        ?: throw IllegalStateException("Não foi possível abrir o fluxo de leitura do vídeo de origem.")
                }

                inputStream.use { input ->
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } >= 0) {
                        if (bytes > 0) {
                            out.write(buffer, 0, bytes)
                            bytesWritten += bytes
                        }
                    }
                }
                out.flush()
            } ?: throw IllegalStateException("Não foi possível abrir o fluxo de saída do MediaStore.")

            // Validação pós-escrita: nunca publicar 0 bytes
            if (bytesWritten <= 1024L) {
                throw IllegalStateException("Gravação incompleta no MediaStore: apenas $bytesWritten bytes gravados (mínimo 1024B).")
            }

            // Publicar na galeria tornando visível
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(insertedUri, contentValues, null, null)
            }

            DebugCenter.log(LogModule.General, LogLevel.INFO, "Vídeo publicado no MediaStore com sucesso: $insertedUri ($bytesWritten bytes)")
            Result.success(insertedUri)
        } catch (e: Exception) {
            // Em caso de qualquer erro, purgar entrada órfã de 0 bytes criada no MediaStore
            itemUri?.let { cleanupUri ->
                try {
                    context.contentResolver.delete(cleanupUri, null, null)
                    Log.w("VideoRepositoryImpl", "Entrada órfã $cleanupUri removida do MediaStore após falha.")
                } catch (ignored: Exception) {}
            }
            DebugCenter.log(
                LogModule.General,
                LogLevel.ERROR,
                "Falha ao salvar vídeo no MediaStore: ${e.message}",
                errorCode = "#MEDIASTORE_ERR"
            )
            Result.failure(e)
        }
    }

    override suspend fun savePhotoToGallery(photoFile: File, fileName: String?): Result<Uri> = withContext(Dispatchers.IO) {
        var itemUri: Uri? = null
        try {
            if (!photoFile.exists()) {
                throw IllegalStateException("Arquivo de foto não existe: ${photoFile.absolutePath}")
            }
            if (photoFile.length() <= 1024L) {
                val len = photoFile.length()
                throw IllegalStateException("Arquivo de foto vazio ou corrompido ($len bytes <= 1024B).")
            }

            val baseName = fileName ?: "PHOTO_${System.currentTimeMillis()}.jpg"
            val sanitizedName = if (baseName.endsWith(".jpg", ignoreCase = true) || baseName.endsWith(".jpeg", ignoreCase = true)) baseName else "$baseName.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, sanitizedName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.ORIENTATION, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StabilizePro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val insertedUri = resolver.insert(collectionUri, contentValues)
                ?: throw IllegalStateException("Falha ao criar entrada no MediaStore para foto.")
            itemUri = insertedUri

            var bytesWritten = 0L
            val buffer = ByteArray(64 * 1024)

            resolver.openOutputStream(insertedUri)?.use { out ->
                FileInputStream(photoFile).use { input ->
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } >= 0) {
                        if (bytes > 0) {
                            out.write(buffer, 0, bytes)
                            bytesWritten += bytes
                        }
                    }
                }
                out.flush()
            } ?: throw IllegalStateException("Não foi possível abrir o fluxo de saída do MediaStore.")

            if (bytesWritten <= 1024L) {
                throw IllegalStateException("Gravação incompleta no MediaStore: apenas $bytesWritten bytes gravados.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(insertedUri, contentValues, null, null)
            }

            DebugCenter.log(LogModule.General, LogLevel.INFO, "Foto publicada no MediaStore com sucesso: $insertedUri ($bytesWritten bytes)")
            Result.success(insertedUri)
        } catch (e: Exception) {
            itemUri?.let { cleanupUri ->
                try {
                    context.contentResolver.delete(cleanupUri, null, null)
                    Log.w("VideoRepositoryImpl", "Entrada órfã $cleanupUri removida do MediaStore após falha.")
                } catch (ignored: Exception) {}
            }
            DebugCenter.log(
                LogModule.General,
                LogLevel.ERROR,
                "Falha ao salvar foto no MediaStore: ${e.message}",
                errorCode = "#MEDIASTORE_IMG_ERR"
            )
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
