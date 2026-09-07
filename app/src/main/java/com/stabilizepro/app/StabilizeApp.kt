package com.stabilizepro.app

import android.app.Application
import android.util.Log
import com.stabilizepro.app.logs.DebugCenter
import com.stabilizepro.app.logs.LogLevel
import com.stabilizepro.app.logs.LogModule
import org.opencv.android.OpenCVLoader

class StabilizeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DebugCenter.init(this)
        initOpenCv()
    }

    private fun initOpenCv() {
        try {
            if (OpenCVLoader.initDebug()) {
                DebugCenter.isOpenCvInitialized = true
                DebugCenter.log(LogModule.OpenCV, LogLevel.INFO, "OpenCV v4.5.3 inicializado com sucesso.")
            } else {
                DebugCenter.isOpenCvInitialized = false
                DebugCenter.log(LogModule.OpenCV, LogLevel.ERROR, "OpenCV initialization failed via OpenCVLoader.", "#031")
            }
        } catch (e: Throwable) {
            DebugCenter.isOpenCvInitialized = false
            DebugCenter.log(LogModule.OpenCV, LogLevel.ERROR, "Exceção fatal ao inicializar OpenCV: ${e.message}", "#031", e)
        }
    }
}
