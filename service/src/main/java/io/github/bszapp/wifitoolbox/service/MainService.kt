package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo

@Keep
open class MainService() : IMainService.Stub() {

    constructor(startupInfo: StartupInfo) : this() {
        initializeFromStartupInfo(startupInfo, source = "service-start")
    }

    private val initializer: ServiceInitializer = ServiceInitializer()

    private val communication: ServiceCommunication = ServiceCommunication(
        startupInfoProvider = { initializer.requireStartupInfo() },
        serviceBinderProvider = { this.asBinder() },
    )

    override fun initializeStartupInfo(startupInfo: StartupInfo) {
        communication.enforceStartupInitializer(startupInfo)
        initializeFromStartupInfo(startupInfo, source = "app-init")
    }

    private fun initializeFromStartupInfo(startupInfo: StartupInfo, source: String): StartupInfo {
        val completed = initializer.initialize(startupInfo, source)
        communication.startBinderPublisher("startup-info-installed")
        return completed
    }

    override fun connect(): Boolean = communication.connect()

    override fun isAlive(): Boolean = communication.isAlive()

    override fun getStartupInfo(): StartupInfo = communication.callFromApp {
        initializer.requireStartupInfo()
    }

    override fun executeAndroidApi(request: AndroidApiRequest): AndroidApiResponse = communication.callFromApp {
        initializer.androidApi
            ?.execute(request)
            ?: AndroidApiResponse.failure(IllegalStateException("AndroidApi 尚未初始化"))
    }

    override fun shutdown() = communication.callFromApp {
        Log.d(TAG, "收到 shutdown，服务退出")
        Process.killProcess(Process.myPid())
    }

    override fun registerCallback(cb: IMainServiceCallback) {
        communication.registerCallback(cb)
    }

    override fun unregisterCallback(cb: IMainServiceCallback) {
        communication.unregisterCallback(cb)
    }

    companion object {
        private const val TAG = "ToolboxMainService"
    }
}
