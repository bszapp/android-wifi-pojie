package io.github.bszapp.wifitoolbox.service.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import io.github.bszapp.wifitoolbox.service.ICameraPreviewCallback
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 所有 Camera2 调用在服务专用线程上执行，保持服务 UID，客户端死亡时释放摄像头。 */
@SuppressLint("MissingPermission", "PrivateApi")
@Suppress("DEPRECATION")
internal class WifiQrCameraManager : AutoCloseable {
    private val thread = HandlerThread("service-qr-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var cameraManager: CameraManager? = null
    private var active: Session? = null
    @Volatile private var closed = false

    private data class Profile(
        val id: String,
        val previewSize: Size,
        val analysisSize: Size,
        val orientation: Int,
        val characteristics: CameraCharacteristics,
    )
    private class Session(val id: String, val preview: Surface, val analysis: Surface, val callback: ICameraPreviewCallback) {
        var device: CameraDevice? = null
        var capture: CameraCaptureSession? = null
        var running = false
        lateinit var death: IBinder.DeathRecipient
    }

    fun configuration(): IntArray = onWorker {
        val profile = profile()
        intArrayOf(profile.previewSize.width, profile.previewSize.height, profile.orientation,
            profile.analysisSize.width, profile.analysisSize.height)
    }

    fun start(id: String, preview: Surface, analysis: Surface, callback: ICameraPreviewCallback) {
        require(id.isNotBlank())
        check(!closed) { "摄像头管理器已关闭" }
        handler.post {
            if (closed) { preview.release(); analysis.release(); return@post }
            active?.let(::release)
            val session = Session(id, preview, analysis, callback)
            active = session
            session.death = IBinder.DeathRecipient { handler.post { if (active === session) release(session) } }
            try {
                callback.asBinder().linkToDeath(session.death, 0)
                check(preview.isValid && analysis.isValid) { "摄像头接收 Surface 已失效" }
                val profile = profile()
                manager().openCamera(profile.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (active !== session) { camera.close(); return }
                        session.device = camera
                        try {
                            camera.createCaptureSession(listOf(preview, analysis), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(capture: CameraCaptureSession) {
                                    if (active !== session) { capture.close(); return }
                                    session.capture = capture
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(preview); addTarget(analysis)
                                            val ranges = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                                            ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }?.let {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                            }
                                            if (profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                                    ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            }
                                        }.build()
                                        capture.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                if (active === session && !session.running) {
                                                    session.running = true
                                                    runCatching { callback.onRunning(id) }.onFailure { release(session) }
                                                    Log.d(TAG, "后置摄像头开始输出 uid=${Process.myUid()} preview=${profile.previewSize} analysis=${profile.analysisSize}")
                                                }
                                            }
                                        }, handler)
                                    } catch (error: Exception) { fail(session, error) }
                                }
                                override fun onConfigureFailed(capture: CameraCaptureSession) {
                                    capture.close()
                                    fail(session, IllegalStateException("后置摄像头输出配置失败"))
                                }
                            }, handler)
                        } catch (error: Exception) { fail(session, error) }
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close(); fail(session, IllegalStateException("后置摄像头已断开"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close(); fail(session, IllegalStateException("后置摄像头错误 $error（服务 UID ${Process.myUid()}）"))
                    }
                }, handler)
            } catch (error: Exception) { fail(session, error) }
        }
    }

    fun stop(id: String) { handler.post { active?.takeIf { it.id == id }?.let(::release) } }

    private fun fail(session: Session, error: Throwable) {
        if (active !== session) return
        Log.e(TAG, "扫码摄像头", error)
        runCatching { session.callback.onError(session.id, error.message ?: error.javaClass.name) }
        release(session)
    }

    private fun release(session: Session) {
        if (active === session) active = null
        runCatching { session.callback.asBinder().unlinkToDeath(session.death, 0) }
        runCatching { session.capture?.close() }
        runCatching { session.device?.close() }
        session.preview.release(); session.analysis.release()
    }

    private fun manager(): CameraManager = cameraManager ?: run {
        // app_process 没有 Application；只在服务 Looper 上取得系统 Context，不借用 App 身份。
        val activityThread = Class.forName("android.app.ActivityThread")
        val instance = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(instance) as Context
        val pkg = if (Process.myUid() == 2000) "com.android.shell" else "android"
        system.createPackageContext(pkg, 0).getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }.also { cameraManager = it }

    private fun profile(): Profile {
        val manager = manager()
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("设备没有后置摄像头")
        val characteristics = manager.getCameraCharacteristics(id)
        val map = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        // 两个 Surface 分别选择尺寸，避免扫码识别的处理量限制全屏预览清晰度。
        val previewSize = selectSize(map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList(), 1920, 1080)
        val analysisSize = selectSize(map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList(), 1280, 720)
        return Profile(id, previewSize, analysisSize,
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, characteristics)
    }

    private fun selectSize(sizes: List<Size>, maxLongSide: Int, maxShortSide: Int): Size =
        sizes.filter { maxOf(it.width, it.height) <= maxLongSide && minOf(it.width, it.height) <= maxShortSide }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: error("后置摄像头没有可用的输出尺寸")

    private fun <T> onWorker(block: () -> T): T {
        check(!closed) { "摄像头管理器已关闭" }
        val task = FutureTask(block)
        handler.post(task)
        return task.get(5, TimeUnit.SECONDS)
    }

    override fun close() {
        closed = true
        handler.post { active?.let(::release); thread.quitSafely() }
    }
    companion object { private const val TAG = "WifiQrCamera" }
}
