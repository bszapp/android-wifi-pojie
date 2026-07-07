package io.github.bszapp.wifitoolbox.contract.androidapi

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AndroidApiResponse(
    val success: Boolean,
    val data: Bundle = Bundle.EMPTY,
    val errorClass: String? = null,
    val errorMessage: String? = null,
) : Parcelable {
    fun requireSuccess(): Bundle {
        if (!success) {
            throw IllegalStateException(errorMessage ?: errorClass ?: "AndroidApi 调用失败")
        }
        return data
    }

    companion object {
        fun success(data: Bundle = Bundle.EMPTY) = AndroidApiResponse(success = true, data = data)

        fun failure(error: Throwable) = AndroidApiResponse(
            success = false,
            errorClass = error::class.java.name,
            errorMessage = error.message,
        )
    }
}
