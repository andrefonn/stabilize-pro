package com.stabilizepro.app.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCenterTest {

    @Test
    fun testLogEntryFormatting() {
        val entry = LogEntry(
            timestamp = 1772985600000L,
            module = LogModule.CameraX,
            level = LogLevel.ERROR,
            message = "Camera binding failed",
            errorCode = "#204",
            stackTrace = "java.lang.RuntimeException: Camera failed\n\tat test.Test(Test.kt:10)"
        )

        val formatted = entry.format()

        assertTrue("Must contain module", formatted.contains("[MÓDULO] CameraX [#204]"))
        assertTrue("Must contain level", formatted.contains("[NÍVEL] ERROR"))
        assertTrue("Must contain message", formatted.contains("[MENSAGEM] Camera binding failed"))
        assertTrue("Must contain stack trace header", formatted.contains("[STACK TRACE]"))
        assertTrue("Must contain stack trace body", formatted.contains("java.lang.RuntimeException"))
    }

    @Test
    fun testLogBufferRetention() {
        DebugCenter.clearRecentLogs()

        for (i in 1..260) {
            DebugCenter.log(
                module = LogModule.OpenCV,
                level = LogLevel.INFO,
                message = "Frame $i processed"
            )
        }

        val logs = DebugCenter.getRecentLogs()
        // Max buffer capacity is 250
        assertTrue("Buffer must not exceed 250 items", logs.size <= 250)
        assertEquals("Most recent log must be on top", "Frame 260 processed", logs.first().message)
    }
}
