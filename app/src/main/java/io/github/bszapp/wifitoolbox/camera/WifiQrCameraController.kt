package io.github.bszapp.wifitoolbox.camera

import android.view.Surface
import io.github.bszapp.wifitoolbox.contract.camera.*
import io.github.bszapp.wifitoolbox.service.ICameraPreviewCallback
import io.github.bszapp.wifitoolbox.service.IMainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App 仅转发摄像头控制和 Surface；不调用 CameraManager，不请求摄像头权限。 */
class WifiQrCameraController(
    private val scope: CoroutineScope,
    private val reportError: (String, String, Throwable, String?) -> Unit,
) : IWifiQrCameraController {
    private val lock = Any()
    private val operations = Mutex()
    private var service: IMainService? = null
    private var session: Session? = null
    private class Session(val id: String, val service: IMainService, val callback: ICameraPreviewCallback)
    private val _state = MutableStateFlow<CameraPreviewState?>(null)
    override val state = _state.asStateFlow()

    fun connect(service: IMainService) {
        disconnect()
        synchronized(lock) { this.service = service }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            service = null
            session.also { session = null; _state.value = null }
        }
        previous?.let { scope.launch(Dispatchers.IO) { runCatching { it.service.stopCameraPreview(it.id) } } }
    }

    override suspend fun configuration(): CameraPreviewConfiguration = withContext(Dispatchers.IO) {
        val connected = synchronized(lock) { service } ?: error("服务未连接")
        try {
            val data = connected.getRearCameraConfiguration()
            check(data != null) { "服务未提供摄像头配置，请重启服务后重试" }
            check((data.size == 3 || data.size == 5) && data[0] > 0 && data[1] > 0) { "摄像头配置无效" }
            val analysisWidth = if (data.size == 5) data[3] else data[0]
            val analysisHeight = if (data.size == 5) data[4] else data[1]
            check(analysisWidth > 0 && analysisHeight > 0) { "摄像头识别配置无效" }
            synchronized(lock) { check(service === connected) { "服务连接已改变" } }
            CameraPreviewConfiguration(data[0], data[1], data[2], analysisWidth, analysisHeight)
        } catch (error: Exception) {
            reportError("App.WifiQrCamera", "读取后置摄像头配置", error, null)
            throw error
        }
    }

    override suspend fun start(sessionId: String, preview: Surface, analysis: Surface) = withContext(Dispatchers.IO) {
        operations.withLock {
            val connected = synchronized(lock) { service } ?: error("服务未连接")
            lateinit var binding: Session
            val callback = object : ICameraPreviewCallback.Stub() {
                override fun onRunning(id: String) {
                    synchronized(lock) {
                        if (session === binding && id == sessionId) _state.value = CameraPreviewState(id, true)
                    }
                }
                override fun onError(id: String, message: String) {
                    val current = synchronized(lock) {
                        (session === binding && id == sessionId).also {
                            if (it) _state.value = CameraPreviewState(id, false, message)
                        }
                    }
                    if (current) reportError("App.WifiQrCamera", "扫码摄像头", IllegalStateException(message), null)
                }
            }
            binding = Session(sessionId, connected, callback)
            val previous = synchronized(lock) {
                check(service === connected)
                session.also { session = binding; _state.value = CameraPreviewState(sessionId, false) }
            }
            previous?.let { runCatching { it.service.stopCameraPreview(it.id) } }
            try {
                connected.startCameraPreview(sessionId, preview, analysis, callback)
                if (synchronized(lock) { session !== binding }) connected.stopCameraPreview(sessionId)
            } catch (error: Exception) {
                val current = synchronized(lock) {
                    (session === binding).also {
                        if (it) { session = null; _state.value = CameraPreviewState(sessionId, false, error.message) }
                    }
                }
                if (current) reportError("App.WifiQrCamera", "启动扫码摄像头", error, null)
                throw error
            }
        }
    }

    override fun stop(sessionId: String) {
        val previous = synchronized(lock) {
            session?.takeIf { it.id == sessionId }?.also { session = null; _state.value = null }
        } ?: return
        scope.launch(Dispatchers.IO) {
            operations.withLock {
                runCatching { previous.service.stopCameraPreview(previous.id) }
            }
        }
    }
}
