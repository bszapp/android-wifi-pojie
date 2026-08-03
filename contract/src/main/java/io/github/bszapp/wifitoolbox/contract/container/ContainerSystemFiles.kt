package io.github.bszapp.wifitoolbox.contract.container

import android.system.Os
import android.system.OsConstants
import java.io.File

fun isContainerSystemInstalled(rootfs: File): Boolean {
    val shell = File(rootfs, "bin/sh")
    return runCatching {
        val type = Os.lstat(shell.absolutePath).st_mode and OsConstants.S_IFMT
        type == OsConstants.S_IFREG || type == OsConstants.S_IFLNK
    }.getOrDefault(false)
}
