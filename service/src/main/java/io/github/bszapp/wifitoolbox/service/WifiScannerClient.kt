package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Looper
import android.os.WorkSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * 通过隐藏的 [android.net.wifi.WifiScanner] 发起单次扫描。
 *
 * 这里使用反射是因为 WifiScanner 属于 SystemApi/隐藏 API。回调对象本身实现运行时的
 * WifiScanner.ScanListener，因此扫描请求是否被接受、失败以及结果到达都由同一请求监听器确认。
 */
@SuppressLint("PrivateApi")
internal class WifiScannerClient(
    private val callerPackage: String,
) {
    interface ScanListener {
        fun onSuccess()
        fun onFailure(reason: Int, description: String)
        fun onResults()
    }

    class ScanRequest internal constructor(
        internal val scanner: Any,
        internal val listener: Any,
    )

    fun startScan(
        callbackExecutor: Executor,
        callback: ScanListener,
    ): ScanRequest {
        val scannerClass = Class.forName(WIFI_SCANNER_CLASS)
        val scannerServiceClass = Class.forName(I_WIFI_SCANNER_CLASS)
        val scannerService = resolveScannerService(scannerServiceClass)
        val scanner = createScanner(scannerClass, scannerServiceClass, scannerService)
        val settings = createScanSettings(scannerClass)
        val listenerClass = Class.forName(SCAN_LISTENER_CLASS)
        val listener = createScanListener(listenerClass, callback)

        val startMethod = findStartScanMethod(
            scannerClass = scannerClass,
            settingsClass = settings.javaClass,
            listenerClass = listenerClass,
        )

        invokeScannerMethod(
            method = startMethod,
            receiver = scanner,
            args = when (startMethod.parameterCount) {
                4 -> arrayOf(settings, callbackExecutor, listener, null)
                3 -> arrayOf(settings, listener, null)
                2 -> arrayOf(settings, listener)
                else -> error("不支持的 WifiScanner.startScan 参数数量")
            },
        )

        return ScanRequest(scanner = scanner, listener = listener)
    }

    fun stopScan(request: ScanRequest) {
        val listenerClass = Class.forName(SCAN_LISTENER_CLASS)
        val method = request.scanner.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "stopScan" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == listenerClass
        } ?: throw NoSuchMethodException("找不到 WifiScanner.stopScan(ScanListener)")

        invokeScannerMethod(method, request.scanner, arrayOf(request.listener))
    }

    private fun resolveScannerService(scannerServiceClass: Class<*>): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, WIFI_SCANNING_SERVICE) as? IBinder
            ?: throw IllegalStateException("WifiScanner 系统服务不存在")

        return Class.forName(I_WIFI_SCANNER_STUB_CLASS)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?.takeIf(scannerServiceClass::isInstance)
            ?: throw IllegalStateException("IWifiScanner.Stub.asInterface 返回无效对象")
    }

    private fun createScanner(
        scannerClass: Class<*>,
        scannerServiceClass: Class<*>,
        scannerService: Any,
    ): Any {
        val context = ScannerContext(callerPackage)
        val looper = Looper.getMainLooper() ?: Looper.myLooper()
            ?: throw IllegalStateException("当前进程没有可用 Looper")

        val constructor = scannerClass.constructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 3 &&
                Context::class.java.isAssignableFrom(types[0]) &&
                types[1] == scannerServiceClass &&
                types[2] == Looper::class.java
        } ?: throw NoSuchMethodException(
            "找不到 WifiScanner(Context, IWifiScanner, Looper)",
        )

        return invokeConstructor(constructor) {
            constructor.newInstance(context, scannerService, looper)
        }
    }

    private fun createScanSettings(scannerClass: Class<*>): Any {
        val settingsClass = Class.forName(SCAN_SETTINGS_CLASS)
        val settings = settingsClass.getDeclaredConstructor().newInstance()

        setIntField(
            receiver = settings,
            name = "band",
            value = scannerClass.getField("WIFI_BAND_ALL").getInt(null),
        )
        setIntField(
            receiver = settings,
            name = "reportEvents",
            value = scannerClass.getField("REPORT_EVENT_AFTER_EACH_SCAN").getInt(null),
        )
        runCatching {
            setIntField(
                receiver = settings,
                name = "type",
                value = scannerClass.getField("SCAN_TYPE_LOW_LATENCY").getInt(null),
            )
        }

        return settings
    }

    private fun createScanListener(
        listenerClass: Class<*>,
        callback: ScanListener,
    ): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onSuccess" -> callback.onSuccess()
                "onFailure" -> callback.onFailure(
                    reason = (args?.getOrNull(0) as? Number)?.toInt() ?: 0,
                    description = args?.getOrNull(1) as? String ?: "",
                )
                "onResults" -> callback.onResults()
                "onPeriodChanged", "onFullResult" -> Unit
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "WifiToolboxWifiScannerListener@${
                    Integer.toHexString(System.identityHashCode(proxy))
                }"
                else -> defaultValue(method.returnType)
            }
        }

        return Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
            handler,
        )
    }

    private fun findStartScanMethod(
        scannerClass: Class<*>,
        settingsClass: Class<*>,
        listenerClass: Class<*>,
    ): Method {
        val candidates = scannerClass.methods.filter { method ->
            method.name == "startScan" &&
                method.parameterTypes.firstOrNull() == settingsClass
        }

        return candidates.firstOrNull { method ->
            val types = method.parameterTypes
            types.size == 4 &&
                Executor::class.java.isAssignableFrom(types[1]) &&
                types[2] == listenerClass &&
                types[3] == WorkSource::class.java
        } ?: candidates.firstOrNull { method ->
            val types = method.parameterTypes
            types.size == 3 &&
                types[1] == listenerClass &&
                types[2] == WorkSource::class.java
        } ?: candidates.firstOrNull { method ->
            val types = method.parameterTypes
            types.size == 2 && types[1] == listenerClass
        } ?: throw NoSuchMethodException("找不到可用的 WifiScanner.startScan")
    }

    private fun setIntField(receiver: Any, name: String, value: Int) {
        receiver.javaClass.getField(name).setInt(receiver, value)
    }

    private fun invokeScannerMethod(method: Method, receiver: Any, args: Array<Any?>): Any? {
        method.isAccessible = true
        return try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            throw (error.targetException ?: error)
        }
    }

    private inline fun invokeConstructor(
        constructor: java.lang.reflect.Constructor<*>,
        block: () -> Any,
    ): Any {
        constructor.isAccessible = true
        return try {
            block()
        } catch (error: InvocationTargetException) {
            throw (error.targetException ?: error)
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    /** WifiScanner 在现代 Android 上只需要包名、attributionTag 和 Looper。 */
    private class ScannerContext(
        private val operationPackage: String,
    ) : ContextWrapper(null) {
        override fun getPackageName(): String = operationPackage
        override fun getOpPackageName(): String = operationPackage
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext(): Context = this
        override fun getMainLooper(): Looper =
            Looper.getMainLooper() ?: Looper.myLooper()
            ?: throw IllegalStateException("当前进程没有可用 Looper")
    }

    private companion object {
        const val WIFI_SCANNING_SERVICE = "wifiscanner"
        const val WIFI_SCANNER_CLASS = "android.net.wifi.WifiScanner"
        const val SCAN_SETTINGS_CLASS = "android.net.wifi.WifiScanner\$ScanSettings"
        const val SCAN_LISTENER_CLASS = "android.net.wifi.WifiScanner\$ScanListener"
        const val I_WIFI_SCANNER_CLASS = "android.net.wifi.IWifiScanner"
        const val I_WIFI_SCANNER_STUB_CLASS = "android.net.wifi.IWifiScanner\$Stub"
    }
}
