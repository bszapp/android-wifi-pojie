package com.wifi.toolbox.structs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieConfig(
    val maxTryTime: Float = 5000f,
    val failureFlag: Int = 1,
    val timeout: Float = 2000f,
    val maxHandshakeCount: Float = 1f,
    val retryCount: Float = 0f,
    val doublingBase: Float = 0f
) : Parcelable
