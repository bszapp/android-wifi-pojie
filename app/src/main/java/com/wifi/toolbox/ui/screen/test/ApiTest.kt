package com.wifi.toolbox.ui.screen.test

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.LogState
import kotlinx.coroutines.*


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
                    performWifiScan(context, logState)
                } else {
                    logState.addLog("E: 缺少位置权限，无法扫描Wi-Fi")
                }
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
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
            ) {
                logState.addLog("系统定位服务未开启，请在设置中打开")
                enableLocation(context)
                return
            }

            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val success = wifiManager.startScan()
        if (!success) {
            logState.addLog("W: 扫描频率过快，请求被系统拒绝")
        } else logState.addLog("请求已发送，3秒后获取结果")
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
            logState.addLog(
                String.format(
                    "名称: %-16s 信号强度: %-8s 支持的协议: %s",
                    it.ssid,
                    it.level,
                    it.capabilities
                )
            )
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

private fun enableLocation(context: Context) {
    try {
        //先用GooglePlayGMS弹窗
        val activity = context as Activity
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
        val settingsClient: SettingsClient = LocationServices.getSettingsClient(activity)
        val task: Task<LocationSettingsResponse> =
            settingsClient.checkLocationSettings(builder.build())

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                exception.startResolutionForResult(activity, 0x1)
            }
        }
    } catch (_: Exception) {
        //直接打开设置页
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        context.startActivity(intent)
    }
}