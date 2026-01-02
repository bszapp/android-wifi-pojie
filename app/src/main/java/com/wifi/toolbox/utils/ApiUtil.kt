package com.wifi.toolbox.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task

@Suppress("DEPRECATION") //targetSdk = 28 不用理会警告
object ApiUtil {

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            wifiManager.setWifiEnabled(enabled)
        } catch (_: SecurityException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectToWifiApi29(
        context: Context,
        ssid: String,
        password: String,
        onStatus: (Boolean) -> Unit = {}
    ) {
        Log.d("ApiUtil", "connectToWifiApi29 ssid: $ssid, password: $password")
        try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (password.isNotEmpty()) {
                builder.setWpa2Passphrase(password)
            }
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()

            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onStatus(true)
                    connectivityManager.unregisterNetworkCallback(this)
                }

                override fun onUnavailable() {
                    onStatus(false)
                    connectivityManager.unregisterNetworkCallback(this)
                }
            }
            connectivityManager.requestNetwork(networkRequest, networkCallback)
        } catch (_: Exception) {
            onStatus(false)
        }
    }

    fun connectToWifiApi28(context: Context, ssid: String, password: String): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
        }
        val netId = wifiManager.addNetwork(wifiConfig)
        return if (netId != -1) {
            wifiManager.enableNetwork(netId, true)
            true
        } else false
    }

    fun enableLocation(context: Context): Boolean {
        return if (!isLocationEnabled(context)) {
            try {
                val activity = context as Activity
                val locationRequest =
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
                val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
                    .setAlwaysShow(true)
                val client = LocationServices.getSettingsClient(activity)
                val task: Task<LocationSettingsResponse> =
                    client.checkLocationSettings(builder.build())

                task.addOnFailureListener { exception ->
                    if (exception is ResolvableApiException) {
                        exception.startResolutionForResult(activity, 0x1)
                    }
                }
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                context.startActivity(intent)
            }
            false
        } else true
    }

    fun startScan(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.startScan()
    }

    @SuppressLint("MissingPermission")
    fun getScanResults(context: Context): List<com.wifi.toolbox.structs.WifiInfo> {
        if (hasLocationPermission(context)) {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifiManager.scanResults.map {
                com.wifi.toolbox.structs.WifiInfo(
                    ssid = it.SSID,
                    level = it.level,
                    capabilities = it.capabilities
                )
            }.sortedByDescending { it.level }
        }
        return emptyList()
    }

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)

        return capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    const val REQUEST_LOCATION_CODE = 1001
    fun requestLocationPermission(activity: Activity): Boolean {
        return if (!hasLocationPermission(activity)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_CODE
            )
            false
        } else true
    }
}