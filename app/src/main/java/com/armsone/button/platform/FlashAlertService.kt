package com.armsone.button.platform

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Best-effort foreground torch pulses. Android may reject this while camera/torch is busy. */
class FlashAlertService(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cameraId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }
    private var generation = 0

    fun flash(times: Int = 3) {
        stop()
        val id = cameraId ?: return
        val run = ++generation
        repeat(times.coerceAtLeast(0)) { index ->
            handler.postDelayed({ setTorchIfCurrent(id, true, run) }, index * 440L)
            handler.postDelayed({ setTorchIfCurrent(id, false, run) }, index * 440L + 220L)
        }
    }

    fun stop() {
        generation += 1
        cameraId?.let { id -> runCatching { cameraManager.setTorchMode(id, false) } }
    }

    private fun setTorchIfCurrent(id: String, enabled: Boolean, run: Int) {
        if (generation != run) return
        runCatching { cameraManager.setTorchMode(id, enabled) }
    }
}
