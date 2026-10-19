package io.github.bszapp.wifitoolbox.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import io.github.bszapp.wifitoolbox.contract.camera.CameraPreviewConfiguration
import io.github.bszapp.wifitoolbox.contract.camera.IWifiQrCameraController
import io.github.bszapp.wifitoolbox.ui.model.WifiQrNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
fun WifiQrScannerScreen(
    controller: IWifiQrCameraController,
    onNetwork: (WifiQrNetwork) -> Unit,
) {
    var configuration by remember(controller) { mutableStateOf<CameraPreviewConfiguration?>(null) }
    var error by remember(controller) { mutableStateOf<String?>(null) }
    val cameraState by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val window = view.context.qrActivity()?.window
    DisposableEffect(window, lifecycle) {
        val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val previouslyVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(statusBars) ?: true
        val previousBehavior = bars?.systemBarsBehavior
        fun hideStatusBar() {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(statusBars)
        }
        hideStatusBar()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hideStatusBar()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            previousBehavior?.let { bars?.systemBarsBehavior = it }
            if (previouslyVisible) bars?.show(statusBars) else bars?.hide(statusBars)
        }
    }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) {
        try { configuration = controller.configuration() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法打开后置摄像头" }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val profile = configuration
        if (resumed && profile != null && error == null) {
            QrPreviewSurface(profile, controller, onNetwork, { error = it })
        }
        val message = error ?: cameraState?.error ?: when {
            !resumed -> "摄像头已暂停"
            cameraState?.running != true -> "正在启动摄像头…"
            else -> "将 Wi-Fi 二维码放入画面中"
        }
        Text(
            text = message,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(24.dp).background(MiuixTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private tailrec fun Context.qrActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.qrActivity() else null
    else -> null
}

@Composable
private fun QrPreviewSurface(
    profile: CameraPreviewConfiguration,
    controller: IWifiQrCameraController,
    onNetwork: (WifiQrNetwork) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val networkCallback = rememberUpdatedState(onNetwork)
    val errorCallback = rememberUpdatedState(onError)
    val preview = remember(profile, controller) {
        QrPreviewSession(profile, controller, scope, { networkCallback.value(it) }, { errorCallback.value(it) })
    }
    DisposableEffect(preview) { onDispose { preview.close() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> TextureView(context).apply { surfaceTextureListener = preview } },
        update = { preview.transform(it) },
    )
}

/** UI 只持有接收端 Surface 和二维码解码器；摄像头的打开与控制全部交给服务。 */
private class QrPreviewSession(
    private val profile: CameraPreviewConfiguration,
    private val controller: IWifiQrCameraController,
    private val scope: CoroutineScope,
    private val onNetwork: (WifiQrNetwork) -> Unit,
    private val onError: (String) -> Unit,
) : TextureView.SurfaceTextureListener {
    private val closed = AtomicBoolean(false)
    private val found = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var frameThread: HandlerThread? = null
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null
    private val decoding = AtomicBoolean(false)
    private val metrics = QrPreviewMetrics()
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var sessionId: String? = null
    private var startJob: Job? = null
    private var textureView: TextureView? = null
    private var transformedDisplayRotation: Int? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed.get()) return
        try {
            texture.setDefaultBufferSize(profile.width, profile.height)
            surface = Surface(texture)
            val receiverWorker = HandlerThread("wifi-qr-receive").apply { start() }
            frameThread = receiverWorker
            val decoderWorker = HandlerThread("wifi-qr-decode", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            decodeThread = decoderWorker
            val decoderHandler = Handler(decoderWorker.looper)
            decodeHandler = decoderHandler
            val decoder = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            }
            var lastDecode = 0L
            // 只有一个识别任务可以持有此数组；后续帧在识别忙时直接释放。
            val luminanceBytes = ByteArray(profile.analysisWidth * profile.analysisHeight)
            val receiver = ImageReader.newInstance(profile.analysisWidth, profile.analysisHeight, ImageFormat.YUV_420_888, 3)
            reader = receiver
            receiver.setOnImageAvailableListener({ source ->
                if (closed.get()) return@setOnImageAvailableListener
                var reserved = false
                try {
                    source.acquireLatestImage()?.use { image ->
                        metrics.received()
                        val now = SystemClock.elapsedRealtime()
                        if (closed.get() || found.get() || now - lastDecode < 100L ||
                            !decoding.compareAndSet(false, true)) return@use
                        reserved = true
                        lastDecode = now
                        val copyStarted = SystemClock.elapsedRealtimeNanos()
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val start = buffer.position()
                        for (y in 0 until image.height) {
                            val row = start + y * plane.rowStride
                            val offset = y * image.width
                            if (plane.pixelStride == 1) {
                                buffer.position(row)
                                buffer.get(luminanceBytes, offset, image.width)
                            } else {
                                for (x in 0 until image.width) {
                                    luminanceBytes[offset + x] = buffer.get(row + x * plane.pixelStride)
                                }
                            }
                        }
                        metrics.copied(SystemClock.elapsedRealtimeNanos() - copyStarted)
                    }
                } catch (failure: Exception) {
                    if (reserved) decoding.set(false)
                    if (!closed.get()) {
                        Log.e("WifiQrPreview", "读取扫码画面失败", failure)
                        main.post { if (!closed.get()) onError(failure.message ?: "读取扫码画面失败") }
                    }
                    return@setOnImageAvailableListener
                }
                if (!reserved) return@setOnImageAvailableListener
                // Image 已关闭，识别耗时不会阻止接收线程释放相机输出缓冲区。
                if (closed.get() || !decoderHandler.post {
                    val started = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (closed.get()) return@post
                        val luminance = PlanarYUVLuminanceSource(luminanceBytes, profile.analysisWidth, profile.analysisHeight,
                            0, 0, profile.analysisWidth, profile.analysisHeight, false)
                        val result = decoder.decodeWithState(BinaryBitmap(HybridBinarizer(luminance)))
                        WifiQrNetwork.parse(result)?.let { network ->
                            if (found.compareAndSet(false, true)) main.post {
                                if (!closed.get()) {
                                    close()
                                    onNetwork(network)
                                }
                            }
                        }
                    } catch (_: ReaderException) {
                        // 未找到 Wi-Fi 二维码，继续处理下一帧，不产生错误广播。
                    } finally {
                        decoder.reset()
                        metrics.decoded(SystemClock.elapsedRealtimeNanos() - started)
                        decoding.set(false)
                    }
                }) decoding.set(false)
            }, Handler(receiverWorker.looper))
            val id = UUID.randomUUID().toString()
            sessionId = id
            startJob = scope.launch {
                try { controller.start(id, checkNotNull(surface), receiver.surface) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (!closed.get()) onError(failure.message ?: "摄像头启动失败") }
                finally { if (closed.get()) controller.stop(id) }
            }
            textureView?.let(::transform)
        } catch (failure: Exception) {
            onError(failure.message ?: "摄像头接收端初始化失败")
            close()
        }
    }

    fun transform(view: TextureView) {
        textureView = view
        if (view.width == 0 || view.height == 0) return
        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        transformedDisplayRotation = displayRotation
        val displayAngle = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Camera2 的 TextureView 输出已补偿传感器方向；这里只补偿屏幕旋转。
        // 先得到自动旋转后的缓冲区尺寸，再撤销 TextureView 默认的非等比拉伸。
        val sensorSwapped = profile.sensorOrientation % 180 != 0
        val bufferWidth = if (sensorSwapped) profile.height else profile.width
        val bufferHeight = if (sensorSwapped) profile.width else profile.height
        val rotation = (360 - displayAngle) % 360
        val swapped = rotation == 90 || rotation == 270
        val scale = maxOf(view.width.toFloat() / if (swapped) bufferHeight else bufferWidth,
            view.height.toFloat() / if (swapped) bufferWidth else bufferHeight)
        val cos = when (rotation) { 0 -> 1f; 180 -> -1f; else -> 0f }
        val sin = when (rotation) { 90 -> 1f; 270 -> -1f; else -> 0f }
        val raw = floatArrayOf(0f, 0f, bufferWidth.toFloat(), 0f,
            bufferWidth.toFloat(), bufferHeight.toFloat(), 0f, bufferHeight.toFloat())
        val target = FloatArray(8)
        for (i in 0..3) {
            val x = raw[i * 2] - bufferWidth / 2f
            val y = raw[i * 2 + 1] - bufferHeight / 2f
            target[i * 2] = view.width / 2f + (x * cos - y * sin) * scale
            target[i * 2 + 1] = view.height / 2f + (x * sin + y * cos) * scale
        }
        // 旋转后等比居中裁剪铺满页面；保持比例，同时避免预览区域出现留白。
        val source = floatArrayOf(0f, 0f, view.width.toFloat(), 0f,
            view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
        view.setTransform(Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) })
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        textureView?.let(::transform)
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        metrics.previewed()
        textureView?.let { view ->
            if ((view.display?.rotation ?: Surface.ROTATION_0) != transformedDisplayRotation) transform(view)
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { close(); return true }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        startJob?.cancel()
        sessionId?.let(controller::stop)
        reader?.setOnImageAvailableListener(null, null)
        decodeHandler?.removeCallbacksAndMessages(null)
        decodeThread?.quitSafely()
        // 与图像接收回调同线程关闭，避免正在复制亮度平面时使缓冲区失效。
        val worker = frameThread
        if (worker != null) Handler(worker.looper).post { reader?.close(); worker.quitSafely() }
        else reader?.close()
        surface?.release()
        textureView = null
    }
}

