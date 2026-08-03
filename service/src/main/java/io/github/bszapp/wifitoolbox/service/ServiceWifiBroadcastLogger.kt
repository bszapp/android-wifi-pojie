package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 独立 service 的 Wi-Fi 事件入口。
 *
 * 不再尝试 ActivityManager 广播：
 * - 扫描结果完全由每次 WifiScanner.ScanListener.onResults 处理；
 * - Wi-Fi 开关变化只使用 IWifiNetworkStateChangedListener 作为唤醒信号；
 * - 收到回调后通过 getWifiEnabledState 读取真实开关状态；
 * - listener 注册失败后不再接收 Wi-Fi 开关变化。
 */
@SuppressLint("PrivateApi")
internal class ServiceWifiBroadcastLogger(
    private val onWifiStateChanged: () -> Unit,
    private val onWifiNetworkStateChanged: (clientRole: Int, status: Int) -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {
    @Volatile
    private var started = false

    private var wifiService: Any? = null
    private var networkStateCallbackInterface: Any? = null
    private var networkStateCallbackBinder: RawCallbackBinder? = null

    private var lastWifiState: Int? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true

        runCatching(::startWifiNetworkStateChangedCallback).onFailure {
            reportError("注册 Wi-Fi 网络状态回调", it)
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false

        runCatching(::stopWifiNetworkStateChangedCallback).onFailure {
            reportError("注销 Wi-Fi 网络状态回调", it)
        }
        lastWifiState = null
        wifiService = null
    }

    private fun startWifiNetworkStateChangedCallback() {
        Class.forName(I_WIFI_NETWORK_STATE_CHANGED_LISTENER_STUB)
        val wifi = resolveWifiService()
        val code = resolveTransactionCode(
            I_WIFI_NETWORK_STATE_CHANGED_LISTENER_STUB,
            "TRANSACTION_onWifiNetworkStateChanged",
            IBinder.FIRST_CALL_TRANSACTION,
        )
        val binder = RawCallbackBinder(
            descriptor = I_WIFI_NETWORK_STATE_CHANGED_LISTENER,
            onError = { reportError("处理 Wi-Fi 网络状态 Binder 回调", it) },
            handlers = mapOf(code to { data ->
                val clientRole = data.readInt()
                val status = data.readInt()
                Log.d(
                    TAG,
                    "[$PATH_NETWORK_STATE_CALLBACK] onWifiNetworkStateChanged(" +
                        "clientRole=$clientRole, status=$status)",
                )
                onWifiNetworkStateChanged(clientRole, status)
                readWifiStateChange()?.let { change ->
                    Log.d(TAG, "[$PATH_NETWORK_STATE_CALLBACK] WIFI_STATE ${change.previous} -> ${change.current}")
                    onWifiStateChanged()
                }
            }),
        )
        val callback = createAidlInterface(I_WIFI_NETWORK_STATE_CHANGED_LISTENER_STUB, binder)
        val method = findSingleCallbackMethod(
            wifi,
            names = setOf(
                "addWifiNetworkStateChangedListener",
                "registerWifiNetworkStateChangedListener",
            ),
            callbackClassName = I_WIFI_NETWORK_STATE_CHANGED_LISTENER,
        )
        invokeSystem(method, wifi, arrayOf(callback))

        networkStateCallbackBinder = binder
        networkStateCallbackInterface = callback
        Log.d(TAG, "[$PATH_NETWORK_STATE_CALLBACK] 注册成功：${method.toGenericString()}")
    }

    private fun stopWifiNetworkStateChangedCallback() {
        val wifi = wifiService
        val callback = networkStateCallbackInterface
        networkStateCallbackInterface = null
        networkStateCallbackBinder = null
        if (wifi == null || callback == null) return
        findOptionalSingleCallbackMethod(
            wifi,
            names = setOf(
                "removeWifiNetworkStateChangedListener",
                "unregisterWifiNetworkStateChangedListener",
            ),
            callbackClassName = I_WIFI_NETWORK_STATE_CHANGED_LISTENER,
        )?.let { invokeSystem(it, wifi, arrayOf(callback)) }
    }

    @Synchronized
    private fun readWifiStateChange(): WifiStateChange? {
        if (!started) return null
        val current = queryWifiEnabledState(resolveWifiService())
        val previous = lastWifiState
        lastWifiState = current
        return if (previous == null || previous != current) {
            WifiStateChange(previous = previous, current = current)
        } else {
            null
        }
    }

    private fun queryWifiEnabledState(wifi: Any): Int {
        val method = wifi.javaClass.methods.firstOrNull {
            it.name == "getWifiEnabledState" && it.parameterCount == 0
        } ?: throw NoSuchMethodException("IWifiManager.getWifiEnabledState() 不存在")
        return (invokeSystem(method, wifi, emptyArray()) as? Number)?.toInt()
            ?: throw IllegalStateException("getWifiEnabledState 返回值不是 Number")
    }

    private fun resolveWifiService(): Any {
        wifiService?.let { return it }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wifi") as? IBinder
            ?: throw IllegalStateException("Wi-Fi 系统服务不存在")
        return Class.forName(I_WIFI_MANAGER_STUB)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?.also { wifiService = it }
            ?: throw IllegalStateException("IWifiManager.Stub.asInterface 返回 null")
    }

    private fun createAidlInterface(stubClassName: String, binder: IBinder): Any =
        Class.forName(stubClassName)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: throw IllegalStateException("$stubClassName.asInterface 返回 null")

    private fun resolveTransactionCode(
        stubClassName: String,
        fieldName: String,
        fallback: Int,
    ): Int = runCatching {
        Class.forName(stubClassName)
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
            .getInt(null)
    }.getOrDefault(fallback)

    private fun findSingleCallbackMethod(
        receiver: Any,
        names: Set<String>,
        callbackClassName: String,
    ): Method = findOptionalSingleCallbackMethod(receiver, names, callbackClassName)
        ?: throw NoSuchMethodException(
            "${receiver.javaClass.name} 不存在 ${names.joinToString()}($callbackClassName)",
        )

    private fun findOptionalSingleCallbackMethod(
        receiver: Any,
        names: Set<String>,
        callbackClassName: String,
    ): Method? = receiver.javaClass.methods.firstOrNull { method ->
        method.name in names &&
            method.parameterCount == 1 &&
            method.parameterTypes[0].name == callbackClassName
    }

    private fun invokeSystem(method: Method, receiver: Any, args: Array<Any?>): Any? {
        method.isAccessible = true
        return try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            throw (error.targetException ?: error)
        }
    }


    private fun reportError(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 失败：${error.message}", error)
        onError(operation, error)
    }

    private data class WifiStateChange(
        val previous: Int?,
        val current: Int,
    )

    private class RawCallbackBinder(
        private val descriptor: String,
        private val onError: (Throwable) -> Unit,
        private val handlers: Map<Int, (Parcel) -> Unit>,
    ) : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(descriptor)
                return true
            }
            val handler = handlers[code] ?: return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface(descriptor)
                val identity = clearCallingIdentity()
                try {
                    handler(data)
                } finally {
                    restoreCallingIdentity(identity)
                }
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Binder callback 解码失败：${error.message}", error)
                onError(error)
                true
            }
        }
    }

    private companion object {
        const val TAG = "ServiceWifiReceiver"

        const val PATH_NETWORK_STATE_CALLBACK = "IWIFI_NETWORK_CALLBACK"

        const val I_WIFI_MANAGER_STUB = "android.net.wifi.IWifiManager\$Stub"
        const val I_WIFI_NETWORK_STATE_CHANGED_LISTENER =
            "android.net.wifi.IWifiNetworkStateChangedListener"
        const val I_WIFI_NETWORK_STATE_CHANGED_LISTENER_STUB =
            "android.net.wifi.IWifiNetworkStateChangedListener\$Stub"
    }
}
