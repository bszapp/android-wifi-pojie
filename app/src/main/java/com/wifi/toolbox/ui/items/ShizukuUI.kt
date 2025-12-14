package com.wifi.toolbox.ui.items

import android.content.pm.PackageManager
import com.wifi.toolbox.MyApplication
import rikka.shizuku.Shizuku

fun checkShizukuUI(app: MyApplication, onSuccess: () -> Unit = {}) : Boolean {
    try {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onSuccess()
            return true;
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            app.alert("Shizuku", "当前已被始终拒绝，请手动授予")
        } else {
            app.requestShizukuPermission(onSuccess)
        }
    } catch (_: IllegalStateException) {
        try {
            app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            app.alert("Shizuku", "Shizuku服务未启动")
        } catch (_: PackageManager.NameNotFoundException) {
            app.alert("Shizuku", "还没有安装Shizuku")
        }
    }
    return false
}
