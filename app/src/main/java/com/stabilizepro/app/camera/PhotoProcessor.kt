package com.stabilizepro.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.stabilizepro.app.presets.ColorGradingParams
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

object PhotoProcessor {

    private const val TAG = "PhotoProcessor"

    /**
     * Applies the active [ColorGradingParams] to the captured photo file in full resolution,
     * ensuring the photo orientation is physically upright (portrait) before saving.
     */
    fun processPhoto(photoFile: File, params: ColorGradingParams): Result<File> {
        if (!photoFile.exists()) {
            return Result.failure(IllegalStateException("Arquivo de foto não encontrado: ${photoFile.absolutePath}"))
        }

        return try {
            // 1. Detect rotation from EXIF metadata
            val exif = try {
                android.media.ExifInterface(photoFile.absolutePath)
            } catch (e: Exception) {
                null
            }

            val exifOrientation = exif?.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            ) ?: android.media.ExifInterface.ORIENTATION_NORMAL

            var rotationDegrees = when (exifOrientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            // Decode image bounds first to check aspect ratio
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.absolutePath, boundsOptions)

            // If EXIF didn't report rotation but image is in landscape (width > height),
            // camera sensor mounted at 90 deg requires 90 deg clockwise rotation for portrait.
            if (rotationDegrees == 0 && boundsOptions.outWidth > boundsOptions.outHeight) {
                rotationDegrees = 90
            }

            // If already neutral and no rotation needed, keep original file
            if (params.isNeutral() && rotationDegrees == 0) {
                return Result.success(photoFile)
            }

            // Decode full-resolution bitmap
            val rawBitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                ?: return Result.failure(IllegalStateException("Não foi possível decodificar o arquivo de imagem."))

            // Rotate bitmap physically so pixels are upright
            val workingBitmap = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                val rotated = Bitmap.createBitmap(
                    rawBitmap,
                    0, 0,
                    rawBitmap.width, rawBitmap.height,
                    matrix,
                    true
                )
                rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }

            val mutableBitmap = if (workingBitmap.isMutable) {
                workingBitmap
            } else {
                val copy = workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
                workingBitmap.recycle()
                copy
            }

            // Apply Color Grading if not neutral
            if (!params.isNeutral()) {
                val mat = Mat()
                Utils.bitmapToMat(mutableBitmap, mat)

                // Exposure, Brightness, Shadows and Contrast
                val exposureScale = Math.pow(2.0, params.exposure.toDouble()).toFloat()
                val alpha = (params.contrast * exposureScale).toDouble().coerceIn(0.2, 3.0)
                val beta = (params.brightness * 35.0) + (1.0 - params.contrast) * 64.0 + (params.shadows * 15.0)
                mat.convertTo(mat, -1, alpha, beta)

                // Sharpness & Definition (Unsharp Mask filter via OpenCV)
                if (params.sharpness > 0.02f || params.definition > 0.02f) {
                    val blurMat = Mat()
                    val totalSharp = (params.sharpness * 1.5 + params.definition * 0.8).toDouble()
                    Imgproc.GaussianBlur(mat, blurMat, Size(0.0, 0.0), 3.0)
                    Core.addWeighted(mat, 1.0 + totalSharp, blurMat, -totalSharp, 0.0, mat)
                    blurMat.release()
                }

                // Temperature & Tint adjustment
                if (kotlin.math.abs(params.temperature) > 0.02f || kotlin.math.abs(params.tint) > 0.02f) {
                    val channels = ArrayList<Mat>(4)
                    Core.split(mat, channels)
                    if (channels.size >= 3) {
                        val tempOffset = (params.temperature * 25.0).toDouble()
                        if (kotlin.math.abs(params.temperature) > 0.02f) {
                            Core.add(channels[0], Scalar(tempOffset), channels[0])
                            Core.subtract(channels[2], Scalar(tempOffset), channels[2])
                        }
                        if (kotlin.math.abs(params.tint) > 0.02f) {
                            val greenShift = -(params.tint * 25.0).toDouble()
                            Core.add(channels[1], Scalar(greenShift), channels[1])
                            Core.add(channels[0], Scalar(-greenShift * 0.5), channels[0])
                            Core.add(channels[2], Scalar(-greenShift * 0.5), channels[2])
                        }
                        Core.merge(channels, mat)
                    }
                    for (ch in channels) ch.release()
                }

                // Saturation & Vibrance adjustment
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

                // Convert back to bitmap
                Utils.matToBitmap(mat, mutableBitmap)
                mat.release()
            }

            // Save upright photo back to file
            FileOutputStream(photoFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            mutableBitmap.recycle()

            // Update EXIF orientation to NORMAL since pixels are now physically upright
            try {
                val updatedExif = android.media.ExifInterface(photoFile.absolutePath)
                updatedExif.setAttribute(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                )
                updatedExif.saveAttributes()
            } catch (ignored: Exception) {}

            Log.i(TAG, "Processamento de rotação e cor na foto concluído: ${photoFile.length()} bytes (rotacionado: $rotationDegrees deg)")
            Result.success(photoFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao processar foto: ${e.message}", e)
            Result.failure(e)
        }
    }
}