/** 每五秒汇总一次，只记录时序与数量，不保存摄像头图像。 */
private class QrPreviewMetrics {
    private val received = AtomicLong()
    private val receiveMaxGap = AtomicLong()
    private val copyMaxTime = AtomicLong()
    private val decodeCount = AtomicLong()
    private val decodeMaxTime = AtomicLong()
    private var previousReceive = 0L // 仅图像接收线程访问。
    private var previousPreview = 0L // 以下字段仅主线程访问。
    private var windowStart = 0L
    private var previewCount = 0L
    private var previewMaxGap = 0L
    private var previewStalls = 0L

    fun received() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (previousReceive != 0L) receiveMaxGap.updateAndGet { maxOf(it, now - previousReceive) }
        previousReceive = now
        received.incrementAndGet()
    }

    fun copied(duration: Long) { copyMaxTime.updateAndGet { maxOf(it, duration) } }

    fun decoded(duration: Long) {
        decodeCount.incrementAndGet()
        decodeMaxTime.updateAndGet { maxOf(it, duration) }
    }

    fun previewed() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (windowStart == 0L) windowStart = now
        if (previousPreview != 0L) {
            val gap = now - previousPreview
            previewMaxGap = maxOf(previewMaxGap, gap)
            if (gap > 100_000_000L) previewStalls++
        }
        previousPreview = now
        previewCount++
        val elapsed = now - windowStart
        if (elapsed < 5_000_000_000L) return
        val seconds = elapsed / 1e9
        Log.d("WifiQrPreview", "窗口=${elapsed / 1_000_000}ms 预览=${previewCount / seconds}fps " +
            "接收=${received.getAndSet(0) / seconds}fps 预览最大间隔=${previewMaxGap / 1_000_000}ms " +
            "接收最大间隔=${receiveMaxGap.getAndSet(0) / 1_000_000}ms 超100ms=${previewStalls} " +
            "复制最大=${copyMaxTime.getAndSet(0) / 1_000_000}ms " +
            "识别次数=${decodeCount.getAndSet(0)} 识别最大=${decodeMaxTime.getAndSet(0) / 1_000_000}ms")
        windowStart = now
        previewCount = 0
        previewMaxGap = 0
        previewStalls = 0
    }
}
