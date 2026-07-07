package io.github.bszapp.wifitoolbox.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.bszapp.wifitoolbox.service.IMainService

/**
 * 进程内 Binder 入口。
 *
 * 特权服务启动后会把自己的 Binder 主动交给这里；应用进程重建时，
 * 只要旧服务仍然存活，新的 Provider 实例会重新收到 Binder，
 * 上层即可直接恢复到 RUNNING，而不需要重新申请 root/shell。
 */
class ToolboxServiceProvider : ContentProvider() {

    companion object {
        private const val TAG = "ToolboxSvcProvider"

        private val _binderFlow = MutableStateFlow<IBinder?>(null)

        val binderFlow: StateFlow<IBinder?> = _binderFlow.asStateFlow()

        internal fun storeBinderInternal(binder: IBinder?) {
            _binderFlow.value = if (binder?.isBinderAlive == true) binder else null
        }

        fun getAliveBinder(): IBinder? =
            _binderFlow.value?.takeIf { it.isBinderAlive }

        internal fun clearBinder() {
            storeBinderInternal(null)
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "Provider ready")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ServiceConfig.PROVIDER_METHOD) return null

        val callerUid = Binder.getCallingUid()
        val binder = extras?.getBinder(ServiceConfig.PROVIDER_BINDER_KEY)
        return if (binder?.isBinderAlive == true) {
            // 预连接：先让投递方看到本次投递已被处理，但只有启动信息可信时才正式保存 Binder。
            val trusted = runCatching {
                val service = IMainService.Stub.asInterface(binder)
                val info = service.getStartupInfo()
                info.trustedUid == Process.myUid() && info.trustedUid > 0
            }.onFailure {
                Log.w(TAG, "收到服务 Binder 但启动信息不可验证（uid=$callerUid）：${it.message}")
            }.getOrDefault(false)

            if (trusted) {
                storeBinderInternal(binder)
                Log.d(TAG, "收到可信服务 Binder（uid=$callerUid）")
            } else {
                Log.w(TAG, "忽略不可信服务 Binder（uid=$callerUid），不影响后续新服务启动")
            }
            Bundle().apply { putBoolean(ServiceConfig.PROVIDER_RESULT_OK, true) }
        } else {
            Log.w(TAG, "收到无效 Binder")
            null
        }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
