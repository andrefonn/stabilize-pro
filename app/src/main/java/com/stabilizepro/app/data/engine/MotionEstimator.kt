package com.stabilizepro.app.data.engine

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 2D Inter-frame transformation representing translation (dx, dy) and in-plane rotation (da)
 * centered at the frame's optical center.
 */
data class FrameTransform(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val da: Double = 0.0
)

/**
 * Motion Estimator using OpenCV Shi-Tomasi Corner Detection, Lucas-Kanade Optical Flow,
 * and RANSAC Partial Affine Estimation.
 *
 * Detects fresh features on each frame pair to eliminate foreground tracking drift,
 * and extracts motion relative to the frame center to decouple rotation from translation.
 */
class MotionEstimator(
    private val maxCorners: Int = 250,
    private val qualityLevel: Double = 0.01,
    private val minDistance: Double = 20.0
) {
    fun reset() {
        // Stateless between frames for maximum tracking accuracy
    }

    /**
     * Estimates the motion transform from [prevGray] to [currGray].
     * Both input Mats must be single-channel 8-bit grayscale (CV_8UC1).
     */
    fun estimateMotion(prevGray: Mat, currGray: Mat): FrameTransform {
        // Step 1: Detect fresh, well-distributed Shi-Tomasi corners in prevGray
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            prevGray,
            corners,
            maxCorners,
            qualityLevel,
            minDistance
        )

        val cornerArray = corners.toArray()
        corners.release()

        if (cornerArray.size < 6) {
            return FrameTransform(0.0, 0.0, 0.0)
        }

        val prevPoints = MatOfPoint2f(*cornerArray)
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        // Step 2: Track corners to currGray using Pyramidal Lucas-Kanade Optical Flow
        Video.calcOpticalFlowPyrLK(
            prevGray,
            currGray,
            prevPoints,
            nextPoints,
            status,
            err
        )

        val statusArr = status.toArray()
        val prevArr = prevPoints.toArray()
        val nextArr = nextPoints.toArray()

        val validPrev = ArrayList<Point>()
        val validNext = ArrayList<Point>()

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1 && i < prevArr.size && i < nextArr.size) {
                validPrev.add(prevArr[i])
                validNext.add(nextArr[i])
            }
        }

        prevPoints.release()
        nextPoints.release()
        status.release()
        err.release()

        if (validPrev.size < 6) {
            return FrameTransform(0.0, 0.0, 0.0)
        }

        // Step 3: Compute median translation as a mathematically robust fallback
        val dxList = validPrev.indices.map { validNext[it].x - validPrev[it].x }.sorted()
        val dyList = validPrev.indices.map { validNext[it].y - validPrev[it].y }.sorted()
        val medianDx = dxList[dxList.size / 2]
        val medianDy = dyList[dyList.size / 2]

        val matPrev = MatOfPoint2f(*validPrev.toTypedArray())
        val matNext = MatOfPoint2f(*validNext.toTypedArray())

        // Step 4: Estimate Partial Affine Transformation (Translation + Rotation + Uniform Scale) via RANSAC
        val inliers = Mat()
        val affineTransform = Calib3d.estimateAffinePartial2D(
            matPrev,
            matNext,
            inliers,
            Calib3d.RANSAC,
            3.0
        )

        matPrev.release()
        matNext.release()
        inliers.release()

        var transform = FrameTransform(medianDx, medianDy, 0.0)

        if (!affineTransform.empty() && affineTransform.rows() == 2 && affineTransform.cols() == 3) {
            val a00 = affineTransform.get(0, 0)[0]
            val a01 = affineTransform.get(0, 1)[0]
            val a02 = affineTransform.get(0, 2)[0]
            val a10 = affineTransform.get(1, 0)[0]
            val a11 = affineTransform.get(1, 1)[0]
            val a12 = affineTransform.get(1, 2)[0]

            // Decouple rotation from translation by evaluating displacement AT THE FRAME CENTER
            val cx = prevGray.cols() / 2.0
            val cy = prevGray.rows() / 2.0

            val dxCenter = a00 * cx + a01 * cy + a02 - cx
            val dyCenter = a10 * cx + a11 * cy + a12 - cy
            val da = atan2(a10, a00)

            // Sanity check against extreme outliers / scene transitions
            val maxAllowedShiftX = prevGray.cols() * 0.35
            val maxAllowedShiftY = prevGray.rows() * 0.35

            if (!dxCenter.isNaN() && !dyCenter.isNaN() && !da.isNaN() &&
                abs(dxCenter) < maxAllowedShiftX &&
                abs(dyCenter) < maxAllowedShiftY &&
                abs(da) < 0.25 // max ~14 degrees inter-frame rotation
            ) {
                transform = FrameTransform(dxCenter, dyCenter, da)
            }

            affineTransform.release()
        }

        return transform
    }
}
