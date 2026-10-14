package io.github.bszapp.wifitoolbox.uidefault.model

import android.content.res.AssetManager
import android.net.wifi.ScanResult
import io.github.bszapp.wifitoolbox.contract.wifilist.WpsDeviceInfo
import io.github.bszapp.wifitoolbox.contract.wifilist.wpsDeviceInfo
import java.util.Locale

data class WifiDeviceAssessment(
    val manufacturer: String?,
    val wps: WpsDeviceInfo?,
    val potentiallyVulnerable: Boolean,
) {
    val modelDisplay: String? get() = wps?.let {
        listOf(it.model, it.modelNumber).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotEmpty)
    }
    val summary: String? get() = listOfNotNull(manufacturer, modelDisplay).distinct().joinToString(" · ").takeIf(String::isNotEmpty)
}

/** 只做被动识别。IEEE MA-L/MA-M/MA-S 厂商表不参与 OneShot 型号风险匹配。 */
internal class WifiDeviceRiskCatalog(assets: AssetManager) {
    private val vulnerableModels = assets.open("vulnwsc.txt").bufferedReader(Charsets.UTF_8).use { it.readLines().toHashSet() }
    private val manufacturers = HashMap<String, String>()

    init {
        // Official IEEE tables: https://standards-oui.ieee.org/{oui,oui28,oui36}/
        for (file in listOf("mac-oui.csv", "mac-mam.csv", "mac-oui36.csv")) {
            assets.open(file).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.drop(1).forEach { line ->
                    val columns = firstThreeColumns(line)
                    if (columns.size == 3 && assignmentPattern.matches(columns[1])) {
                        manufacturers[columns[1].uppercase(Locale.ROOT)] = columns[2]
                    }
                }
            }
        }
    }

    fun assess(result: ScanResult): WifiDeviceAssessment {
        val wps = result.wpsDeviceInfo()
        // Exactly reproduce OneShot: '{} {}'.format(Model, Model number) in vuln_list.
        // No trimming, case folding, substring match or vendor-based inference.
        val vulnerable = wps != null && "${wps.model} ${wps.modelNumber}" in vulnerableModels
        return WifiDeviceAssessment(manufacturer(result.BSSID), wps, vulnerable)
    }

    /** 仅抓包发现或当前连接补出的接入点，也按其 MAC 查询 IEEE 厂商。 */
    fun assess(bssid: String): WifiDeviceAssessment =
        WifiDeviceAssessment(manufacturer(bssid), wps = null, potentiallyVulnerable = false)

    private fun manufacturer(bssid: String?): String? {
        val mac = bssid?.replace(":", "")?.uppercase(Locale.ROOT) ?: return null
        if (!macPattern.matches(mac) || mac == "000000000000" || (mac.substring(0, 2).toInt(16) and 3) != 0) return null
        for (length in listOf(9, 7, 6)) manufacturers[mac.take(length)]?.takeIf(String::isNotBlank)?.let { return it }
        return null
    }

    /** CSV quote handling; addresses after column three need not be retained in memory. */
    private fun firstThreeColumns(line: String): List<String> {
        val columns = ArrayList<String>(3)
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index++]
            when {
                character == '"' -> {
                    if (quoted && index < line.length && line[index] == '"') { value.append('"'); index++ }
                    else quoted = !quoted
                }
                character == ',' && !quoted -> {
                    columns += value.toString(); value.setLength(0)
                    if (columns.size == 3) return columns
                }
                else -> value.append(character)
            }
        }
        if (columns.size < 3) columns += value.toString()
        return columns
    }

    private companion object {
        val assignmentPattern = Regex("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{7}|[0-9A-Fa-f]{9}")
        val macPattern = Regex("[0-9A-F]{12}")
    }
}
