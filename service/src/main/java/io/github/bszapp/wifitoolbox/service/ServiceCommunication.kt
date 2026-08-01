package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiParcelTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSourceState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一处理 App 与 service 之间的通信：调用方校验、Provider 投递 Binder、callback 预留通道。
 */
@SuppressLint("PrivateApi")
class ServiceCommunication(
    private val startupInfoProvider: () -> StartupInfo,
    private val serviceBinderProvider: () -> IBinder,
) {
    private val clientStates = ConcurrentHashMap<IBinder, ClientDeliveryState>()
    private val callbacks = object : RemoteCallbackList<IMainServiceCallback>() {
        override fun onCallbackDied(callback: IMainServiceCallback) {
            clientStates.remove(callback.asBinder())
        }
    }
    private val serviceLogCallbacks = RemoteCallbackList<IServiceLogCallback>()
    private val containerTerminalCallbacks = RemoteCallbackList<IContainerTerminalCallback>()
    private val terminalManagerCallbacks = RemoteCallbackList<ITerminalManagerCallback>()
    private val serviceLogRangeLock = Any()
    private var pendingServiceLogRange = ServiceLogRange(1L, 0L, 0L)
    private val serviceLogDeliveryScheduled = AtomicBoolean(false)
    private val terminalLogRangeLock = Any()
    private val pendingTerminalLogRanges = mutableMapOf<Long, TerminalLogRangeSnapshot>()
    private val terminalLogDeliveryScheduled = AtomicBoolean(false)
    private val deliveryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "wifi-ipc-delivery").apply { isDaemon = true }
    }
    private val publisherLock = Any()
    private val sdk = Build.VERSION.SDK_INT

    @Volatile
    private var binderPublisherRunning = false

    fun enforceStartupInitializer(info: StartupInfo) {
        info.requireLaunchInfo()
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (!isLocalCall(callingUid, callingPid) && callingUid != info.trustedUid) {
            throw SecurityException(
                "初始化调用方 uid=$callingUid pid=$callingPid 与启动信息 trustedUid=${info.trustedUid} 不一致"
            )
        }
    }

    fun <T> callFromApp(block: () -> T): T {
        enforceCallerIsApp()
        return block()
    }

    fun connect(): Boolean = callFromApp {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        Log.d(TAG, "connect() uid=$callingUid pid=$callingPid → 允许")
        true
    }

    fun isAlive(): Boolean = callFromApp { true }

    fun registerCallback(callback: IMainServiceCallback) {
        callFromApp {
            clientStates.putIfAbsent(callback.asBinder(), ClientDeliveryState())
            callbacks.register(callback)
            Unit
        }
    }

    fun unregisterCallback(callback: IMainServiceCallback) {
        callFromApp {
            callbacks.unregister(callback)
            clientStates.remove(callback.asBinder())
            Unit
        }
    }

    fun registerServiceLogCallback(callback: IServiceLogCallback) {
        callFromApp {
            serviceLogCallbacks.register(callback)
            Unit
        }
    }

    fun unregisterServiceLogCallback(callback: IServiceLogCallback) {
        callFromApp {
            serviceLogCallbacks.unregister(callback)
            Unit
        }
    }

    fun registerContainerTerminalCallback(callback: IContainerTerminalCallback) {
        callFromApp {
            containerTerminalCallbacks.register(callback)
            Unit
        }
    }

    fun unregisterContainerTerminalCallback(callback: IContainerTerminalCallback) {
        callFromApp {
            containerTerminalCallbacks.unregister(callback)
            Unit
        }
    }

    fun registerTerminalManagerCallback(callback: ITerminalManagerCallback) {
        callFromApp {
            terminalManagerCallbacks.register(callback)
            Unit
        }
    }

    fun unregisterTerminalManagerCallback(callback: ITerminalManagerCallback) {
        callFromApp {
            terminalManagerCallbacks.unregister(callback)
            Unit
        }
    }

    internal fun pushAliveTerminalsChanged(
        callback: ITerminalManagerCallback,
        snapshot: AliveTerminalSnapshot,
    ) {
        runCatching {
            callback.onAliveTerminalIdsChanged(snapshot.generation, snapshot.terminalIds)
        }.onFailure { terminalManagerCallbacks.unregister(callback) }
    }

    internal fun broadcastAliveTerminalsChanged(snapshot: AliveTerminalSnapshot) {
        deliveryExecutor.execute {
            forEachTerminalManagerCallback { callback ->
                pushAliveTerminalsChanged(callback, snapshot)
            }
        }
    }

    internal fun pushTerminalLogRangeChanged(
        callback: ITerminalManagerCallback,
        range: TerminalLogRangeSnapshot,
    ) {
        runCatching {
            callback.onTerminalLogRangeChanged(
                range.terminalId,
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount,
            )
        }.onFailure { terminalManagerCallbacks.unregister(callback) }
    }

    internal fun broadcastTerminalLogRangeChanged(range: TerminalLogRangeSnapshot) {
        synchronized(terminalLogRangeLock) {
            val pending = pendingTerminalLogRanges[range.terminalId]
            if (pending == null || range.generation >= pending.generation) {
                pendingTerminalLogRanges[range.terminalId] = range
            }
        }
        scheduleTerminalLogDelivery()
    }

    private fun scheduleTerminalLogDelivery() {
        if (!terminalLogDeliveryScheduled.compareAndSet(false, true)) return
        deliveryExecutor.execute {
            try {
                while (true) {
                    val ranges = synchronized(terminalLogRangeLock) {
                        if (pendingTerminalLogRanges.isEmpty()) return@synchronized emptyList()
                        pendingTerminalLogRanges.values.toList().also {
                            pendingTerminalLogRanges.clear()
                        }
                    }
                    if (ranges.isEmpty()) break
                    forEachTerminalManagerCallback { callback ->
                        ranges.forEach { range -> pushTerminalLogRangeChanged(callback, range) }
                    }
                }
            } finally {
                terminalLogDeliveryScheduled.set(false)
                val hasPending = synchronized(terminalLogRangeLock) {
                    pendingTerminalLogRanges.isNotEmpty()
                }
                if (hasPending) scheduleTerminalLogDelivery()
            }
        }
    }

    fun pushContainerTerminalEvent(
        callback: IContainerTerminalCallback,
        eventJson: String,
    ) {
        runCatching { callback.onContainerTerminalEvent(eventJson) }
            .onFailure { containerTerminalCallbacks.unregister(callback) }
    }

    fun broadcastContainerTerminalEvent(eventJson: String) {
        val count = containerTerminalCallbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                pushContainerTerminalEvent(
                    callback = containerTerminalCallbacks.getBroadcastItem(index),
                    eventJson = eventJson,
                )
            }
        } finally {
            containerTerminalCallbacks.finishBroadcast()
        }
    }

    fun pushServiceLogRangeChanged(
        callback: IServiceLogCallback,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        runCatching { callback.onServiceLogRangeChanged(oldestAvailableId, latestId) }
            .onFailure { serviceLogCallbacks.unregister(callback) }
    }

    fun broadcastServiceLogRangeChanged(oldestAvailableId: Long, latestId: Long) {
        synchronized(serviceLogRangeLock) {
            pendingServiceLogRange = ServiceLogRange(
                oldestAvailableId = maxOf(
                    pendingServiceLogRange.oldestAvailableId,
                    oldestAvailableId,
                ),
                latestId = maxOf(pendingServiceLogRange.latestId, latestId),
                generation = pendingServiceLogRange.generation + 1L,
            )
        }
        scheduleServiceLogDelivery()
    }

    private fun scheduleServiceLogDelivery() {
        if (!serviceLogDeliveryScheduled.compareAndSet(false, true)) return
        deliveryExecutor.execute {
            var deliveredGeneration = 0L
            try {
                while (true) {
                    val range = synchronized(serviceLogRangeLock) { pendingServiceLogRange }
                    forEachServiceLogCallback { callback ->
                        pushServiceLogRangeChanged(
                            callback = callback,
                            oldestAvailableId = range.oldestAvailableId,
                            latestId = range.latestId,
                        )
                    }
                    deliveredGeneration = range.generation
                    val caughtUp = synchronized(serviceLogRangeLock) {
                        pendingServiceLogRange.generation == range.generation
                    }
                    if (caughtUp) break
                }
            } finally {
                serviceLogDeliveryScheduled.set(false)
                val hasPendingRange = synchronized(serviceLogRangeLock) {
                    pendingServiceLogRange.generation > deliveredGeneration
                }
                if (hasPendingRange) scheduleServiceLogDelivery()
            }
        }
    }

    fun broadcastWifiState(state: WifiState) {
        forEachCallback { callback -> pushWifiState(callback, state) }
    }

    fun broadcastSavedWifiList(value: SavedWifiList) {
        forEachCallback { callback -> pushSavedWifiList(callback, value) }
    }

    fun broadcastWifiInformationSourceState(value: WifiInformationSourceState) {
        forEachCallback { callback -> pushWifiInformationSourceState(callback, value) }
    }

    fun pushWifiInformationSourceState(
        callback: IMainServiceCallback,
        value: WifiInformationSourceState,
    ) {
        runCatching {
            callback.onWifiInformationSourceStateChanged(
                value.source.wireValue,
                value.initializing,
            )
        }.onFailure {
            Log.w(TAG, "推送 Wi-Fi 信息源状态失败：${it.message}", it)
        }
    }

    fun pushWifiState(callback: IMainServiceCallback, state: WifiState) {
        val delivery = clientStates.getOrPut(callback.asBinder()) { ClientDeliveryState() }
        val shouldSend = synchronized(delivery) {
            if (delivery.wifiStateInFlight) {
                delivery.pendingWifiState = state
                false
            } else {
                delivery.wifiStateInFlight = true
                true
            }
        }
        if (shouldSend) deliverWifiState(callback, delivery, state)
    }

    fun pushSavedWifiList(callback: IMainServiceCallback, value: SavedWifiList) {
        val delivery = clientStates.getOrPut(callback.asBinder()) { ClientDeliveryState() }
        val shouldSend = synchronized(delivery) {
            if (delivery.savedWifiListInFlight) {
                delivery.pendingSavedWifiList = value
                false
            } else {
                delivery.savedWifiListInFlight = true
                true
            }
        }
        if (shouldSend) deliverSavedWifiList(callback, delivery, value)
    }

    fun acknowledgeWifiState(callback: IMainServiceCallback) = callFromApp {
        val delivery = clientStates[callback.asBinder()] ?: return@callFromApp
        val next = synchronized(delivery) {
            delivery.wifiStateInFlight = false
            delivery.pendingWifiState.also {
                delivery.pendingWifiState = null
                if (it != null) delivery.wifiStateInFlight = true
            }
        }
        if (next != null) deliverWifiState(callback, delivery, next)
    }

    fun acknowledgeSavedWifiList(callback: IMainServiceCallback) = callFromApp {
        val delivery = clientStates[callback.asBinder()] ?: return@callFromApp
        val next = synchronized(delivery) {
            delivery.savedWifiListInFlight = false
            delivery.pendingSavedWifiList.also {
                delivery.pendingSavedWifiList = null
                if (it != null) delivery.savedWifiListInFlight = true
            }
        }
        if (next != null) deliverSavedWifiList(callback, delivery, next)
    }

    private fun deliverWifiState(
        callback: IMainServiceCallback,
        delivery: ClientDeliveryState,
        state: WifiState,
    ) {
        deliveryExecutor.execute {
            val result = runCatching {
                val payload = WifiParcelTransport.encodeWifiState(state)
                try {
                    callback.onWifiStateChanged(payload)
                } finally {
                    payload.close()
                }
            }
            result.onFailure {
                Log.w(TAG, "推送 WifiState 失败：${it.message}", it)
                failWifiStateDelivery(callback, delivery)
            }
        }
    }

    private fun deliverSavedWifiList(
        callback: IMainServiceCallback,
        delivery: ClientDeliveryState,
        value: SavedWifiList,
    ) {
        deliveryExecutor.execute {
            val result = runCatching {
                val payload = WifiParcelTransport.encodeSavedWifiList(value)
                try {
                    callback.onSavedWifiListChanged(payload)
                } finally {
                    payload.close()
                }
            }
            result.onFailure {
                Log.w(TAG, "推送 SavedWifiList 失败：${it.message}", it)
                failSavedWifiListDelivery(callback, delivery)
            }
        }
    }

    private fun failWifiStateDelivery(
        callback: IMainServiceCallback,
        delivery: ClientDeliveryState,
    ) {
        synchronized(delivery) {
            delivery.wifiStateInFlight = false
            delivery.pendingWifiState = null
        }
        callbacks.unregister(callback)
        clientStates.remove(callback.asBinder())
    }

    private fun failSavedWifiListDelivery(
        callback: IMainServiceCallback,
        delivery: ClientDeliveryState,
    ) {
        synchronized(delivery) {
            delivery.savedWifiListInFlight = false
            delivery.pendingSavedWifiList = null
        }
        callbacks.unregister(callback)
        clientStates.remove(callback.asBinder())
    }

    fun broadcastServiceError(
        source: String,
        operation: String,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.name
        val details = error.stackTraceToString()
        forEachCallback { callback ->
            runCatching {
                callback.onServiceError(
                    source,
                    operation,
                    message,
                    details,
                )
            }.onFailure {
                Log.w(TAG, "推送 Service 错误失败：${it.message}", it)
            }
        }
    }

    private inline fun forEachCallback(block: (IMainServiceCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) block(callbacks.getBroadcastItem(index))
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private inline fun forEachServiceLogCallback(block: (IServiceLogCallback) -> Unit) {
        val count = serviceLogCallbacks.beginBroadcast()
        try {
            for (index in 0 until count) block(serviceLogCallbacks.getBroadcastItem(index))
        } finally {
            serviceLogCallbacks.finishBroadcast()
        }
    }

    private inline fun forEachTerminalManagerCallback(
        block: (ITerminalManagerCallback) -> Unit,
    ) {
        val count = terminalManagerCallbacks.beginBroadcast()
        try {
            for (index in 0 until count) block(terminalManagerCallbacks.getBroadcastItem(index))
        } finally {
            terminalManagerCallbacks.finishBroadcast()
        }
    }

    private data class ServiceLogRange(
        val oldestAvailableId: Long,
        val latestId: Long,
        val generation: Long,
    )

    fun startBinderPublisher() {
        synchronized(publisherLock) {
            if (binderPublisherRunning) return
            binderPublisherRunning = true
        }

        Thread({
            Log.d(TAG, "启动 Binder 投递器")
            try {
                var deliveredInCurrentAppRun = false
                while (true) {
                    if (isTrustedAppProcessRunning()) {
                        if (!deliveredInCurrentAppRun && tryPushBinder()) {
                            Log.d(TAG, "Binder 已投递到应用进程")
                            deliveredInCurrentAppRun = true
                        }
                    } else {
                        deliveredInCurrentAppRun = false
                    }
                    Thread.sleep(500L)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.e(TAG, "Binder 投递器异常，停止投递", e)
            } finally {
                synchronized(publisherLock) {
                    binderPublisherRunning = false
                }
            }
        }, "toolbox-binder-publisher").apply {
            isDaemon = true
            start()
        }
    }

    private fun enforceCallerIsApp() {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (isLocalCall(callingUid, callingPid)) return

        val expectedUid = startupInfoProvider().trustedUid
        if (expectedUid <= 0) {
            throw SecurityException("服务启动信息未初始化，拒绝 uid=$callingUid pid=$callingPid")
        }
        if (callingUid != expectedUid) {
            Log.w(TAG, "uid 不匹配：caller=$callingUid expected=$expectedUid")
            throw SecurityException("调用方 uid=$callingUid pid=$callingPid 未授权（期望 uid=$expectedUid）")
        }
    }

    private fun isLocalCall(callingUid: Int, callingPid: Int): Boolean =
        callingUid == Process.myUid() && callingPid == Process.myPid()

    private fun isTrustedAppProcessRunning(): Boolean {
        val trustedUid = startupInfoProvider().trustedUid
        val am = activityManager()
        val list = systemApi("getRunningAppProcesses") {
            am::class.java.getMethod("getRunningAppProcesses").invoke(am)
        } as? List<*> ?: throw IllegalStateException("getRunningAppProcesses 返回类型不是 List")

        return list.any { item ->
            val info = item as? ActivityManager.RunningAppProcessInfo
                ?: throw IllegalStateException("getRunningAppProcesses 返回了非 RunningAppProcessInfo 项：$item")
            info.uid == trustedUid
        }
    }

    @SuppressLint("NewApi")
    private fun tryPushBinder(): Boolean {
        val authority = PROVIDER_AUTHORITY
        var holder: Any? = null
        var failure: Throwable? = null

        try {
            val am = activityManager()
            val amClass = am::class.java

            holder = systemApi("getContentProviderExternal") {
                if (sdk >= 29) {
                    amClass.getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.java,
                        IBinder::class.java,
                        String::class.java
                    ).invoke(am, authority, 0, null, authority)
                } else {
                    amClass.getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.java,
                        IBinder::class.java
                    ).invoke(am, authority, 0, null)
                }
            } ?: return false

            enforceProviderBelongsToTrustedApp(holder)

            val provider = holder::class.java
                .getField("provider")
                .get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider 为 null")

            val providerBinder = provider::class.java
                .getMethod("asBinder")
                .invoke(provider) as? IBinder
                ?: throw IllegalStateException("provider.asBinder 返回 null")

            if (!providerBinder.pingBinder()) return false

            val extras = Bundle().apply {
                putBinder(PROVIDER_BINDER_KEY, serviceBinderProvider())
            }
            val providerClass = provider::class.java
            val callerPackage = providerCallerPackage()

            val result = systemApi("ContentProvider.call") {
                when {
                    sdk >= 31 -> {
                        val attrSource = AttributionSource.Builder(Process.myUid())
                            .setPackageName(callerPackage)
                            .build()
                        providerClass.getMethod(
                            "call",
                            AttributionSource::class.java,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        ).invoke(provider, attrSource, authority, PROVIDER_METHOD, null, extras)
                    }
                    sdk >= 30 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, null, authority, PROVIDER_METHOD, null, extras)

                    sdk >= 29 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, authority, PROVIDER_METHOD, null, extras)

                    else -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, PROVIDER_METHOD, null, extras)
                }
            } as? Bundle ?: return false

            return result.getBoolean(PROVIDER_RESULT_OK)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            if (holder != null) {
                try {
                    releaseContentProviderExternal(authority)
                } catch (releaseError: Throwable) {
                    if (failure != null) failure.addSuppressed(releaseError) else throw releaseError
                }
            }
        }
    }

    private fun enforceProviderBelongsToTrustedApp(holder: Any) {
        val info = holder::class.java
            .getField("info")
            .get(holder) as? ProviderInfo
            ?: throw IllegalStateException("ContentProviderHolder.info 不是 ProviderInfo")

        val authorities = info.authority?.split(';')?.map { it.trim() }.orEmpty()
        if (PROVIDER_AUTHORITY !in authorities) {
            throw SecurityException("Provider authority 不匹配：${info.authority}")
        }

        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("ProviderInfo.applicationInfo 为 null")

        if (appInfo.packageName != APP_PACKAGE) {
            throw SecurityException("Provider package 不匹配：${appInfo.packageName}")
        }

        val trustedUid = startupInfoProvider().trustedUid
        if (appInfo.uid != trustedUid) {
            throw SecurityException("Provider uid=${appInfo.uid} 与可信 uid=$trustedUid 不一致")
        }
    }

    private fun releaseContentProviderExternal(authority: String) {
        val am = activityManager()
        systemApi("removeContentProviderExternal") {
            am::class.java.getMethod(
                "removeContentProviderExternal",
                String::class.java,
                IBinder::class.java
            ).invoke(am, authority, null)
        }
    }

    private fun activityManager(): Any {
        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: throw IllegalStateException("ActivityManager service 不存在")

        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)
            ?: throw IllegalStateException("IActivityManager.asInterface 返回 null")
    }

    private inline fun <T> systemApi(apiName: String, block: () -> T): T {
        return try {
            block()
        } catch (e: InvocationTargetException) {
            val target = e.targetException ?: e
            throw when (target) {
                is RuntimeException -> target
                is Error -> target
                else -> IllegalStateException("$apiName 调用失败", target)
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("$apiName 反射调用失败", e)
        }
    }

    private fun providerCallerPackage(): String = when (Process.myUid()) {
        0, 1000 -> "android"
        else -> "com.android.shell"
    }

    private class ClientDeliveryState {
        var wifiStateInFlight: Boolean = false
        var pendingWifiState: WifiState? = null
        var savedWifiListInFlight: Boolean = false
        var pendingSavedWifiList: SavedWifiList? = null
    }

    companion object {
        private const val TAG = "ServiceCommunication"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val PROVIDER_AUTHORITY = "io.github.bszapp.wifitoolbox.provider"
        private const val PROVIDER_METHOD = "sendBinder"
        private const val PROVIDER_BINDER_KEY = "binder"
        private const val PROVIDER_RESULT_OK = "ok"
    }
}
