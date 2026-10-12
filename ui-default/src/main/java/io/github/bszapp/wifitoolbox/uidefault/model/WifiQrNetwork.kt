package io.github.bszapp.wifitoolbox.uidefault.model

import com.google.zxing.Result
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult

data class WifiQrNetwork(val ssid: String, val password: String) {
    companion object {
        fun parse(result: Result): WifiQrNetwork? {
            val network = ResultParser.parseResult(result) as? WifiParsedResult ?: return null
            if (network.ssid.isNullOrEmpty()) return null
            return WifiQrNetwork(network.ssid, network.password.orEmpty())
        }
    }
}
