package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 在线评估与 Hashcat 共用；密码仅由用户主动展开。 */
@Composable
fun WeakPasswordResult(key: String, position: Long?, password: String,
    resultMessage: String? = null, pin: String? = null) {
    var visible by rememberSaveable(key, password, pin) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(resultMessage ?: "已在给定字典的第${position ?: "未知"}位发现密码，此密码为弱密码，建议立即更换。")
            pin?.let {
                Text("PIN：${if (visible) it else "••••••••"}", style = MiuixTheme.textStyles.subtitle)
            }
            if (pin != null) Text("网络密码", style = MiuixTheme.textStyles.body2)
            Text(if (visible) password else "••••••••", style = MiuixTheme.textStyles.subtitle)
            TextButton(if (visible) "隐藏密码" else "显示密码", onClick = { visible = !visible })
        }
    }
}
