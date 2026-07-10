package io.github.bszapp.wifitoolbox.contract.settings

class ThemeSettings : SettingGroup("theme") {
    val colorMode = EnumStringSetting(
        key = "colorMode",
        defaultValue = "SYSTEM",
        allowedValues = setOf("SYSTEM", "LIGHT", "DARK", "MONET_SYSTEM", "MONET_LIGHT", "MONET_DARK"),
    )
    val miuixMonet = BooleanSetting("miuixMonet", false)
    val keyColor = IntSetting("keyColor", 0)
    val colorStyle = StringSetting("colorStyle", "TonalSpot")
    val colorSpec = StringSetting("colorSpec", "SPEC_2025")
    val enableBlur = BooleanSetting("enableBlur", false)
    val enableFloatingBottomBar = BooleanSetting("enableFloatingBottomBar", false)
    val enableFloatingBottomBarBlur = BooleanSetting("enableFloatingBottomBarBlur", false)
    val pageScale = FloatSetting("pageScale", 1.0f)

    override val children: List<SettingItem> = listOf(
        colorMode,
        miuixMonet,
        keyColor,
        colorStyle,
        colorSpec,
        enableBlur,
        enableFloatingBottomBar,
        enableFloatingBottomBarBlur,
        pageScale,
    )
}

class ApplicationSettings : RootSettings() {
    val theme = ThemeSettings()

    override val children: List<SettingItem> = listOf(
        theme,
    )
}
