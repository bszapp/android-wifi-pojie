package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Looper
import android.os.WorkSource
import android.util.Log
import java.lang.reflect.Constructor
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

        val startPlan = findStartScanMethod(
            scannerClass = scannerClass,
            settingsClass = settings.javaClass,
            listenerClass = listenerClass,
            settings = settings,
            callbackExecutor = callbackExecutor,
            listener = listener,
        )

        Log.d(TAG, "使用 WifiScanner.startScan：${startPlan.method.runtimeSignature()}")
        invokeScannerMethod(
            method = startPlan.method,
            receiver = scanner,
            args = startPlan.arguments,
        )

        return ScanRequest(scanner = scanner, listener = listener)
    }

    fun stopScan(request: ScanRequest) {
        val listenerClass = Class.forName(SCAN_LISTENER_CLASS)
        val methods = visibleMethods(request.scanner.javaClass)
        val namedMethods = methods.filter { it.name == "stopScan" }
        if (namedMethods.isEmpty()) {
            throw NoSuchMethodException(
                "WifiScanner 中不存在名为 stopScan 的可见方法；" +
                    "可见方法名=${methods.map { it.name }.distinct().sorted()}",
            )
        }

        val plan = namedMethods
            .mapNotNull { method ->
                buildStopScanArguments(method.parameterTypes, listenerClass, request.listener)
                    ?.let { arguments -> MethodPlan(method, arguments) }
            }
            .minByOrNull { it.method.parameterCount }
            ?: throw NoSuchMethodException(
                "WifiScanner 存在 stopScan 方法，但参数无法适配；" +
                    "运行时签名=${namedMethods.joinToString { it.runtimeSignature() }}",
            )

        invokeScannerMethod(plan.method, request.scanner, plan.arguments)
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

        val constructors = scannerClass.declaredConstructors.toList()
        if (constructors.isEmpty()) {
            throw NoSuchMethodException("WifiScanner 中没有可见构造函数")
        }

        val plan = constructors
            .mapNotNull { constructor ->
                buildConstructorArguments(
                    parameterTypes = constructor.parameterTypes,
                    context = context,
                    scannerServiceClass = scannerServiceClass,
                    scannerService = scannerService,
                    looper = looper,
                )?.let { arguments -> ConstructorPlan(constructor, arguments) }
            }
            .maxByOrNull { constructorPriority(it.constructor.parameterTypes) }
            ?: throw NoSuchMethodException(
                "WifiScanner 存在构造函数，但参数无法适配；" +
                    "运行时签名=${constructors.joinToString { it.runtimeSignature() }}",
            )

        Log.d(TAG, "使用 WifiScanner 构造函数：${plan.constructor.runtimeSignature()}")
        return invokeConstructor(plan.constructor) {
            plan.constructor.newInstance(*plan.arguments)
        }
    }

    private fun buildConstructorArguments(
        parameterTypes: Array<Class<*>>,
        context: Context,
        scannerServiceClass: Class<*>,
        scannerService: Any,
        looper: Looper,
    ): Array<Any?>? {
        var contextCount = 0
        var serviceCount = 0
        var stringCount = 0

        val arguments = parameterTypes.map { type ->
            when {
                Context::class.java.isAssignableFrom(type) -> {
                    contextCount++
                    context
                }
                type == scannerServiceClass -> {
                    serviceCount++
                    scannerService
                }
                type == Looper::class.java -> looper
                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> true
                Executor::class.java.isAssignableFrom(type) &&
                    type.isInstance(DIRECT_EXECUTOR) -> DIRECT_EXECUTOR
                type == String::class.java -> {
                    stringCount++
                    if (stringCount == 1) callerPackage else null
                }
                else -> return null
            }
        }.toTypedArray()

        return arguments.takeIf { contextCount == 1 && serviceCount == 1 }
    }

    private fun constructorPriority(parameterTypes: Array<Class<*>>): Int {
        val hasLooper = parameterTypes.any { it == Looper::class.java }
        val hasBoolean = parameterTypes.any {
            it == java.lang.Boolean.TYPE || it == java.lang.Boolean::class.java
        }
        return when {
            hasLooper && !hasBoolean -> 300 - parameterTypes.size
            !hasLooper && !hasBoolean -> 200 - parameterTypes.size
            else -> 100 - parameterTypes.size
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
        settings: Any,
        callbackExecutor: Executor,
        listener: Any,
    ): MethodPlan {
        val methods = visibleMethods(scannerClass)
        val namedMethods = methods.filter { it.name == "startScan" }
        if (namedMethods.isEmpty()) {
            throw NoSuchMethodException(
                "WifiScanner 中不存在名为 startScan 的可见方法；" +
                    "可见方法名=${methods.map { it.name }.distinct().sorted()}",
            )
        }

        return namedMethods
            .mapNotNull { method ->
                buildStartScanArguments(
                    parameterTypes = method.parameterTypes,
                    settingsClass = settingsClass,
                    settings = settings,
                    listenerClass = listenerClass,
                    listener = listener,
                    callbackExecutor = callbackExecutor,
                )?.let { arguments -> MethodPlan(method, arguments) }
            }
            .maxByOrNull { startScanPriority(it.method.parameterTypes) }
            ?: throw NoSuchMethodException(
                "WifiScanner 存在 startScan 方法，但参数无法适配；" +
                    "运行时签名=${namedMethods.joinToString { it.runtimeSignature() }}",
            )
    }

    private fun buildStartScanArguments(
        parameterTypes: Array<Class<*>>,
        settingsClass: Class<*>,
        settings: Any,
        listenerClass: Class<*>,
        listener: Any,
        callbackExecutor: Executor,
    ): Array<Any?>? {
        var settingsCount = 0
        var listenerCount = 0
        var stringCount = 0

        val arguments = parameterTypes.map { type ->
            when {
                type == settingsClass -> {
                    settingsCount++
                    settings
                }
                type == listenerClass -> {
                    listenerCount++
                    listener
                }
                Executor::class.java.isAssignableFrom(type) &&
                    type.isInstance(callbackExecutor) -> callbackExecutor
                type == WorkSource::class.java -> null
                type == String::class.java -> {
                    stringCount++
                    if (stringCount == 1) callerPackage else null
                }
                else -> return null
            }
        }.toTypedArray()

        return arguments.takeIf { settingsCount == 1 && listenerCount == 1 }
    }

    private fun buildStopScanArguments(
        parameterTypes: Array<Class<*>>,
        listenerClass: Class<*>,
        listener: Any,
    ): Array<Any?>? {
        var listenerCount = 0
        var stringCount = 0

        val arguments = parameterTypes.map { type ->
            when {
                type == listenerClass -> {
                    listenerCount++
                    listener
                }
                type == String::class.java -> {
                    stringCount++
                    if (stringCount == 1) callerPackage else null
                }
                type == WorkSource::class.java -> null
                else -> return null
            }
        }.toTypedArray()

        return arguments.takeIf { listenerCount == 1 }
    }

    private fun startScanPriority(parameterTypes: Array<Class<*>>): Int {
        val hasExecutor = parameterTypes.any { Executor::class.java.isAssignableFrom(it) }
        return (if (hasExecutor) 100 else 0) - parameterTypes.size
    }

    private fun visibleMethods(type: Class<*>): List<Method> =
        (type.methods.asList() + type.declaredMethods.asList())
            .distinctBy { it.runtimeSignature() }

    private fun Method.runtimeSignature(): String =
        "$name(${parameterTypes.joinToString { it.name }})"

    private fun Constructor<*>.runtimeSignature(): String =
        "${declaringClass.name}(${parameterTypes.joinToString { it.name }})"

    private data class MethodPlan(
        val method: Method,
        val arguments: Array<Any?>,
    )

    private data class ConstructorPlan(
        val constructor: Constructor<*>,
        val arguments: Array<Any?>,
    )

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
        constructor: Constructor<*>,
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
        const val TAG = "WifiScannerClient"
        const val WIFI_SCANNING_SERVICE = "wifiscanner"
        const val WIFI_SCANNER_CLASS = "android.net.wifi.WifiScanner"
        const val SCAN_SETTINGS_CLASS = "android.net.wifi.WifiScanner\$ScanSettings"
        const val SCAN_LISTENER_CLASS = "android.net.wifi.WifiScanner\$ScanListener"
        const val I_WIFI_SCANNER_CLASS = "android.net.wifi.IWifiScanner"
        const val I_WIFI_SCANNER_STUB_CLASS = "android.net.wifi.IWifiScanner\$Stub"

        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
