package com.wifi.toolbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.util.Log
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.WifiLogData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WifiBroadcastService(private val context: Context) : AutoCloseable {

    private val MIN_INTERVAL = 200L

    private val app = context.applicationContext as MyApplication
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _logFlow = MutableSharedFlow<WifiLogData>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    private var connectStartTime: Long = 0L
    private var handshakeTimeoutJob: Job? = null
    private var currentSsid: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION == action) {
                val newState =
                    intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)

                if (error == WifiManager.ERROR_AUTHENTICATING && app.pojieConfig.failureFlag == 0) {
                    cancelTimeout()
                    emitEvent(WifiLogData.EVENT_CONNECT_FAILED)
                } else if (newState == SupplicantState.FOUR_WAY_HANDSHAKE && app.pojieConfig.failureFlag == 1) {
                    startHandshakeTimeoutMonitor()
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION == action) {
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (info != null && info.isConnected) {
                    cancelTimeout()
                    emitEvent(WifiLogData.EVENT_WIFI_CONNECTED)
                }
            }
        }
    }

    init {
        val filter = IntentFilter()
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
    }

    fun setTargetSsid(ssid: String) {
        currentSsid = ssid
        connectStartTime = System.currentTimeMillis()
        cancelTimeout()
    }

    private fun startHandshakeTimeoutMonitor() {
        if (handshakeTimeoutJob != null) return

        val timeout = app.pojieConfig.timeout.toLong()
        handshakeTimeoutJob = scope.launch {
            delay(timeout)
            if (isActive) {
                _logFlow.tryEmit(
                    WifiLogData(
                        WifiLogData.EVENT_HANDSHAKE,
                        connectStartTime,
                        currentSsid,
                        (timeout + 1000).toInt(),
                        99
                    )
                )
            }
        }
    }

    private fun cancelTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    private fun emitEvent(eventId: Int) {
        _logFlow.tryEmit(
            WifiLogData(
                eventId,
                connectStartTime,
                currentSsid
            )
        )
    }

    override fun close() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        cancelTimeout()
        scope.cancel()
    }
}