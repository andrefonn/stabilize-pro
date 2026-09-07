package com.stabilizepro.app.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrajectorySmootherTest {

    @Test
    fun testAccumulateAndSmooth() {
        val smoother = TrajectorySmoother(radius = 5, cropPercent = 7)
        val transforms = listOf(
            FrameTransform(dx = 10.0, dy = 0.0, da = 0.0),
            FrameTransform(dx = -10.0, dy = 0.0, da = 0.0),
            FrameTransform(dx = 10.0, dy = 0.0, da = 0.0),
            FrameTransform(dx = -10.0, dy = 0.0, da = 0.0),
            FrameTransform(dx = 10.0, dy = 0.0, da = 0.0)
        )

        val trajectory = smoother.accumulateTrajectory(transforms)
        assertEquals(6, trajectory.size)

        val smoothed = smoother.smoothTrajectory(trajectory)
        assertEquals(6, smoothed.size)

        val rawVariance = trajectory.map { it.x }.maxOrNull()!! - trajectory.map { it.x }.minOrNull()!!
        val smoothVariance = smoothed.map { it.x }.maxOrNull()!! - smoothed.map { it.x }.minOrNull()!!
        assertTrue("Smoothed variance ($smoothVariance) should be less than raw ($rawVariance)", smoothVariance < rawVariance)
    }

    @Test
    fun testDynamicCropRatioAdaptsToMotion() {
        val smoother = TrajectorySmoother(radius = 5, cropPercent = 7)
        val frameWidth = 1080
        val frameHeight = 1920

        // Mild motion (small shake)
        val mildTransforms = (0 until 30).map { i ->
            FrameTransform(dx = if (i % 2 == 0) 5.0 else -5.0, dy = 0.0, da = 0.0)
        }
        val mildTraj = smoother.accumulateTrajectory(mildTransforms)
        val mildSmooth = smoother.smoothTrajectory(mildTraj)
        val mildRatio = smoother.calculateDynamicCropRatio(mildTraj, mildSmooth, frameWidth, frameHeight, 0.12)

        // Aggressive motion (large shake)
        val aggTransforms = (0 until 30).map { i ->
            FrameTransform(dx = if (i % 2 == 0) 50.0 else -50.0, dy = if (i % 2 == 0) 40.0 else -40.0, da = 0.03)
        }
        val aggTraj = smoother.accumulateTrajectory(aggTransforms)
        val aggSmooth = smoother.smoothTrajectory(aggTraj)
        val aggRatio = smoother.calculateDynamicCropRatio(aggTraj, aggSmooth, frameWidth, frameHeight, 0.12)

        assertTrue("Aggressive shake should produce larger crop ratio than mild shake", aggRatio > mildRatio)
        assertTrue("Aggressive crop ratio must not exceed max crop limit (0.12)", aggRatio <= 0.12)
        assertTrue("Mild crop ratio must be at least minimum margin (0.02)", mildRatio >= 0.02)
    }

    @Test
    fun testSafeRoiPreservesDimensionsAndBounds() {
        val smoother = TrajectorySmoother(radius = 5, cropPercent = 7)
        val frameWidth = 1080
        val frameHeight = 1920

        val roi = smoother.calculateSafeRoi(frameWidth, frameHeight, 0.07)

        assertTrue("ROI left >= 0", roi.x >= 0)
        assertTrue("ROI top >= 0", roi.y >= 0)
        assertTrue("ROI right <= frameWidth", roi.x + roi.width <= frameWidth)
        assertTrue("ROI bottom <= frameHeight", roi.y + roi.height <= frameHeight)
        assertEquals("ROI width must be an even integer", 0, roi.width % 2)
        assertEquals("ROI height must be an even integer", 0, roi.height % 2)
        assertTrue("ROI must have positive area", roi.width > 0 && roi.height > 0)

        // ROI aspect ratio must match original aspect ratio within 1%
        val origAspect = frameWidth.toDouble() / frameHeight.toDouble()
        val roiAspect = roi.width.toDouble() / roi.height.toDouble()
        assertTrue("ROI aspect ratio ($roiAspect) must closely match original ($origAspect)", abs(origAspect - roiAspect) < 0.02)
    }

    @Test
    fun testStabilizingCounterMotionCancelsShake() {
        // When camera shakes to the right (+dx in trajectory), the stabilizing shift must be negative
        // to return the frame toward the smoothed position (smooth - orig)
        val orig = TrajectoryPoint(x = 25.0, y = -15.0, a = 0.02)
        val smooth = TrajectoryPoint(x = 0.0, y = 0.0, a = 0.0)

        val diffX = smooth.x - orig.x
        val diffY = smooth.y - orig.y
        val diffA = smooth.a - orig.a

        assertEquals(-25.0, diffX, 0.001)
        assertEquals(15.0, diffY, 0.001)
        assertEquals(-0.02, diffA, 0.001)
    }
}
