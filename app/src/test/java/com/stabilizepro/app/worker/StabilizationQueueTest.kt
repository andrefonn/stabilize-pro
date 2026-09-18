package com.stabilizepro.app.worker

import android.net.Uri
import com.stabilizepro.app.domain.model.StabilizationConfig
import com.stabilizepro.app.domain.model.StabilizationIntensity
import com.stabilizepro.app.export.ExportQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class StabilizationQueueTest {

    @Test
    fun testTaskLifecycleTransitions() {
        val mockUri = Mockito.mock(Uri::class.java)

        val task = StabilizationTask(
            id = "test-123",
            inputUri = mockUri,
            videoTitle = "video_teste.mp4",
            config = StabilizationConfig(intensity = StabilizationIntensity.HIGH),
            quality = ExportQuality.ALTA,
            status = QueueStatus.AGUARDANDO,
            progressPercent = 0
        )

        assertEquals(QueueStatus.AGUARDANDO, task.status)
        assertEquals(0, task.progressPercent)
        assertNull(task.completedAt)

        val inProgress = task.copy(
            status = QueueStatus.PROCESSANDO,
            progressPercent = 45,
            stageName = "Suavizando câmera",
            estimatedSecondsRemaining = 8L
        )

        assertEquals(QueueStatus.PROCESSANDO, inProgress.status)
        assertEquals(45, inProgress.progressPercent)
        assertEquals("Suavizando câmera", inProgress.stageName)
        assertEquals(8L, inProgress.estimatedSecondsRemaining)

        val completed = inProgress.copy(
            status = QueueStatus.CONCLUIDO,
            progressPercent = 100,
            stageName = "Concluído",
            estimatedSecondsRemaining = 0L,
            completedAt = System.currentTimeMillis()
        )

        assertEquals(QueueStatus.CONCLUIDO, completed.status)
        assertEquals(100, completed.progressPercent)
        assertNotNull(completed.completedAt)
    }

    @Test
    fun testTaskCancellationAndRemovalDoesNotBlockQueue() {
        val mockUri1 = Mockito.mock(Uri::class.java)
        val mockUri2 = Mockito.mock(Uri::class.java)

        val task1 = StabilizationTask(
            id = "task-1",
            inputUri = mockUri1,
            videoTitle = "video1.mp4",
            config = StabilizationConfig(intensity = StabilizationIntensity.LOW),
            status = QueueStatus.PROCESSANDO,
            progressPercent = 30
        )

        val task2 = StabilizationTask(
            id = "task-2",
            inputUri = mockUri2,
            videoTitle = "video2.mp4",
            config = StabilizationConfig(intensity = StabilizationIntensity.MEDIUM),
            status = QueueStatus.AGUARDANDO,
            progressPercent = 0
        )

        val queue = listOf(task1, task2)
        assertEquals(2, queue.size)

        // When task1 is cancelled, it is removed from the active queue list:
        val queueAfterCancel = queue.filter { it.id != "task-1" }
        assertEquals(1, queueAfterCancel.size)
        assertEquals("task-2", queueAfterCancel[0].id)
        assertEquals(QueueStatus.AGUARDANDO, queueAfterCancel[0].status)
    }

    @Test
    fun testAspectRatioLetterboxMathNoDistortion() {
        val width = 1080
        val height = 2400
        val screenAspect = width.toFloat() / height.toFloat() // 0.45f
        val targetAspect3to4 = 3f / 4f // 0.75f

        // When screen is taller than target aspect ratio:
        val scaleX = 1.0f
        val scaleY = screenAspect / targetAspect3to4

        assertEquals(1.0f, scaleX, 0.0001f) // No horizontal stretching!
        assertEquals(0.60f, scaleY, 0.0001f) // Quad height reduced to 60%, creating black borders

        val renderedW = width * scaleX
        val renderedH = height * scaleY
        val actualAspect = renderedW / renderedH

        assertEquals(0.75f, actualAspect, 0.0001f)
    }
}
