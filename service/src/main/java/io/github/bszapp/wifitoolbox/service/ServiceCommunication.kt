package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.atomic.AtomicLong

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
    private val logRangeLock = Any()
    private val pendingLogRanges = mutableMapOf<LogSource, ServiceLogRange>()
    private val logDeliveryScheduled = AtomicBoolean(false)
    private val terminalLogRangeLock = Any()
    private val pendingTerminalLogRanges = mutableMapOf<Long, TerminalLogRangeSnapshot>()
    private val terminalLogDeliveryScheduled = AtomicBoolean(false)
    private val monitorRecordedBytesLock = Any()
    private var pendingMonitorRecordedBytes: Long? = null
    private val monitorRecordedBytesDeliveryScheduled = AtomicBoolean(false)
    private val callbackBroadcastLock = Any()
    private val deliveryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "wifi-ipc-delivery").apply { isDaemon = true }
    }
    private val snapshotGeneration = AtomicLong(0L)
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
        pushLogRangeChanged(callback, LogSource.Service, oldestAvailableId, latestId)
    }

    fun pushSystemWifiLogRangeChanged(
        callback: IServiceLogCallback,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        pushLogRangeChanged(callback, LogSource.SystemWifi, oldestAvailableId, latestId)
    }

    fun broadcastServiceLogRangeChanged(oldestAvailableId: Long, latestId: Long) {
        broadcastLogRangeChanged(LogSource.Service, oldestAvailableId, latestId)
    }

    fun broadcastSystemWifiLogRangeChanged(oldestAvailableId: Long, latestId: Long) {
        broadcastLogRangeChanged(LogSource.SystemWifi, oldestAvailableId, latestId)
    }

    private fun pushLogRangeChanged(
        callback: IServiceLogCallback,
        source: LogSource,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        runCatching {
            when (source) {
                LogSource.Service -> callback.onServiceLogRangeChanged(
                    oldestAvailableId,
                    latestId,
                )
                LogSource.SystemWifi -> callback.onSystemWifiLogRangeChanged(
                    oldestAvailableId,
                    latestId,
                )
            }
        }.onFailure { serviceLogCallbacks.unregister(callback) }
    }

    private fun broadcastLogRangeChanged(
        source: LogSource,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        synchronized(logRangeLock) {
            val pending = pendingLogRanges[source]
            pendingLogRanges[source] = ServiceLogRange(
                source = source,
                oldestAvailableId = maxOf(pending?.oldestAvailableId ?: 1L, oldestAvailableId),
                latestId = maxOf(pending?.latestId ?: 0L, latestId),
            )
        }
        scheduleLogDelivery()
    }

    private fun scheduleLogDelivery() {
        if (!logDeliveryScheduled.compareAndSet(false, true)) return
        deliveryExecutor.execute {
            try {
                while (true) {
                    val ranges = synchronized(logRangeLock) {
                        if (pendingLogRanges.isEmpty()) return@synchronized emptyList()
                        pendingLogRanges.values.toList().also { pendingLogRanges.clear() }
                    }
                    if (ranges.isEmpty()) break
                    forEachServiceLogCallback { callback ->
                        ranges.forEach { range ->
                            pushLogRangeChanged(
                                callback = callback,
                                source = range.source,
                                oldestAvailableId = range.oldestAvailableId,
                                latestId = range.latestId,
                            )
                        }
                    }
                }
            } finally {
                logDeliveryScheduled.set(false)
                val hasPendingRange = synchronized(logRangeLock) {
                    pendingLogRanges.isNotEmpty()
                }
                if (hasPendingRange) scheduleLogDelivery()
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

    fun broadcastMonitorRecordedBytes(recordedBytes: Long) {
        synchronized(monitorRecordedBytesLock) {
            pendingMonitorRecordedBytes = recordedBytes
        }
        scheduleMonitorRecordedBytesDelivery()
    }

    private fun scheduleMonitorRecordedBytesDelivery() {
        if (!monitorRecordedBytesDeliveryScheduled.compareAndSet(false, true)) return
        deliveryExecutor.execute {
            try {
                while (true) {
                    val recordedBytes = synchronized(monitorRecordedBytesLock) {
                        pendingMonitorRecordedBytes.also { pendingMonitorRecordedBytes = null }
                    } ?: break
                    forEachCallback { callback ->
                        runCatching { callback.onMonitorRecordedBytesChanged(recordedBytes) }
                            .onFailure {
                                Log.w(TAG, "推送监听模式录制大小失败：${it.message}", it)
                            }
                    }
                }
            } finally {
                monitorRecordedBytesDeliveryScheduled.set(false)
                val hasPending = synchronized(monitorRecordedBytesLock) {
                    pendingMonitorRecordedBytes != null
                }
                if (hasPending) scheduleMonitorRecordedBytesDelivery()
            }
        }
    }

    fun broadcastMonitorPcapExported(
        requestId: String,
        path: String,
        fileName: String,
    ) {
        forEachCallback { callback ->
            runCatching {
                callback.onMonitorPcapExported(requestId, path, fileName)
            }.onFailure {
                Log.w(TAG, "推送监听模式 PCAP 导出结果失败：${it.message}", it)
            }
        }
    }

    fun pushWifiState(callback: IMainServiceCallback, state: WifiState) {
        val client = clientStates[callback.asBinder()] ?: return
        enqueueSnapshot(
            callback = callback,
            client = client,
            delivery = client.wifiState,
            value = state,
            label = "WifiState",
            encode = WifiParcelTransport::encodeWifiState,
            notify = IMainServiceCallback::onWifiStateChanged,
        )
    }

    fun pushSavedWifiList(callback: IMainServiceCallback, value: SavedWifiList) {
        val client = clientStates[callback.asBinder()] ?: return
        enqueueSnapshot(
            callback = callback,
            client = client,
            delivery = client.savedWifiList,
            value = value,
            label = "SavedWifiList",
            encode = WifiParcelTransport::encodeSavedWifiList,
            notify = IMainServiceCallback::onSavedWifiListChanged,
        )
    }

    fun pushWifiInformationSourceState(
        callback: IMainServiceCallback,
        value: WifiInformationSourceState,
    ) {
        val client = clientStates[callback.asBinder()] ?: return
        enqueueSnapshot(
            callback = callback,
            client = client,
            delivery = client.wifiInformationSourceState,
            value = value,
            label = "Wi-Fi 信息源状态",
            encode = WifiParcelTransport::encodeWifiInformationSourceState,
            notify = IMainServiceCallback::onWifiInformationSourceStateChanged,
        )
    }

    fun getWifiStateChunk(
        callback: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = getSnapshotChunk(
        callback = callback,
        generation = generation,
        chunkIndex = chunkIndex,
        delivery = { it.wifiState },
    )

    fun getSavedWifiListChunk(
        callback: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = getSnapshotChunk(
        callback = callback,
        generation = generation,
        chunkIndex = chunkIndex,
        delivery = { it.savedWifiList },
    )

    fun getWifiInformationSourceStateChunk(
        callback: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = getSnapshotChunk(
        callback = callback,
        generation = generation,
        chunkIndex = chunkIndex,
        delivery = { it.wifiInformationSourceState },
    )

    fun acknowledgeWifiState(callback: IMainServiceCallback, generation: Long) =
        acknowledgeSnapshot(
            callback = callback,
            generation = generation,
            delivery = { it.wifiState },
            label = "WifiState",
            encode = WifiParcelTransport::encodeWifiState,
            notify = IMainServiceCallback::onWifiStateChanged,
        )

    fun acknowledgeSavedWifiList(callback: IMainServiceCallback, generation: Long) =
        acknowledgeSnapshot(
            callback = callback,
            generation = generation,
            delivery = { it.savedWifiList },
            label = "SavedWifiList",
            encode = WifiParcelTransport::encodeSavedWifiList,
            notify = IMainServiceCallback::onSavedWifiListChanged,
        )

    fun acknowledgeWifiInformationSourceState(
        callback: IMainServiceCallback,
        generation: Long,
    ) = acknowledgeSnapshot(
        callback = callback,
        generation = generation,
        delivery = { it.wifiInformationSourceState },
        label = "Wi-Fi 信息源状态",
        encode = WifiParcelTransport::encodeWifiInformationSourceState,
        notify = IMainServiceCallback::onWifiInformationSourceStateChanged,
    )

    private fun <T> enqueueSnapshot(
        callback: IMainServiceCallback,
        client: ClientDeliveryState,
        delivery: SnapshotDelivery<T>,
        value: T,
        label: String,
        encode: (T) -> WifiParcelTransport.EncodedSnapshot,
        notify: (IMainServiceCallback, Long, Int, Int) -> Unit,
    ) {
        val shouldEncode = synchronized(delivery) {
            if (delivery.encoding || delivery.inFlight != null) {
                delivery.pendingLatest = value
                false
            } else {
                delivery.encoding = true
                true
            }
        }
        if (shouldEncode) {
            encodeAndNotify(callback, client, delivery, value, label, encode, notify)
        }
    }

    private fun <T> encodeAndNotify(
        callback: IMainServiceCallback,
        client: ClientDeliveryState,
        delivery: SnapshotDelivery<T>,
        value: T,
        label: String,
        encode: (T) -> WifiParcelTransport.EncodedSnapshot,
        notify: (IMainServiceCallback, Long, Int, Int) -> Unit,
    ) {
        deliveryExecutor.execute {
            val snapshot = runCatching { encode(value) }
                .getOrElse { error ->
                    Log.w(TAG, "编码 $label 快照失败：${error.message}", error)
                    failSnapshotDelivery(callback, client)
                    return@execute
                }
            val generation = snapshotGeneration.incrementAndGet()
            val shouldNotify = synchronized(delivery) {
                if (clientStates[callback.asBinder()] !== client) {
                    delivery.encoding = false
                    false
                } else {
                    check(delivery.inFlight == null) { "$label 已存在未确认快照" }
                    delivery.inFlight = InFlightSnapshot(generation, snapshot)
                    delivery.encoding = false
                    true
                }
            }
            if (!shouldNotify) return@execute

            runCatching {
                notify(callback, generation, snapshot.chunkCount, snapshot.totalBytes)
            }.onFailure { error ->
                Log.w(TAG, "通知 $label 快照失败：${error.message}", error)
                failSnapshotDelivery(callback, client)
            }
        }
    }

    private fun getSnapshotChunk(
        callback: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
        delivery: (ClientDeliveryState) -> SnapshotDelivery<*>,
    ): ParcelFileDescriptor = callFromApp {
        val client = clientStates[callback.asBinder()]
            ?: throw IllegalStateException("Wi-Fi 数据回调未注册")
        val snapshotDelivery = delivery(client)
        val inFlight = synchronized(snapshotDelivery) {
            snapshotDelivery.inFlight
                ?: throw IllegalStateException("Wi-Fi 数据快照不存在")
        }
        require(inFlight.generation == generation) {
            "Wi-Fi 数据快照 generation 已过期：$generation != ${inFlight.generation}"
        }
        inFlight.snapshot.openChunk(generation, chunkIndex)
    }

    private fun <T> acknowledgeSnapshot(
        callback: IMainServiceCallback,
        generation: Long,
        delivery: (ClientDeliveryState) -> SnapshotDelivery<T>,
        label: String,
        encode: (T) -> WifiParcelTransport.EncodedSnapshot,
        notify: (IMainServiceCallback, Long, Int, Int) -> Unit,
    ) = callFromApp {
        val client = clientStates[callback.asBinder()] ?: return@callFromApp
        val snapshotDelivery = delivery(client)
        val next = synchronized(snapshotDelivery) {
            val inFlight = snapshotDelivery.inFlight ?: return@synchronized null
            if (inFlight.generation != generation) return@synchronized null
            snapshotDelivery.inFlight = null
            snapshotDelivery.pendingLatest.also { pending ->
                snapshotDelivery.pendingLatest = null
                snapshotDelivery.encoding = pending != null
            }
        }
        if (next != null) {
            encodeAndNotify(callback, client, snapshotDelivery, next, label, encode, notify)
        }
    }

    private fun failSnapshotDelivery(
        callback: IMainServiceCallback,
        client: ClientDeliveryState,
    ) {
        callbacks.unregister(callback)
        clientStates.remove(callback.asBinder(), client)
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
        synchronized(callbackBroadcastLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (index in 0 until count) block(callbacks.getBroadcastItem(index))
            } finally {
                callbacks.finishBroadcast()
            }
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
        val source: LogSource,
        val oldestAvailableId: Long,
        val latestId: Long,
    )

    private enum class LogSource {
        Service,
        SystemWifi,
    }

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
        val wifiState = SnapshotDelivery<WifiState>()
        val savedWifiList = SnapshotDelivery<SavedWifiList>()
        val wifiInformationSourceState = SnapshotDelivery<WifiInformationSourceState>()
    }

    private class SnapshotDelivery<T> {
        var encoding: Boolean = false
        var inFlight: InFlightSnapshot? = null
        var pendingLatest: T? = null
    }

    private data class InFlightSnapshot(
        val generation: Long,
        val snapshot: WifiParcelTransport.EncodedSnapshot,
    )

    companion object {
        private const val TAG = "ServiceCommunication"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val PROVIDER_AUTHORITY = "io.github.bszapp.wifitoolbox.provider"
        private const val PROVIDER_METHOD = "sendBinder"
        private const val PROVIDER_BINDER_KEY = "binder"
        private const val PROVIDER_RESULT_OK = "ok"
    }
}
