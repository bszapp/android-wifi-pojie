package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.RemoteCallbackList
import android.os.WorkSource
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.Locale

@Keep
@SuppressLint("PrivateApi")
@Suppress("DEPRECATION")
open class MainService(
    private val startupMode: String? = null,
    startupVersionName: String? = null,
    startupVersionCode: Long? = null
) : IMainService.Stub() {

    private data class AppIdentity(
        val uid: Int,
        val signingCertSha256: Set<String>
    ) {
        val valid: Boolean get() = uid > 0 && signingCertSha256.isNotEmpty()
    }

    private data class AppVersion(
        val name: String,
        val code: Long
    )

    private val callbacks = RemoteCallbackList<IMainServiceCallback>()
    private val sdk = Build.VERSION.SDK_INT

    private val appTokenLock = Any()
    private val publisherLock = Any()

    @Volatile
    private var appTokenAlive = false

    @Volatile
    private var binderPublisherRunning = false

    private var appToken: IBinder? = null
    private var appTokenDeathRecipient: IBinder.DeathRecipient? = null

    private val startupVersion: AppVersion = resolveStartupVersion(
        startupVersionName = startupVersionName,
        startupVersionCode = startupVersionCode
    )

    /**
     * 服务启动时固定可信 App 身份。
     *
     * 不能每次调用时只按包名动态取 UID。
     * 否则原 App 卸载后，同包名盗版 App 可能被旧 root/shell 服务信任。
     *
     * 这里必须 fail-fast：
     * 启动时拿不到 UID 或签名，直接报错，不进入“后续不校验”的危险状态。
     */
    private val trustedApp: AppIdentity = resolveTrustedAppIdentity()

    init {
        killOlderInstances()
        Log.d(
            TAG,
            "可信 App 身份：uid=${trustedApp.uid}, certs=${trustedApp.signingCertSha256.joinToString()}"
        )
        Log.d(TAG, "启动信息：mode=$startupMode version=${startupVersion.name}(${startupVersion.code})")
        startBinderPublisher("service-start")
    }

    // ---------------------------------------------------------------
    // 统一错误处理：不做兼容回退，反射异常直接解包抛出
    // ---------------------------------------------------------------

    private inline fun <T> systemApi(apiName: String, block: () -> T): T {
        return try {
            block()
        } catch (e: InvocationTargetException) {
            throw unwrapSystemApiError(apiName, e)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("$apiName 反射调用失败", e)
        }
    }

    private fun unwrapSystemApiError(apiName: String, e: InvocationTargetException): Throwable {
        val target = e.targetException ?: e
        return when (target) {
            is RuntimeException -> target
            is Error -> target
            else -> IllegalStateException("$apiName 调用失败", target)
        }
    }

    private fun requireBooleanSuccess(apiName: String, result: Any?) {
        if (result is Boolean && !result) {
            throw IllegalStateException("$apiName 返回 false")
        }
    }

    private fun requireNonNegativeInt(apiName: String, result: Any?) {
        if (result is Int && result < 0) {
            throw IllegalStateException("$apiName 返回 $result")
        }
    }

    // ---------------------------------------------------------------
    // 调用方校验：只允许启动服务时的同 UID + 同签名 App 调用
    // ---------------------------------------------------------------
    @SuppressLint("NewApi")
    private fun resolveStartupVersion(
        startupVersionName: String?,
        startupVersionCode: Long?
    ): AppVersion {
        if (!startupVersionName.isNullOrBlank() && startupVersionCode != null && startupVersionCode >= 0) {
            return AppVersion(startupVersionName, startupVersionCode)
        }

        val info = getPackageInfoCompat(
            packageName = APP_PACKAGE,
            flags = 0L,
            userId = 0
        ) ?: throw IllegalStateException("无法读取 $APP_PACKAGE 的版本信息")

        val name = info.versionName
            ?.takeIf { it.isNotBlank() }
            ?: startupVersionName
            ?: throw IllegalStateException("无法读取 $APP_PACKAGE 的 versionName")

        val code = if (sdk >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

        return AppVersion(name, code)
    }

    private fun resolveTrustedAppIdentity(userId: Int = 0): AppIdentity {
        val appInfo = getApplicationInfoCompat(APP_PACKAGE, userId)
            ?: throw IllegalStateException("找不到可信 App：$APP_PACKAGE userId=$userId")

        val pkgInfo = getPackageInfoCompat(
            packageName = APP_PACKAGE,
            flags = packageInfoFlagsForSignatures(),
            userId = userId
        ) ?: throw IllegalStateException("无法读取可信 App 包信息：$APP_PACKAGE userId=$userId")

        val digests = collectSignatureDigests(pkgInfo)
        if (digests.isEmpty()) {
            throw IllegalStateException("无法读取可信 App 签名：$APP_PACKAGE userId=$userId")
        }

        return AppIdentity(
            uid = appInfo.uid,
            signingCertSha256 = digests
        )
    }

    private fun enforceCallerIsApp() {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (isLocalCall(callingUid, callingPid)) return

        if (!isCallerTrusted(callingUid)) {
            throw SecurityException(
                "调用方 uid=$callingUid pid=$callingPid 未授权" +
                        "（期望 uid=${trustedApp.uid} 且签名匹配）"
            )
        }
    }

    private fun isLocalCall(callingUid: Int, callingPid: Int): Boolean {
        /*
         * 不能只比较 uid。
         * 服务运行在 root/shell 时，其他 root/shell 进程也可能是同 uid。
         */
        return callingUid == Process.myUid() && callingPid == Process.myPid()
    }

    private fun isCallerTrusted(callingUid: Int): Boolean {
        if (!trustedApp.valid) {
            throw IllegalStateException("可信 App 身份无效")
        }

        if (callingUid != trustedApp.uid) {
            Log.w(TAG, "uid 不匹配：caller=$callingUid expected=${trustedApp.uid}")
            return false
        }

        val packages = getPackagesForUidCompat(callingUid)
        if (APP_PACKAGE !in packages) {
            Log.w(TAG, "uid=$callingUid 当前不再属于 $APP_PACKAGE，而是 ${packages.joinToString()}")
            return false
        }

        val current = resolveTrustedAppIdentity(userIdFromUid(callingUid))
        if (current.uid != trustedApp.uid) {
            Log.w(TAG, "当前包 uid=${current.uid} 与启动时 uid=${trustedApp.uid} 不一致")
            return false
        }

        val sameSigner = current.signingCertSha256.any { it in trustedApp.signingCertSha256 }
        if (!sameSigner) {
            Log.w(
                TAG,
                "签名不匹配：current=${current.signingCertSha256.joinToString()} " +
                        "trusted=${trustedApp.signingCertSha256.joinToString()}"
            )
            return false
        }

        return true
    }

    private fun getPackageManagerService(): Any {
        val pmBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "package") as? IBinder
            ?: throw IllegalStateException("PackageManager service 不存在")

        return Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, pmBinder)
            ?: throw IllegalStateException("IPackageManager.asInterface 返回 null")
    }

    private fun getApplicationInfoCompat(packageName: String, userId: Int): ApplicationInfo? {
        val pm = getPackageManagerService()
        val pmClass = pm::class.java

        val result = systemApi("getApplicationInfo") {
            if (sdk >= 33) {
                pmClass.getMethod(
                    "getApplicationInfo",
                    String::class.java,
                    Long::class.java,
                    Int::class.java
                ).invoke(pm, packageName, 0L, userId)
            } else {
                pmClass.getMethod(
                    "getApplicationInfo",
                    String::class.java,
                    Int::class.java,
                    Int::class.java
                ).invoke(pm, packageName, 0, userId)
            }
        }

        return result as? ApplicationInfo
    }

    private fun getPackageInfoCompat(
        packageName: String,
        flags: Long,
        userId: Int
    ): PackageInfo? {
        val pm = getPackageManagerService()
        val pmClass = pm::class.java

        val result = systemApi("getPackageInfo") {
            if (sdk >= 33) {
                pmClass.getMethod(
                    "getPackageInfo",
                    String::class.java,
                    Long::class.java,
                    Int::class.java
                ).invoke(pm, packageName, flags, userId)
            } else {
                pmClass.getMethod(
                    "getPackageInfo",
                    String::class.java,
                    Int::class.java,
                    Int::class.java
                ).invoke(pm, packageName, flags.toInt(), userId)
            }
        }

        return result as? PackageInfo
    }

    private fun getPackagesForUidCompat(uid: Int): List<String> {
        val pm = getPackageManagerService()

        val result = systemApi("getPackagesForUid") {
            pm::class.java
                .getMethod("getPackagesForUid", Int::class.java)
                .invoke(pm, uid)
        } as? Array<*> ?: return emptyList()

        return result.map {
            it as? String ?: throw IllegalStateException("getPackagesForUid 返回了非 String 项：$it")
        }
    }

    private fun packageInfoFlagsForSignatures(): Long {
        return if (sdk >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES.toLong()
        }
    }

    @SuppressLint("NewApi")
    private fun collectSignatureDigests(packageInfo: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (sdk >= 28) {
            val signingInfo = packageInfo.signingInfo
                ?: throw IllegalStateException("PackageInfo.signingInfo 为 null")

            when {
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners ?: emptyArray()
                else -> signingInfo.signingCertificateHistory
                    ?: signingInfo.apkContentsSigners
                    ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }

        return signatures.mapTo(linkedSetOf()) { it.sha256() }
    }

    private fun Signature.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(":") { byte ->
            String.format(Locale.US, "%02X", byte.toInt() and 0xff)
        }
    }

    private fun userIdFromUid(uid: Int): Int = uid / 100000

    // ---------------------------------------------------------------
    // IMainService
    // ---------------------------------------------------------------

    override fun connect(): Boolean {
        enforceCallerIsApp()

        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        Log.d(
            TAG,
            "connect() uid=$callingUid pid=$callingPid expectedUid=${trustedApp.uid} → 允许"
        )

        return true
    }

    override fun isAlive(): Boolean {
        enforceCallerIsApp()
        return true
    }

    override fun getUid(): Int {
        enforceCallerIsApp()
        return Process.myUid()
    }

    override fun getUidStr(): String {
        enforceCallerIsApp()

        return Runtime.getRuntime()
            .exec("id")
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
    }

    override fun getPid(): Int {
        enforceCallerIsApp()
        return Process.myPid()
    }

    override fun getStartupMode(): String? {
        enforceCallerIsApp()
        return startupMode
    }

    override fun getStartupVersionName(): String {
        enforceCallerIsApp()
        return startupVersion.name
    }

    override fun getStartupVersionCode(): Long {
        enforceCallerIsApp()
        return startupVersion.code
    }

    override fun startScan(): Boolean {
        enforceCallerIsApp()
        Log.d(TAG, "startScan() | SDK=$sdk | uid=${Process.myUid()}")

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi("startScan") {
            when {
                sdk >= 30 -> clazz.getMethod(
                    "startScan",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, packageName, null)

                sdk >= 28 -> clazz.getMethod(
                    "startScan",
                    String::class.java
                ).invoke(wifiService, packageName)

                else -> {
                    val scanSettings = Class.forName("android.net.wifi.ScanSettings")
                    if (sdk >= 26) {
                        clazz.getMethod(
                            "startScan",
                            scanSettings,
                            WorkSource::class.java,
                            String::class.java
                        ).invoke(wifiService, null, null, packageName)
                    } else {
                        clazz.getMethod(
                            "startScan",
                            scanSettings,
                            WorkSource::class.java
                        ).invoke(wifiService, null, null)
                    }
                }
            }
        }

        requireBooleanSuccess("startScan", result)
        return true
    }

    override fun getScanResults(): List<ScanResult> {
        enforceCallerIsApp()

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi("getScanResults") {
            when {
                sdk >= 35 -> clazz.getMethod(
                    "getScanResults",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, packageName, null)

                sdk >= 30 -> clazz.getMethod(
                    "getScanResults",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, packageName, null)

                else -> clazz.getMethod(
                    "getScanResults",
                    String::class.java
                ).invoke(wifiService, packageName)
            }
        } ?: throw IllegalStateException("getScanResults 返回 null")

        return parseScanResultList(raw, "getScanResults")
    }

    override fun getSavedWifiList(): ByteArray {
        enforceCallerIsApp()

        val list = getSavedWifiListInternal()
        val parcel = Parcel.obtain()

        return try {
            parcel.writeTypedList(list)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun getSavedWifiListInternal(): List<WifiConfiguration> {
        val raw = try {
            queryPrivilegedConfiguredNetworks()
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "getPrivilegedConfiguredNetworks 被安全策略拒绝，回退到普通配置列表（不含密码）: ${e.message}"
            )
            return getSavedWifiListFallback()
        }

        val list = parseWifiConfigurationList(
            raw = raw ?: throw IllegalStateException("getPrivilegedConfiguredNetworks 返回 null"),
            apiName = "getPrivilegedConfiguredNetworks"
        )

        return list.distinctBy { it.networkId }
    }

    @SuppressLint("NewApi")
    private fun queryPrivilegedConfiguredNetworks(): Any? {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        return systemApi("getPrivilegedConfiguredNetworks") {
            when {
                sdk >= 33 -> {
                    val user = when (Process.myUid()) {
                        0 -> "root"
                        1000 -> "system"
                        else -> "shell"
                    }

                    val attrSource = AttributionSource::class.java
                        .getConstructor(
                            Int::class.java,
                            String::class.java,
                            String::class.java,
                            Set::class.java,
                            AttributionSource::class.java
                        )
                        .newInstance(Process.myUid(), packageName, packageName, null, null)

                    val bundle = Bundle().apply {
                        putParcelable(
                            "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE",
                            attrSource as Parcelable
                        )
                    }

                    clazz.getMethod(
                        "getPrivilegedConfiguredNetworks",
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(wifiService, user, packageName, bundle)
                }

                sdk >= 30 -> clazz.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, packageName, null)

                sdk >= 28 -> clazz.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java
                ).invoke(wifiService, packageName)

                else -> clazz.getMethod(
                    "getPrivilegedConfiguredNetworks"
                ).invoke(wifiService)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getSavedWifiListFallback(): List<WifiConfiguration> {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi("getConfiguredNetworks") {
            when {
                sdk >= 30 -> clazz.getMethod(
                    "getConfiguredNetworks",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, packageName, null)

                sdk >= 28 -> clazz.getMethod(
                    "getConfiguredNetworks",
                    String::class.java
                ).invoke(wifiService, packageName)

                else -> clazz.getMethod(
                    "getConfiguredNetworks"
                ).invoke(wifiService)
            }
        } ?: throw IllegalStateException("getConfiguredNetworks 返回 null")

        val list = parseWifiConfigurationList(raw, "getConfiguredNetworks")
        return list.distinctBy { it.networkId }
    }

    private fun parseWifiConfigurationList(raw: Any, apiName: String): List<WifiConfiguration> {
        val rawList = when (raw) {
            is List<*> -> raw
            else -> {
                val list = systemApi("$apiName.getList") {
                    raw.javaClass.getMethod("getList").invoke(raw)
                }

                list as? List<*>
                    ?: throw IllegalStateException("$apiName.getList 返回类型不是 List：${list?.javaClass?.name}")
            }
        }

        return rawList.mapIndexed { index, item ->
            item as? WifiConfiguration
                ?: throw IllegalStateException("$apiName 返回第 $index 项不是 WifiConfiguration：$item")
        }
    }

    private fun parseScanResultList(raw: Any, apiName: String): List<ScanResult> {
        val rawList = when (raw) {
            is List<*> -> raw
            else -> {
                val list = systemApi("$apiName.getList") {
                    raw.javaClass.getMethod("getList").invoke(raw)
                }

                list as? List<*>
                    ?: throw IllegalStateException("$apiName.getList 返回类型不是 List：${list?.javaClass?.name}")
            }
        }

        return rawList.mapIndexed { index, item ->
            item as? ScanResult
                ?: throw IllegalStateException("$apiName 返回第 $index 项不是 ScanResult：$item")
        }
    }

    override fun updateWifiConfig(networkId: Int, patchBytes: ByteArray): Boolean {
        enforceCallerIsApp()

        val patch = readWifiConfigPatch(patchBytes)

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        patch.enabled?.let { enabled ->
            val result = systemApi(if (enabled) "enableNetwork" else "disableNetwork") {
                if (enabled) {
                    if (sdk >= 29) {
                        clazz.getMethod(
                            "enableNetwork",
                            Int::class.java,
                            Boolean::class.java,
                            String::class.java
                        ).invoke(wifiService, networkId, false, packageName)
                    } else {
                        clazz.getMethod(
                            "enableNetwork",
                            Int::class.java,
                            Boolean::class.java
                        ).invoke(wifiService, networkId, false)
                    }
                } else {
                    if (sdk >= 29) {
                        clazz.getMethod(
                            "disableNetwork",
                            Int::class.java,
                            String::class.java
                        ).invoke(wifiService, networkId, packageName)
                    } else {
                        clazz.getMethod(
                            "disableNetwork",
                            Int::class.java
                        ).invoke(wifiService, networkId)
                    }
                }
            }

            requireBooleanSuccess(if (enabled) "enableNetwork" else "disableNetwork", result)
        }

        patch.autoJoin?.let { autoJoin ->
            if (sdk >= 30) {
                systemApi("allowAutojoin") {
                    clazz.getMethod(
                        "allowAutojoin",
                        Int::class.java,
                        Boolean::class.java
                    ).invoke(wifiService, networkId, autoJoin)
                }
            } else {
                val config = getSavedWifiListInternal()
                    .firstOrNull { it.networkId == networkId }
                    ?: throw IllegalArgumentException("找不到 networkId=$networkId 的 Wi-Fi 配置")

                WifiConfiguration::class.java
                    .getField("allowAutojoin")
                    .setBoolean(config, autoJoin)

                val result = systemApi("updateNetwork") {
                    clazz.getMethod(
                        "updateNetwork",
                        WifiConfiguration::class.java
                    ).invoke(wifiService, config)
                }

                requireNonNegativeInt("updateNetwork", result)
            }
        }

        return true
    }

    private fun readWifiConfigPatch(patchBytes: ByteArray): WifiConfigPatch {
        val parcel = Parcel.obtain()

        return try {
            parcel.unmarshall(patchBytes, 0, patchBytes.size)
            parcel.setDataPosition(0)

            parcel.readParcelable<WifiConfigPatch>(WifiConfigPatch::class.java.classLoader)
                ?: throw IllegalArgumentException("patchBytes 无法解析为 WifiConfigPatch")
        } finally {
            parcel.recycle()
        }
    }

    override fun watchApp(token: IBinder) {
        enforceCallerIsApp()

        synchronized(appTokenLock) {
            appTokenDeathRecipient?.let { old ->
                appToken?.unlinkToDeath(old, 0)
            }

            appToken = token
            appTokenAlive = true

            val recipient = IBinder.DeathRecipient {
                Log.d(TAG, "应用进程断开，等待下一次连接")
                synchronized(appTokenLock) {
                    if (appToken === token) {
                        appToken = null
                        appTokenDeathRecipient = null
                        appTokenAlive = false
                    }
                }
                startBinderPublisher("app-death")
            }

            appTokenDeathRecipient = recipient
            token.linkToDeath(recipient, 0)
        }

        Log.d(TAG, "watchApp() 已登记应用进程 token")
    }

    override fun shutdown() {
        enforceCallerIsApp()
        Log.d(TAG, "收到 shutdown，服务退出")
        Process.killProcess(Process.myPid())
    }

    override fun isWifiEnabled(): Boolean {
        enforceCallerIsApp()

        val wifiService = getWifiService()
        val state = systemApi("getWifiEnabledState") {
            wifiService::class.java
                .getMethod("getWifiEnabledState")
                .invoke(wifiService)
        } as? Int ?: throw IllegalStateException("getWifiEnabledState 返回类型不是 Int")

        return state == WifiManager.WIFI_STATE_ENABLED ||
                state == WifiManager.WIFI_STATE_ENABLING
    }

    override fun setWifiEnabled(enabled: Boolean) {
        enforceCallerIsApp()

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi("setWifiEnabled") {
            if (sdk >= 29) {
                clazz.getMethod(
                    "setWifiEnabled",
                    String::class.java,
                    Boolean::class.java
                ).invoke(wifiService, packageName, enabled)
            } else {
                clazz.getMethod(
                    "setWifiEnabled",
                    Boolean::class.java
                ).invoke(wifiService, enabled)
            }
        }

        requireBooleanSuccess("setWifiEnabled", result)
    }

    override fun registerCallback(cb: IMainServiceCallback) {
        enforceCallerIsApp()
        callbacks.register(cb)
    }

    override fun unregisterCallback(cb: IMainServiceCallback) {
        enforceCallerIsApp()
        callbacks.unregister(cb)
    }

    // ---------------------------------------------------------------
    // Binder 投递器
    // ---------------------------------------------------------------

    private fun startBinderPublisher(reason: String) {
        synchronized(publisherLock) {
            if (binderPublisherRunning) return
            binderPublisherRunning = true
        }

        Thread({
            Log.d(TAG, "Binder 投递器启动：$reason")
            try {
                var idleDelayMs = 300L

                while (!appTokenAlive) {
                    if (isTrustedAppProcessRunning()) {
                        if (tryPushBinder()) {
                            Log.d(TAG, "Binder 已投递到应用进程")
                            idleDelayMs = 500L
                        } else {
                            idleDelayMs = 1000L
                        }
                    } else {
                        idleDelayMs = 1000L
                    }

                    Thread.sleep(idleDelayMs)
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

    private fun isTrustedAppProcessRunning(): Boolean {
        if (!trustedApp.valid) {
            throw IllegalStateException("可信 App 身份无效")
        }

        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: throw IllegalStateException("ActivityManager service 不存在")

        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)
            ?: throw IllegalStateException("IActivityManager.asInterface 返回 null")

        val list = systemApi("getRunningAppProcesses") {
            am::class.java
                .getMethod("getRunningAppProcesses")
                .invoke(am)
        } as? List<*> ?: throw IllegalStateException("getRunningAppProcesses 返回类型不是 List")

        return list.any { item ->
            val info = item as? ActivityManager.RunningAppProcessInfo
                ?: throw IllegalStateException("getRunningAppProcesses 返回了非 RunningAppProcessInfo 项：$item")

            val packages = info.pkgList?.toList().orEmpty()
            info.uid == trustedApp.uid &&
                    (info.processName == APP_PACKAGE || APP_PACKAGE in packages)
        }
    }

    @SuppressLint("NewApi")
    private fun tryPushBinder(): Boolean {
        val authority = PROVIDER_AUTHORITY
        var holder: Any? = null
        var failure: Throwable? = null

        try {
            val amBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity") as? IBinder
                ?: throw IllegalStateException("ActivityManager service 不存在")

            val am = Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, amBinder)
                ?: throw IllegalStateException("IActivityManager.asInterface 返回 null")

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

            if (!providerBinder.pingBinder()) {
                return false
            }

            val extras = Bundle()
            extras.putBinder(PROVIDER_BINDER_KEY, this.asBinder())

            val providerClass = provider::class.java

            val result = systemApi("ContentProvider.call") {
                when {
                    sdk >= 31 -> {
                        val attrSource = AttributionSource.Builder(Process.myUid())
                            .setPackageName(packageName)
                            .build()

                        providerClass.getMethod(
                            "call",
                            AttributionSource::class.java,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        ).invoke(
                            provider,
                            attrSource,
                            authority,
                            PROVIDER_METHOD,
                            null,
                            extras
                        )
                    }

                    sdk >= 30 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(
                        provider,
                        packageName,
                        null,
                        authority,
                        PROVIDER_METHOD,
                        null,
                        extras
                    )

                    sdk >= 29 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(
                        provider,
                        packageName,
                        authority,
                        PROVIDER_METHOD,
                        null,
                        extras
                    )

                    else -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(
                        provider,
                        packageName,
                        PROVIDER_METHOD,
                        null,
                        extras
                    )
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
                    if (failure != null) {
                        failure.addSuppressed(releaseError)
                    } else {
                        throw releaseError
                    }
                }
            }
        }
    }

    private fun enforceProviderBelongsToTrustedApp(holder: Any) {
        val info = holder::class.java
            .getField("info")
            .get(holder) as? ProviderInfo
            ?: throw IllegalStateException("ContentProviderHolder.info 不是 ProviderInfo")

        val authorities = info.authority
            ?.split(';')
            ?.map { it.trim() }
            .orEmpty()

        if (PROVIDER_AUTHORITY !in authorities) {
            throw SecurityException("Provider authority 不匹配：${info.authority}")
        }

        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("ProviderInfo.applicationInfo 为 null")

        if (appInfo.packageName != APP_PACKAGE) {
            throw SecurityException("Provider package 不匹配：${appInfo.packageName}")
        }

        if (appInfo.uid != trustedApp.uid) {
            throw SecurityException("Provider uid=${appInfo.uid} 与可信 uid=${trustedApp.uid} 不一致")
        }

        val current = resolveTrustedAppIdentity(userIdFromUid(appInfo.uid))
        val sameSigner = current.signingCertSha256.any { it in trustedApp.signingCertSha256 }

        if (!sameSigner) {
            throw SecurityException(
                "Provider 签名不匹配：current=${current.signingCertSha256.joinToString()} " +
                        "trusted=${trustedApp.signingCertSha256.joinToString()}"
            )
        }
    }

    private fun releaseContentProviderExternal(authority: String) {
        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: throw IllegalStateException("ActivityManager service 不存在")

        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)
            ?: throw IllegalStateException("IActivityManager.asInterface 返回 null")

        systemApi("removeContentProviderExternal") {
            am::class.java.getMethod(
                "removeContentProviderExternal",
                String::class.java,
                IBinder::class.java
            ).invoke(am, authority, null)
        }
    }

    // ---------------------------------------------------------------
    // 旧实例清理 & 系统服务工具
    // ---------------------------------------------------------------

    private fun killOlderInstances() {
        val myPid = Process.myPid()

        val myName = try {
            java.io.File("/proc/self/cmdline")
                .readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
        } catch (e: Exception) {
            throw IllegalStateException("读取当前进程名失败", e)
        }

        if (myName.isEmpty()) {
            throw IllegalStateException("当前进程名为空")
        }

        (java.io.File("/proc").listFiles() ?: throw IllegalStateException("/proc 列表为空")).forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid >= myPid) return@forEach

            try {
                val name = java.io.File(dir, "cmdline")
                    .readBytes()
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
                    .trim()

                if (name == myName) {
                    Log.w(TAG, "kill 旧实例 pid=$pid")
                    Process.killProcess(pid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查旧实例 pid=$pid 失败", e)
            }
        }
    }

    private fun getWifiService(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wifi") as? IBinder
            ?: throw IllegalStateException("Wifi service 不存在")

        return Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: throw IllegalStateException("IWifiManager.asInterface 返回 null")
    }

    private val packageName = when (Process.myUid()) {
        0, 1000 -> "android"
        else -> "com.android.shell"
    }

    companion object {
        private const val TAG = "ToolboxMainService"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val PROVIDER_AUTHORITY = "io.github.bszapp.wifitoolbox.provider"
        private const val PROVIDER_METHOD = "sendBinder"
        private const val PROVIDER_BINDER_KEY = "binder"
        private const val PROVIDER_RESULT_OK = "ok"
    }
}