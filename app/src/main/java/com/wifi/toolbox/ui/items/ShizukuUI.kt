package com.wifi.toolbox.ui.items

import android.content.pm.PackageManager
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.utils.REQUEST_PERMISSION_CODE
import rikka.shizuku.Shizuku

fun CheckShizukuUI(app: MyApplication, onSuccess: () -> Unit = {}) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onSuccess()
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                app.alert("Shizuku", "当前已被始终拒绝，请手动授予")
            } else {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                Shizuku.OnRequestPermissionResultListener { code, grantResult ->
                    if (code == REQUEST_PERMISSION_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            app.alert("Shizuku", "已同意")
                            onSuccess()
                        }
                    }
                }
            }
        } catch (_: IllegalStateException) {
            try {
                app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                app.alert("Shizuku", "Shizuku服务未启动")
            } catch (_: PackageManager.NameNotFoundException) {
                app.alert("Shizuku", "还没有安装Shizuku")
            }
        }
}