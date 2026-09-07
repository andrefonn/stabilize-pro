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
}
