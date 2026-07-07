package io.github.bszapp.wifitoolbox.contract.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

interface SettingItem {
    val key: String

    fun toJsonValue(): Any?

    fun loadFromJsonValue(value: Any?)

    fun forEachValueSetting(action: (ValueSetting<*>) -> Unit) = Unit
}

abstract class ValueSetting<T>(
    override val key: String,
    private val defaultValue: T,
) : SettingItem {
    private val _value = MutableStateFlow(defaultValue)
    val value: StateFlow<T> = _value.asStateFlow()
    val current: T get() = _value.value

    internal var onChanged: (() -> Unit)? = null

    fun set(value: T) {
        if (_value.value == value) return
        _value.value = value
        onChanged?.invoke()
    }

    protected fun setLoadedValue(value: T) {
        _value.value = value
    }

    protected fun resetToDefault() {
        _value.value = defaultValue
    }

    override fun forEachValueSetting(action: (ValueSetting<*>) -> Unit) {
        action(this)
    }
}

class BooleanSetting(
    key: String,
    private val defaultValue: Boolean,
) : ValueSetting<Boolean>(key, defaultValue) {
    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        setLoadedValue(value as? Boolean ?: defaultValue)
    }
}

class IntSetting(
    key: String,
    private val defaultValue: Int,
) : ValueSetting<Int>(key, defaultValue) {
    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        setLoadedValue((value as? Number)?.toInt() ?: defaultValue)
    }
}

class LongSetting(
    key: String,
    private val defaultValue: Long,
) : ValueSetting<Long>(key, defaultValue) {
    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        setLoadedValue((value as? Number)?.toLong() ?: defaultValue)
    }
}

class FloatSetting(
    key: String,
    private val defaultValue: Float,
) : ValueSetting<Float>(key, defaultValue) {
    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        setLoadedValue((value as? Number)?.toFloat() ?: defaultValue)
    }
}

class StringSetting(
    key: String,
    private val defaultValue: String,
) : ValueSetting<String>(key, defaultValue) {
    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        setLoadedValue(value as? String ?: defaultValue)
    }
}

class EnumStringSetting(
    key: String,
    private val defaultValue: String,
    private val allowedValues: Set<String>,
) : ValueSetting<String>(key, defaultValue) {
    init {
        require(defaultValue in allowedValues) { "默认值不在允许枚举中：$defaultValue" }
    }

    override fun toJsonValue(): Any = current

    override fun loadFromJsonValue(value: Any?) {
        val loaded = value as? String
        setLoadedValue(loaded?.takeIf { it in allowedValues } ?: defaultValue)
    }

    fun setEnum(value: String) {
        require(value in allowedValues) { "设置项 $key 不支持枚举值：$value" }
        set(value)
    }
}

abstract class SettingGroup(
    override val key: String,
) : SettingItem {
    protected abstract val children: List<SettingItem>

    override fun toJsonValue(): JSONObject = JSONObject().apply {
        children.forEach { child ->
            put(child.key, child.toJsonValue())
        }
    }

    override fun loadFromJsonValue(value: Any?) {
        val obj = value as? JSONObject
        children.forEach { child ->
            child.loadFromJsonValue(obj?.opt(child.key))
        }
    }

    override fun forEachValueSetting(action: (ValueSetting<*>) -> Unit) {
        children.forEach { child -> child.forEachValueSetting(action) }
    }
}

abstract class RootSettings : SettingGroup("root") {
    protected abstract override val children: List<SettingItem>

    fun toRootJson(lastVersion: Long): JSONObject = JSONObject().apply {
        put("lastVersion", lastVersion)
        children.forEach { child ->
            put(child.key, child.toJsonValue())
        }
    }

    fun loadRootJson(json: JSONObject?) {
        children.forEach { child ->
            child.loadFromJsonValue(json?.opt(child.key))
        }
    }

    override fun toJsonValue(): JSONObject = toRootJson(lastVersion = 0L)

    override fun loadFromJsonValue(value: Any?) {
        loadRootJson(value as? JSONObject)
    }
}
