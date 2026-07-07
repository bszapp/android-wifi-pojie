package io.github.bszapp.wifitoolbox.contract.androidapi

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AndroidApiRequest(
    val action: String,
    val arguments: Bundle = Bundle.EMPTY,
) : Parcelable
