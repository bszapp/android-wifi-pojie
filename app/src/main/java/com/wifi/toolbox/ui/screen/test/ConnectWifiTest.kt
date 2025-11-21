package com.wifi.toolbox.ui.screen.test

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.items.ActionChip
import com.wifi.toolbox.ui.items.LogState
import com.wifi.toolbox.ui.items.SectionDivider
import com.wifi.toolbox.ui.items.SectionTitle


@Composable
fun ConnectWifiTest(logState: LogState, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    val logView by rememberUpdatedState(logState)

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(title = "设备控制", icon = Icons.Default.Devices)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = "打开wifi",
                        icon = Icons.Filled.Wifi,
                        onClick = {

                        })
                    ActionChip(
                        text = "关闭wifi",
                        icon = Icons.Filled.WifiOff,
                        onClick = {

                        })
                }

                SectionDivider()

                SectionTitle(title = "连接wifi", icon = Icons.Default.InsertLink)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        connectToWifiApi28(context, name, password, logView)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("WifiManager (API 28)")
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            connectToWifiApi29(context, name, password, logView)
                        } else {
                            logView.addLog("设备版本过低")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("WifiNetworkSpecifier (API 29)")
                }
            }
        }
    }
}

@RequiresApi(api = Build.VERSION_CODES.Q)
private fun connectToWifiApi29(
    context: Context,
    ssid: String,
    password: String,
    logView: LogState
) {
    try {
        val builder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)

        if (password.isNotEmpty()) {
            builder.setWpa2Passphrase(password)
        }

        val networkSpecifier = builder.build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                logView.addLog("请求已发送")
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                logView.addLog("wifi连接失败")
                connectivityManager.unregisterNetworkCallback(this)
            }
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback)
    } catch (e: Exception) {
        logView.addLog("E: 请求发送失败")
        logView.addLog(e.stackTraceToString())
    }
}

private fun connectToWifiApi28(
    context: Context,
    ssid: String,
    password: String,
    logView: LogState
) {
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiConfig = WifiConfiguration()
    wifiConfig.SSID = "\"$ssid \""
    wifiConfig.preSharedKey = "\"$password \""
    val netId = wifiManager.addNetwork(wifiConfig)

    if (netId != -1) {
        wifiManager.enableNetwork(netId, true)
        logView.addLog("请求已发送")
    } else {
        logView.addLog("请求发送失败（请去设置忘记此网络）")
    }
}