package com.stabilizepro.app.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationProgress
import com.stabilizepro.app.domain.model.StabilizationResult
import com.stabilizepro.app.domain.model.StabilizationStage
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import com.stabilizepro.app.presets.ColorGradingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

class VideoStabilizerEngine(private val context: Context) {

    companion object {
        private const val TAG = "VideoStabilizerEngine"
        private const val MIME_TYPE_AVC = "video/avc"
        private const val TIMEOUT_USEC = 12000L
        private const val TRACKING_WIDTH = 480
        private const val MAX_RENDER_WIDTH = 1920
        private const val MAX_RENDER_HEIGHT = 1080
    }

    private class PendingFrame(
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    suspend fun stabilize(
        inputUri: Uri,
        config: StabilizationConfig,
        onProgress: (StabilizationProgress) -> Unit
    ): StabilizationResult = withContext(Dispatchers.IO) {
        val startTimeMs = System.currentTimeMillis()

        // -------------------------------------------------------------
        // ETAPA 1: Lendo vídeo
        // -------------------------------------------------------------
        onProgress(
            StabilizationProgress(
                stage = StabilizationStage.READING_VIDEO,
                progressPercent = 4,
                estimatedSecondsRemaining = 0L
            )
        )

        val retriever = MediaMetadataRetriever()
        try {
            if (inputUri.scheme == "content") {
                retriever.setDataSource(context, inputUri)
            } else {
                val path = inputUri.path ?: ""
                if (File(path).exists()) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, inputUri)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Não foi possível ler o arquivo de vídeo: ${e.localizedMessage}")
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 5000L
        if (durationMs <= 0) {
            throw IllegalStateException("Duração do vídeo inválida ou arquivo corrompido.")
        }

        val origWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val origHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

        val (rawVideoWidth, rawVideoHeight) = if (rotation == 90 || rotation == 270) {
            Pair(origHeight, origWidth)
        } else {
            Pair(origWidth, origHeight)
        }

        // Cap dimensions while preserving portrait/landscape aspect ratio correctly
        var targetWidth = rawVideoWidth
        var targetHeight = rawVideoHeight
        val isPortrait = targetHeight > targetWidth
        val maxLongSide = 3840
        val maxShortSide = 2160
        val maxWidth = if (isPortrait) maxShortSide else maxLongSide
        val maxHeight = if (isPortrait) maxLongSide else maxShortSide

        if (targetWidth > maxWidth || targetHeight > maxHeight) {
            val scale = min(
                maxWidth.toDouble() / targetWidth,
                maxHeight.toDouble() / targetHeight
            )
            targetWidth = (targetWidth * scale).toInt()
            targetHeight = (targetHeight * scale).toInt()
        }
        // Width and height must be even multiples of 2 (preserving standard 1080x1920 or 720x1280)
        targetWidth = (targetWidth / 2) * 2
        targetHeight = (targetHeight / 2) * 2

        var fps = 30f
        var hasAudioTrack = false
        val extractor = MediaExtractor()
        try {
            if (inputUri.scheme == "content") {
                extractor.setDataSource(context, inputUri, null)
            } else {
                val path = inputUri.path ?: ""
                if (File(path).exists()) {
                    extractor.setDataSource(path)
                } else {
                    extractor.setDataSource(context, inputUri, null)
                }
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val trackFps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        if (trackFps in 10f..120f) {
                            fps = trackFps
                        }
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudioTrack = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor read warning: ${e.message}")
        } finally {
            try { extractor.release() } catch (ignored: Exception) {}
        }

        val estimatedTotalFrames = ((durationMs / 1000.0) * fps).roundToInt().coerceAtLeast(1)

        val tempVideoFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
        val finalOutputFile = File(context.cacheDir, "stabilized_${System.currentTimeMillis()}.mp4")

        // -------------------------------------------------------------
        // ETAPA 2: Detectando movimento (Optical Flow Lucas-Kanade)
        // -------------------------------------------------------------
        onProgress(
            StabilizationProgress(
                stage = StabilizationStage.DETECTING_MOTION,
                progressPercent = 8,
                currentFrame = 0,
                totalFrames = estimatedTotalFrames,
                estimatedSecondsRemaining = (estimatedTotalFrames / fps).toLong()
            )
        )

        val motionEstimator = MotionEstimator()
        val transforms = ArrayList<FrameTransform>()

        // 480p tracking resolution for fast and accurate optical flow
        val trackWidth = TRACKING_WIDTH
        val trackAspect = targetHeight.toDouble() / targetWidth.toDouble()
        val trackHeight = ((trackWidth * trackAspect) / 2).toInt() * 2

        val scaleX = targetWidth.toDouble() / trackWidth.toDouble()
        val scaleY = targetHeight.toDouble() / trackHeight.toDouble()

        var prevGray = Mat()
        val frameStepUs = (1_000_000L / fps).toLong()
        var currentTimeUs = 0L
        var frameIndex = 0

        val motionStartTime = System.currentTimeMillis()

        while (currentTimeUs < durationMs * 1000L) {
            currentCoroutineContext().ensureActive()
            val frameBitmap = getFrameSafely(
                retriever = retriever,
                timeUs = currentTimeUs,
                width = trackWidth,
                height = trackHeight
            )

            if (frameBitmap != null) {
                val origMat = Mat()
                Utils.bitmapToMat(frameBitmap, origMat)

                val resizedMat = Mat()
                if (origMat.cols() != trackWidth || origMat.rows() != trackHeight) {
                    Imgproc.resize(origMat, resizedMat, Size(trackWidth.toDouble(), trackHeight.toDouble()))
                } else {
                    origMat.copyTo(resizedMat)
                }

                val currGray = Mat()
                Imgproc.cvtColor(resizedMat, currGray, Imgproc.COLOR_RGBA2GRAY)

                if (!prevGray.empty()) {
                    val rawTransform = motionEstimator.estimateMotion(prevGray, currGray)
                    val scaledTransform = FrameTransform(
                        dx = rawTransform.dx * scaleX,
                        dy = rawTransform.dy * scaleY,
                        da = rawTransform.da
                    )
                    transforms.add(scaledTransform)
                }

                prevGray.release()
                prevGray = currGray

                origMat.release()
                resizedMat.release()
                frameBitmap.recycle()
            } else {
                transforms.add(FrameTransform(0.0, 0.0, 0.0))
            }

            frameIndex++
            currentTimeUs += frameStepUs

            if (frameIndex % (fps.roundToInt().coerceAtLeast(1) / 2) == 0 || frameIndex >= estimatedTotalFrames) {
                val percent = 8 + ((frameIndex.toFloat() / estimatedTotalFrames.toFloat()) * 32).toInt().coerceIn(0, 32)
                val elapsedSec = (System.currentTimeMillis() - motionStartTime) / 1000.0
                val fpsProcessed = if (elapsedSec > 0) frameIndex / elapsedSec else 1.0
                val remainingSec = (((estimatedTotalFrames - frameIndex) / fpsProcessed) + (estimatedTotalFrames / fpsProcessed)).toLong().coerceAtLeast(1L)

                onProgress(
                    StabilizationProgress(
                        stage = StabilizationStage.DETECTING_MOTION,
                        progressPercent = percent,
                        currentFrame = frameIndex,
                        totalFrames = estimatedTotalFrames,
                        estimatedSecondsRemaining = remainingSec
                    )
                )
            }
        }

        prevGray.release()
        val totalActualFrames = frameIndex.coerceAtLeast(1)

        // -------------------------------------------------------------
        // ETAPA 3: Calculando trajetória
        // -------------------------------------------------------------
        val trajectorySmoother = TrajectorySmoother(
            radius = config.intensity.smoothingRadius,
            cropPercent = config.intensity.cropPercent
        )
        val trajectory = ArrayList<TrajectoryPoint>()
        var accX = 0.0
        var accY = 0.0
        var accA = 0.0
        trajectory.add(TrajectoryPoint(accX, accY, accA))

        val stepChunk = (transforms.size / 5).coerceAtLeast(1)
        for (idx in transforms.indices) {
            val t = transforms[idx]
            accX += t.dx
            accY += t.dy
            accA += t.da
            trajectory.add(TrajectoryPoint(accX, accY, accA))

            if (idx % stepChunk == 0 || idx == transforms.lastIndex) {
                val p = 40 + ((idx.toFloat() / transforms.size.toFloat()) * 8).toInt()
                onProgress(
                    StabilizationProgress(
                        stage = StabilizationStage.CALCULATING_TRAJECTORY,
                        progressPercent = p,
                        currentFrame = idx + 1,
                        totalFrames = totalActualFrames,
                        estimatedSecondsRemaining = 4L
                    )
                )
                delay(80)
            }
        }

        // -------------------------------------------------------------
        // ETAPA 4: Suavizando câmera (Filtro Gaussiano temporal)
        // -------------------------------------------------------------
        val smoothedTrajectory = trajectorySmoother.smoothTrajectory(trajectory)
        for (step in 1..4) {
            val p = 48 + (step * 2)
            onProgress(
                StabilizationProgress(
                    stage = StabilizationStage.SMOOTHING_CAMERA,
                    progressPercent = p,
                    currentFrame = totalActualFrames,
                    totalFrames = totalActualFrames,
                    estimatedSecondsRemaining = (5 - step).toLong()
                )
            )
            delay(120)
        }


        // -------------------------------------------------------------
        // ETAPA 4b: Calculando Safe ROI — Dynamic Auto Crop
        // Determina o zoom ótimo e o retângulo seguro comum a todos os frames,
        // garantindo que nenhum frame exibirá transparência ou borda preta.
        // maxCropRatio é o crop máximo permitido por intensidade:
        //   LOW    → 3%  (preserva enquadramento)
        //   MEDIUM → 7%  (equilíbrio profissional)
        //   HIGH   → 12% (estabilidade máxima)
        // -------------------------------------------------------------
        val maxCropRatio = config.intensity.cropPercent.toDouble() / 100.0
        val effectiveCropRatio = trajectorySmoother.calculateDynamicCropRatio(
            trajectory = trajectory,
            smoothedTrajectory = smoothedTrajectory,
            frameWidth = targetWidth,
            frameHeight = targetHeight,
            maxCropRatio = maxCropRatio
        )
        val uniformSafeRoi = trajectorySmoother.calculateSafeRoi(
            frameWidth = targetWidth,
            frameHeight = targetHeight,
            effectiveCropRatio = effectiveCropRatio
        )
        val zoomFactor = 1.0 / (1.0 - effectiveCropRatio)
        Log.i(TAG, "Dynamic Safe ROI: x=${uniformSafeRoi.x} y=${uniformSafeRoi.y} w=${uniformSafeRoi.width} h=${uniformSafeRoi.height} " +
            "(crop: ${String.format("%.1f", effectiveCropRatio * 100)}%, zoom: ${String.format("%.2f", zoomFactor)}x)")

        // -------------------------------------------------------------
        // ETAPA 5: Recriando vídeo (Warp Affine + MediaCodec H.264)
        // -------------------------------------------------------------
        onProgress(
            StabilizationProgress(
                stage = StabilizationStage.RECREATING_VIDEO,
                progressPercent = 54,
                currentFrame = 0,
                totalFrames = totalActualFrames,
                estimatedSecondsRemaining = (totalActualFrames / fps).toLong().coerceAtLeast(4L)
            )
        )

        val encodeBitrate = (targetWidth * targetHeight * 4.5 * (fps / 30f)).roundToInt().coerceIn(2_000_000, 12_000_000)

        val outputFormat = MediaFormat.createVideoFormat(MIME_TYPE_AVC, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, encodeBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE_AVC)
        val chosenColorFormat = selectColorFormat(encoder.codecInfo, MIME_TYPE_AVC)
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenColorFormat)

        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var formatChanged = false
        var muxerStarted = false
        var samplesWritten = 0L
        var totalBytesWritten = 0L
        var eosReceived = false
        val pendingFrames = ArrayList<PendingFrame>()

        val bufferInfo = MediaCodec.BufferInfo()
        val renderStartTime = System.currentTimeMillis()
        currentTimeUs = 0L
        var primaryError: Throwable? = null

        try {
            for (i in 0 until totalActualFrames) {
                currentCoroutineContext().ensureActive()
                val rawBitmap = getFrameSafely(
                    retriever = retriever,
                    timeUs = currentTimeUs,
                    width = targetWidth,
                    height = targetHeight
                )

                if (rawBitmap != null) {
                    val inputMat = Mat()
                    Utils.bitmapToMat(rawBitmap, inputMat)

                    val scaledInputMat = Mat()
                    if (inputMat.cols() != targetWidth || inputMat.rows() != targetHeight) {
                        Imgproc.resize(inputMat, scaledInputMat, Size(targetWidth.toDouble(), targetHeight.toDouble()))
                    } else {
                        inputMat.copyTo(scaledInputMat)
                    }

                    val origTraj = if (i < trajectory.size) trajectory[i] else trajectory.last()
                    val smoothTraj = if (i < smoothedTrajectory.size) smoothedTrajectory[i] else smoothedTrajectory.last()

                    // Step 1: Build stabilization matrix (pure counter-motion with edge-safe clamping)
                    val correctionMatrix = trajectorySmoother.buildCorrectionMatrix(
                        origTraj = origTraj,
                        smoothTraj = smoothTraj,
                        frameWidth = targetWidth,
                        frameHeight = targetHeight,
                        effectiveCropRatio = effectiveCropRatio
                    )

                    // Step 2: Apply warp — output canvas is same size as input.
                    // Borders outside the warped region are filled via BORDER_REPLICATE.
                    val warpedMat = Mat()
                    Imgproc.warpAffine(
                        scaledInputMat,
                        warpedMat,
                        correctionMatrix,
                        Size(targetWidth.toDouble(), targetHeight.toDouble()),
                        Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE
                    )

                    // Step 3: Crop to Safe ROI — removes any border region the warp introduced.
                    // uniformSafeRoi is pre-computed from all frames so the crop is the same for
                    // every frame (no flickering zoom), and is guaranteed to be border-free.
                    val roiMat = warpedMat.submat(uniformSafeRoi)

                    // Step 4: Resize back to original resolution — this IS the zoom effect.
                    // A smaller ROI resized to 1920×1080 produces a larger apparent zoom.
                    val finalMat = Mat()
                    Imgproc.resize(
                        roiMat,
                        finalMat,
                        Size(targetWidth.toDouble(), targetHeight.toDouble()),
                        0.0,
                        0.0,
                        Imgproc.INTER_LINEAR
                    )

                    // Optional Color Grading Preset application
                    config.preset?.let { presetParams ->
                        applyColorGrading(finalMat, presetParams)
                    }

                    // Step 5: Encode the final, border-free, full-resolution frame
                    val frameTimeUs = (i * 1_000_000L / fps).toLong()
                    feedFrameToEncoder(
                        encoder = encoder,
                        mat = finalMat,
                        colorFormat = chosenColorFormat,
                        presentationTimeUs = frameTimeUs
                    )

                    // Drain encoder safely
                    drainEncoderSafely(
                        encoder = encoder,
                        muxer = muxer,
                        bufferInfo = bufferInfo,
                        endOfStream = false,
                        getVideoTrack = { muxerVideoTrack },
                        onTrackAdded = { trackIndex ->
                            muxerVideoTrack = trackIndex
                            formatChanged = true
                            muxer.start()
                            muxerStarted = true
                            Log.i(TAG, "[TRACK_ADDED] MediaMuxer iniciado com videoTrack=$trackIndex")
                        },
                        isMuxerStarted = { muxerStarted },
                        pendingFrames = pendingFrames,
                        onSampleWritten = { bytes ->
                            samplesWritten++
                            totalBytesWritten += bytes
                        },
                        onEosReceived = {
                            eosReceived = true
                        }
                    )

                    inputMat.release()
                    scaledInputMat.release()
                    correctionMatrix.release()
                    warpedMat.release()
                    // roiMat is a submat (no copy), release is safe but not required
                    finalMat.release()
                    rawBitmap.recycle()
                }

                currentTimeUs += frameStepUs

                if ((i + 1) % (fps.roundToInt().coerceAtLeast(1) / 2) == 0 || (i + 1) == totalActualFrames) {
                    val renderPercent = 54 + (((i + 1).toFloat() / totalActualFrames.toFloat()) * 40).toInt().coerceIn(0, 40)
                    val elapsedRenderSec = (System.currentTimeMillis() - renderStartTime) / 1000.0
                    val renderFps = if (elapsedRenderSec > 0) (i + 1) / elapsedRenderSec else 1.0
                    val remainingRenderSec = ((totalActualFrames - (i + 1)) / renderFps).toLong().coerceAtLeast(1L)

                    onProgress(
                        StabilizationProgress(
                            stage = StabilizationStage.RECREATING_VIDEO,
                            progressPercent = renderPercent,
                            currentFrame = i + 1,
                            totalFrames = totalActualFrames,
                            estimatedSecondsRemaining = remainingRenderSec
                        )
                    )
                }
            }

            // Signal EOS to encoder with retries
            var eosQueued = false
            for (attempt in 0 until 20) {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, currentTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                    Log.i(TAG, "EOS sinalizado no MediaCodec com sucesso (timestamp=$currentTimeUs)")
                    break
                }
                delay(10)
            }

            // Final drain until EOS received or timeout limit reached
            drainEncoderSafely(
                encoder = encoder,
                muxer = muxer,
                bufferInfo = bufferInfo,
                endOfStream = true,
                getVideoTrack = { muxerVideoTrack },
                onTrackAdded = { trackIndex ->
                    muxerVideoTrack = trackIndex
                    formatChanged = true
                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "[TRACK_ADDED] MediaMuxer iniciado no drain final (videoTrack=$trackIndex)")
                },
                isMuxerStarted = { muxerStarted },
                pendingFrames = pendingFrames,
                onSampleWritten = { bytes ->
                    samplesWritten++
                    totalBytesWritten += bytes
                },
                onEosReceived = {
                    eosReceived = true
                }
            )
        } catch (t: Throwable) {
            primaryError = t
            throw t
        } finally {
            // Sequência de liberação estrita conforme Regra 3:
            // 1. muxer.stop() (apenas se muxerStarted == true e samplesWritten > 0)
            try {
                if (muxerStarted && samplesWritten > 0L) {
                    Log.i(TAG, "[MUXER_STOP] Finalizando MediaMuxer com sucesso ($samplesWritten samples, $totalBytesWritten bytes)...")
                    muxer.stop()
                } else if (!muxerStarted) {
                    Log.w(TAG, "[MUXER_NEVER_STARTED] MediaMuxer nunca foi iniciado")
                } else {
                    Log.w(TAG, "[MUXER_STOP] MediaMuxer iniciado porém nenhum sample foi escrito ($samplesWritten). Ignorando chamada a stop() para evitar IllegalStateException.")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao parar MediaMuxer: ${e.message}", e)
                if (primaryError == null) primaryError = e
            }

            // 2. muxer.release()
            try {
                muxer.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao liberar MediaMuxer: ${e.message}")
                if (primaryError == null) primaryError = e
            }

            // 3. encoder.stop()
            try {
                encoder.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao parar MediaCodec: ${e.message}")
                if (primaryError == null) primaryError = e
            }

            // 4. encoder.release()
            try {
                encoder.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao liberar MediaCodec: ${e.message}")
                if (primaryError == null) primaryError = e
            }

            // 5. retriever.release()
            try {
                retriever.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao liberar MediaMetadataRetriever: ${e.message}")
                if (primaryError == null) primaryError = e
            }

            pendingFrames.clear()
        }

        // -------------------------------------------------------------
        // ETAPA 6: Validação Pré-MediaStore e Finalizando MP4
        // -------------------------------------------------------------
        onProgress(
            StabilizationProgress(
                stage = StabilizationStage.FINALIZING_MP4,
                progressPercent = 96,
                currentFrame = totalActualFrames,
                totalFrames = totalActualFrames,
                estimatedSecondsRemaining = 2L
            )
        )

        // Validação obrigatória pré-MediaStore conforme Regra 4
        try {
            check(formatChanged) { "Validação pré-MediaStore falhou: formatChanged == false (MediaCodec nunca emitiu INFO_OUTPUT_FORMAT_CHANGED)" }
            check(muxerStarted) { "Validação pré-MediaStore falhou: muxerStarted == false (MediaMuxer nunca foi iniciado)" }
            check(samplesWritten > 0L) { "Validação pré-MediaStore falhou: samplesWritten ($samplesWritten) <= 0" }
            check(tempVideoFile.exists()) { "Validação pré-MediaStore falhou: arquivo temporário não encontrado (${tempVideoFile.absolutePath})" }
            check(tempVideoFile.length() > 1024L) { "Validação pré-MediaStore falhou: arquivo temporário corrompido/vazio (${tempVideoFile.length()} bytes <= 1024L)" }
        } catch (vEx: IllegalStateException) {
            DebugCenter.logAndToastError(
                context = context,
                module = LogModule.MediaCodec,
                errorCode = "#MP4_0B",
                detailedMessage = "Validação pré-MediaStore falhou: ${vEx.message}",
                throwable = vEx
            )
            try { if (tempVideoFile.exists()) tempVideoFile.delete() } catch (ignored: Exception) {}
            try { if (finalOutputFile.exists()) finalOutputFile.delete() } catch (ignored: Exception) {}
            throw vEx
        }

        // Interleave original audio if requested and audio exists
        if (config.keepOriginalAudio && hasAudioTrack && tempVideoFile.exists() && tempVideoFile.length() > 1024L) {
            if (finalOutputFile.exists()) {
                finalOutputFile.delete()
            }
            val interleavedOk = MediaMuxerInterleaver.interleave(
                context = context,
                videoOnlyFile = tempVideoFile,
                audioSourceUri = inputUri,
                outputFile = finalOutputFile
            )
            if (interleavedOk && finalOutputFile.exists() && finalOutputFile.length() > 1024L) {
                tempVideoFile.delete()
            } else {
                Log.w(TAG, "Multiplexação de áudio falhou ou gerou arquivo inválido. Utilizando vídeo estabilizado sem áudio.")
                if (finalOutputFile.exists()) {
                    finalOutputFile.delete()
                }
                val renamed = tempVideoFile.renameTo(finalOutputFile)
                if (!renamed) {
                    tempVideoFile.copyTo(finalOutputFile, overwrite = true)
                    tempVideoFile.delete()
                }
            }
        } else {
            if (finalOutputFile.exists()) {
                finalOutputFile.delete()
            }
            val renamed = tempVideoFile.renameTo(finalOutputFile)
            if (!renamed) {
                tempVideoFile.copyTo(finalOutputFile, overwrite = true)
                tempVideoFile.delete()
            }
        }

        // Validação final do arquivo de saída estabilizado
        if (!finalOutputFile.exists() || finalOutputFile.length() <= 1024L) {
            val length = if (finalOutputFile.exists()) finalOutputFile.length() else 0L
            try { if (finalOutputFile.exists()) finalOutputFile.delete() } catch (ignored: Exception) {}
            try { if (tempVideoFile.exists()) tempVideoFile.delete() } catch (ignored: Exception) {}
            val err = IllegalStateException("Falha crítica: vídeo final estabilizado tem $length bytes (<= 1024L). Publicação cancelada.")
            DebugCenter.logAndToastError(context, LogModule.MediaCodec, "#MP4_0B", err.message ?: "", err)
            throw err
        }

        val originalSize = getFileSize(context, inputUri)
        val finalSize = finalOutputFile.length()

        onProgress(
            StabilizationProgress(
                stage = StabilizationStage.FINALIZING_MP4,
                progressPercent = 100,
                currentFrame = totalActualFrames,
                totalFrames = totalActualFrames,
                estimatedSecondsRemaining = 0L,
                isCompleted = true
            )
        )

        StabilizationResult(
            originalUri = inputUri,
            stabilizedUri = Uri.fromFile(finalOutputFile),
            durationMs = durationMs,
            fps = fps,
            width = targetWidth,
            height = targetHeight,
            originalSizeBytes = originalSize,
            stabilizedSizeBytes = finalSize,
            outputFilePath = finalOutputFile.absolutePath
        )
    }

    private fun selectColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
        val supportedFormats = capabilities.colorFormats
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        for (pref in preferred) {
            if (supportedFormats.contains(pref)) {
                return pref
            }
        }
        return supportedFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private fun feedFrameToEncoder(
        encoder: MediaCodec,
        mat: Mat,
        colorFormat: Int,
        presentationTimeUs: Long
    ) {
        val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
        if (inputBufferIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: return
            inputBuffer.clear()

            val yuvMat = Mat()
            val yuvBytes = ByteArray((mat.cols() * mat.rows() * 3) / 2)

            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                Imgproc.cvtColor(mat, yuvMat, Imgproc.COLOR_RGBA2YUV_YV12)
                val yv12Bytes = ByteArray((mat.cols() * mat.rows() * 3) / 2)
                yuvMat.get(0, 0, yv12Bytes)
                fastYv12ToNV12(yv12Bytes, yuvBytes, mat.cols(), mat.rows())
            } else {
                Imgproc.cvtColor(mat, yuvMat, Imgproc.COLOR_RGBA2YUV_I420)
                yuvMat.get(0, 0, yuvBytes)
            }

            inputBuffer.put(yuvBytes)
            encoder.queueInputBuffer(
                inputBufferIndex,
                0,
                yuvBytes.size,
                presentationTimeUs,
                0
            )
            yuvMat.release()
        }
    }

