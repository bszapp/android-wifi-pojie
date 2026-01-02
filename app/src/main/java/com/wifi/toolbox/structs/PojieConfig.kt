package com.wifi.toolbox.structs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieConfig(
    val maxTryTime: Int = 5000,
    val failureFlag: Int = 2,
    val timeout: Int = 2000,
    val maxHandshakeCount: Int = 1,
    val retryCountType: Int = 6,
    val doublingBase: Int = 0
) : Parcelable
