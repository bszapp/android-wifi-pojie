package io.github.bszapp.wifitoolbox.contract.camera

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

data class CameraPreviewConfiguration(
    val width: Int,
    val height: Int,
    val sensorOrientation: Int,
    val analysisWidth: Int = width,
    val analysisHeight: Int = height,
)
data class CameraPreviewState(val sessionId: String, val running: Boolean, val error: String? = null)

/** 摄像头由服务持有；App 的 Surface 通过 BufferQueue 接收帧，Binder 只传控制信息。 */
interface IWifiQrCameraController {
    val state: StateFlow<CameraPreviewState?>
    suspend fun configuration(): CameraPreviewConfiguration
    suspend fun start(sessionId: String, preview: Surface, analysis: Surface)
    fun stop(sessionId: String)
}
