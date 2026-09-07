package com.stabilizepro.app.data.engine

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Accumulated camera trajectory state at a given frame.
 */
data class TrajectoryPoint(
    val x: Double,
    val y: Double,
    val a: Double
)

/**
 * Computes accumulated camera trajectory, smooths it using a Gaussian weighted sliding window,
 * and builds 2x3 Affine Transformation Matrices with mathematical Safe ROI crop guarantees.
 *
 * Full pipeline for each frame:
 *   warpAffine(stabilization matrix) → crop(safeRoi) → resize(originalSize) → encode
 */
class TrajectorySmoother(
    private val radius: Int = 35,
    private val cropPercent: Int = 7
) {
    /**
     * Converts a list of inter-frame transforms into an accumulated camera trajectory.
     */
    fun accumulateTrajectory(transforms: List<FrameTransform>): List<TrajectoryPoint> {
        val trajectory = ArrayList<TrajectoryPoint>(transforms.size + 1)
        var x = 0.0
        var y = 0.0
        var a = 0.0
        trajectory.add(TrajectoryPoint(x, y, a))

        for (t in transforms) {
            x += t.dx
            y += t.dy
            a += t.da
            trajectory.add(TrajectoryPoint(x, y, a))
        }
        return trajectory
    }

    /**
     * Smooths trajectory using a temporal Gaussian-weighted sliding window.
     */
    fun smoothTrajectory(trajectory: List<TrajectoryPoint>): List<TrajectoryPoint> {
        val count = trajectory.size
        val smoothed = ArrayList<TrajectoryPoint>(count)
        val sigma = (radius.toDouble() / 2.0).coerceAtLeast(1.0)
        val twoSigmaSq = 2.0 * sigma * sigma

        for (i in 0 until count) {
            var sumX = 0.0
            var sumY = 0.0
            var sumA = 0.0
            var totalWeight = 0.0

            val start = (i - radius).coerceAtLeast(0)
            val end = (i + radius).coerceAtMost(count - 1)

            for (j in start..end) {
                val dist = (j - i).toDouble()
                val weight = exp(-(dist * dist) / twoSigmaSq)
                sumX += trajectory[j].x * weight
                sumY += trajectory[j].y * weight
                sumA += trajectory[j].a * weight
                totalWeight += weight
            }

            if (totalWeight > 0.0) {
                smoothed.add(
                    TrajectoryPoint(
                        x = sumX / totalWeight,
                        y = sumY / totalWeight,
                        a = sumA / totalWeight
                    )
                )
            } else {
                smoothed.add(trajectory[i])
            }
        }
        return smoothed
    }

    /**
     * Calculates the dynamic crop ratio adaptively based on the actual camera trajectory shake,
     * clamped to [maxCropRatio] (e.g. 0.03 for Low, 0.07 for Medium, 0.12 for High).
     */
    fun calculateDynamicCropRatio(
        trajectory: List<TrajectoryPoint>,
        smoothedTrajectory: List<TrajectoryPoint>,
        frameWidth: Int,
        frameHeight: Int,
        maxCropRatio: Double
    ): Double {
        val count = minOf(trajectory.size, smoothedTrajectory.size)
        if (count < 2) return maxCropRatio.coerceIn(0.015, 0.12)

        val halfW = frameWidth / 2.0
        val halfH = frameHeight / 2.0

        val ratios = ArrayList<Double>(count)
        for (i in 0 until count) {
            // Difference vector: how far the camera drifted from the smooth path
            val diffX = abs(smoothedTrajectory[i].x - trajectory[i].x)
            val diffY = abs(smoothedTrajectory[i].y - trajectory[i].y)
            val diffA = abs(smoothedTrajectory[i].a - trajectory[i].a)

            val reqX = diffX + halfH * abs(sin(diffA))
            val reqY = diffY + halfW * abs(sin(diffA))

            val ratioX = reqX / halfW
            val ratioY = reqY / halfH
            ratios.add(max(ratioX, ratioY))
        }

        ratios.sort()
        // Use 95th percentile so a single wild jolt does not over-crop the entire video
        val p95Index = ((count - 1) * 0.95).toInt().coerceIn(0, count - 1)
        val p95Ratio = ratios[p95Index]

        // Add 15% headroom for safety, clamped to user intensity limit
        return (p95Ratio * 1.15).coerceIn(0.02, maxCropRatio)
    }

    /**
     * Computes the uniform Safe ROI (Region of Interest) centered on the frame,
     * maintaining the exact original aspect ratio.
     */
    fun calculateSafeRoi(
        frameWidth: Int,
        frameHeight: Int,
        effectiveCropRatio: Double
    ): Rect {
        val cropPxX = ((frameWidth / 2.0) * effectiveCropRatio).toInt().coerceAtLeast(1)
        val cropPxY = ((frameHeight / 2.0) * effectiveCropRatio).toInt().coerceAtLeast(1)

        // Keep width and height even integers for video encoder compatibility
        val roiW = ((frameWidth - 2 * cropPxX) / 2) * 2
        val roiH = ((frameHeight - 2 * cropPxY) / 2) * 2
        val roiX = (frameWidth - roiW) / 2
        val roiY = (frameHeight - roiH) / 2

        return Rect(roiX, roiY, roiW, roiH)
    }

    /**
     * Builds the 2x3 affine correction matrix for OpenCV warpAffine.
     *
     * Mathematical convention:
     *   diff = smooth - orig (cancels jitter by moving frame toward the smooth path)
     *   Clamps displacement to effectiveCropRatio so that border exposure is strictly impossible.
     */
    fun buildCorrectionMatrix(
        origTraj: TrajectoryPoint,
        smoothTraj: TrajectoryPoint,
        frameWidth: Int,
        frameHeight: Int,
        effectiveCropRatio: Double
    ): Mat {
        // Corrective delta: move actual camera position toward the smooth target path
        val rawDx = smoothTraj.x - origTraj.x
        val rawDy = smoothTraj.y - origTraj.y
        val rawDa = smoothTraj.a - origTraj.a

        // Limit rotational correction to avoid perspective distortion
        val da = rawDa.coerceIn(-0.06, 0.06)
        val cosA = cos(da)
        val sinA = sin(da)

        val cx = frameWidth / 2.0
        val cy = frameHeight / 2.0

        // Guarantee that the displacement never shifts image edges past the cropped ROI margin.
        // Safety buffer of 2px prevents subpixel interpolation bleeding.
        val maxAllowedDx = max(0.0, cx * effectiveCropRatio - cy * abs(sinA) - 2.0)
        val maxAllowedDy = max(0.0, cy * effectiveCropRatio - cx * abs(sinA) - 2.0)

        val dx = rawDx.coerceIn(-maxAllowedDx, maxAllowedDx)
        val dy = rawDy.coerceIn(-maxAllowedDy, maxAllowedDy)

        // OpenCV warpAffine forward mapping around center (cx, cy):
        //   x_dst = cosA*(x_src - cx) - sinA*(y_src - cy) + cx + dx
        //   y_dst = sinA*(x_src - cx) + cosA*(y_src - cy) + cy + dy
        val tx = cx - cosA * cx + sinA * cy + dx
        val ty = cy - sinA * cx - cosA * cy + dy

        val mat = Mat(2, 3, CvType.CV_64F)
        mat.put(0, 0, cosA, -sinA, tx)
        mat.put(1, 0, sinA,  cosA, ty)

        return mat
    }
}