    private fun fastYv12ToNV12(yv12: ByteArray, nv12: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        val uvSize = ySize / 4
        // Fast block copy of Y plane
        System.arraycopy(yv12, 0, nv12, 0, ySize)
        val vStart = ySize
        val uStart = ySize + uvSize
        for (i in 0 until uvSize) {
            nv12[ySize + i * 2] = yv12[uStart + i]
            nv12[ySize + i * 2 + 1] = yv12[vStart + i]
        }
    }

    /**
     * Non-blocking, deadlock-free encoder draining loop.
     * Uses consecutive timeout limit to ensure it never hangs indefinitely.
     */
    private fun drainEncoderSafely(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        getVideoTrack: () -> Int,
        onTrackAdded: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        pendingFrames: ArrayList<PendingFrame>,
        onSampleWritten: (Long) -> Unit,
        onEosReceived: () -> Unit
    ) {
        var consecutiveTimeouts = 0
        val maxTimeouts = if (endOfStream) 40 else 4

        while (consecutiveTimeouts < maxTimeouts) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    consecutiveTimeouts++
                }
                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    consecutiveTimeouts = 0
                    if (!isMuxerStarted()) {
                        val newFormat = encoder.outputFormat
                        Log.i(TAG, "[FORMAT_CHANGED] Novo formato emitido pelo MediaCodec: $newFormat")
                        val trackIndex = muxer.addTrack(newFormat)
                        onTrackAdded(trackIndex)

                        // Despejar imediatamente frames que foram acumulados antes do format changed
                        if (pendingFrames.isNotEmpty()) {
                            Log.i(TAG, "[SAMPLE_WRITTEN] Despejando ${pendingFrames.size} frames em buffer para MediaMuxer...")
                            for (pending in pendingFrames) {
                                muxer.writeSampleData(trackIndex, pending.data, pending.info)
                                onSampleWritten(pending.info.size.toLong())
                            }
                            pendingFrames.clear()
                        }
                    }
                }
                encoderStatus >= 0 -> {
                    consecutiveTimeouts = 0
                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }

                        val track = getVideoTrack()
                        if (bufferInfo.size > 0) {
                            if (isMuxerStarted() && track >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(track, encodedData, bufferInfo)
                                onSampleWritten(bufferInfo.size.toLong())
                            } else {
                                // Preservar frames gerados antes de muxer.start()
                                val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                copy.put(encodedData)
                                copy.flip()
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                pendingFrames.add(PendingFrame(data = copy, info = infoCopy))
                                Log.d(TAG, "Frame acumulado em pendingFrames (#${pendingFrames.size}, tamanho=${bufferInfo.size})")
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "[EOS_RECEIVED] Sinal EOS recebido do encoder com sucesso")
                        onEosReceived()
                        break
                    }
                }
            }
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            if (uri.scheme == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize
                } ?: 0L
            } else {
                File(uri.path ?: "").length()
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun getFrameSafely(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        // ALWAYS use getScaledFrameAtTime because it respects METADATA_KEY_VIDEO_ROTATION.
        // getFrameAtIndex ignores rotation on Android 9+ and produces unrotated/stretched frames.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val bmp = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width,
                    height
                )
                if (bmp != null) return bmp
            } catch (e: Throwable) {
                Log.w(TAG, "getScaledFrameAtTime warning: ${e.message}")
            }
        }

        return try {
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bmp != null) {
                if (bmp.width != width || bmp.height != height) {
                    val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                    if (scaled != bmp) bmp.recycle()
                    scaled
                } else {
                    bmp
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getFrameAtTime error: ${e.message}")
            null
        }
    }

    private fun applyColorGrading(mat: Mat, params: ColorGradingParams) {
        if (params.isNeutral()) return

        // 1. Exposure, Brightness, Shadows and Contrast
        val exposureScale = Math.pow(2.0, params.exposure.toDouble()).toFloat()
        val alpha = (params.contrast * exposureScale).toDouble().coerceIn(0.2, 3.0)
        val beta = (params.brightness * 35.0) + (1.0 - params.contrast) * 64.0 + (params.shadows * 15.0)
        mat.convertTo(mat, -1, alpha, beta)

        // 2. Sharpness & Definition (Unsharp Mask filter via OpenCV)
        if (params.sharpness > 0.02f || params.definition > 0.02f) {
            val blurMat = Mat()
            val totalSharp = (params.sharpness * 1.5 + params.definition * 0.8).toDouble()
            Imgproc.GaussianBlur(mat, blurMat, Size(0.0, 0.0), 3.0)
            Core.addWeighted(mat, 1.0 + totalSharp, blurMat, -totalSharp, 0.0, mat)
            blurMat.release()
        }

        // 3. Temperature & Tint adjustment (Warm: boost R, reduce B; Tint: shift G)
        if (kotlin.math.abs(params.temperature) > 0.02f || kotlin.math.abs(params.tint) > 0.02f) {
            val channels = ArrayList<Mat>(4)
            Core.split(mat, channels)
            if (channels.size >= 3) {
                val tempOffset = (params.temperature * 25.0).toDouble()
                val tintOffset = (params.tint * 20.0).toDouble()
                if (kotlin.math.abs(params.temperature) > 0.02f) {
                    Core.add(channels[0], org.opencv.core.Scalar(tempOffset), channels[0])
                    Core.subtract(channels[2], org.opencv.core.Scalar(tempOffset), channels[2])
                }
                if (kotlin.math.abs(params.tint) > 0.02f) {
                    // Positive tint shifts toward magenta (reduces green, boosts red & blue)
                    val greenShift = -(params.tint * 25.0).toDouble()
                    Core.add(channels[1], org.opencv.core.Scalar(greenShift), channels[1])
                    Core.add(channels[0], org.opencv.core.Scalar(-greenShift * 0.5), channels[0])
                    Core.add(channels[2], org.opencv.core.Scalar(-greenShift * 0.5), channels[2])
                }
                Core.merge(channels, mat)
            }
            for (ch in channels) ch.release()
        }

        // 4. Saturation & Vibrance adjustment
        if (kotlin.math.abs(params.saturation - 1.0f) > 0.05f || kotlin.math.abs(params.vibrance) > 0.05f) {
            val hsvMat = Mat()
            Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)
            val hsvChannels = ArrayList<Mat>(3)
            Core.split(hsvMat, hsvChannels)
            val satScale = (params.saturation + params.vibrance * 0.4).toDouble().coerceIn(0.0, 2.5)
            hsvChannels[1].convertTo(hsvChannels[1], -1, satScale, 0.0)
            Core.merge(hsvChannels, hsvMat)
            Imgproc.cvtColor(hsvMat, mat, Imgproc.COLOR_HSV2RGB)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2RGBA)
            hsvMat.release()
            for (ch in hsvChannels) ch.release()
        }
    }
}

