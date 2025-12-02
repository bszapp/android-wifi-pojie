package com.wifi.toolbox.ui.screen.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
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
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.ui.items.ActionChip
import com.wifi.toolbox.ui.items.LogState
import com.wifi.toolbox.ui.items.SectionDivider
import com.wifi.toolbox.ui.items.SectionTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun ApiTest(logState: LogState, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val wifiScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            scope.launch {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {

                }
                performWifiScan(context, logState)
            }
        } else {
            logState.addLog("E: 缺少位置权限，无法扫描Wi-Fi")
        }
    }

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
                            setWifiEnabled(context, true, logState)
                        })
                    ActionChip(
                        text = "关闭wifi",
                        icon = Icons.Filled.WifiOff,
                        onClick = {
                            setWifiEnabled(context, false, logState)
                        })
                    ActionChip(
                        text = "扫描wifi",
                        icon = Icons.Filled.Radar,
                        onClick = {
                            checkAndPerformWifiScan(context, logState, wifiScanLauncher, scope)
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
                        connectToWifiApi28(context, name, password, logState)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("WifiManager (API 28)")
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            connectToWifiApi29(context, name, password, logState)
                        } else {
                            logState.addLog("设备版本过低")
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

private fun checkAndPerformWifiScan(
    context: Context,
    logState: LogState,
    wifiScanLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    scope: CoroutineScope
) {
    when {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED -> {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                logState.addLog("系统定位服务未开启，请在设置中打开")
                return
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                logState.addLog("Wi-Fi未开启，请打开Wi-Fi")
                return
            }

            scope.launch {
                performWifiScan(context, logState)
            }
        }
        else -> {
            logState.addLog("请先允许定位权限")
            wifiScanLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}


private suspend fun performWifiScan(context: Context, logState: LogState) {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val success = wifiManager.startScan()
        if (!success) {
            logState.addLog("扫描频率过快，请求被系统拒绝")
            return
        }
        logState.addLog("请求已发送，3秒后获取结果")
        delay(3000)
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw RuntimeException("缺少位置权限")
        }
        val scanResults = wifiManager.scanResults
        val result = scanResults.sortedByDescending { it.level }.map {
            WifiInfo(
                ssid = it.SSID,
                level = it.level,
                capabilities = it.capabilities
            )
        }
        logState.addLog("=== 扫描结果 ===")
        result.forEach {
            logState.addLog(String.format("名称: %-16s 信号强度: %-8s 支持的协议: %s", it.ssid, it.level, it.capabilities))
        }
        logState.addLog("===============")

    } catch (e: Exception) {
        logState.addLog("E: 扫描wifi失败")
        logState.addLog(e.stackTraceToString())
    }
}

private fun setWifiEnabled(context: Context, enabled: Boolean, logState: LogState) {
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    try {
        val success = wifiManager.setWifiEnabled(enabled)
        if (success) {
            logState.addLog("Wi-Fi ${if (enabled) "打开" else "关闭"}成功")
        } else {
            logState.addLog("请求已发送")
        }
    } catch (e: SecurityException) {
        logState.addLog("E: 缺少权限，无法${if (enabled) "打开" else "关闭"}Wi-Fi")
        logState.addLog(e.stackTraceToString())
    }
}

@RequiresApi(api = Build.VERSION_CODES.Q)
private fun connectToWifiApi29(
    context: Context,
    ssid: String,
    password: String,
    logState: LogState
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
                logState.addLog("请求已发送")
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                logState.addLog("wifi连接失败")
                connectivityManager.unregisterNetworkCallback(this)
            }
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback)
    } catch (e: Exception) {
        logState.addLog("E: 请求发送失败")
        logState.addLog(e.stackTraceToString())
    }
}

private fun connectToWifiApi28(
    context: Context,
    ssid: String,
    password: String,
    logState: LogState
) {
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiConfig = WifiConfiguration()
    wifiConfig.SSID = "\"$ssid\""
    wifiConfig.preSharedKey = "\"$password\""
    val netId = wifiManager.addNetwork(wifiConfig)

    if (netId != -1) {
        wifiManager.enableNetwork(netId, true)
        logState.addLog("请求已发送")
    } else {
        logState.addLog("请求发送失败（请去设置忘记此网络）")
    }
}