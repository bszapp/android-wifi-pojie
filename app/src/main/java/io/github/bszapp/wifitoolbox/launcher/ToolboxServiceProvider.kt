package io.github.bszapp.wifitoolbox.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        if (callerUid != 0 && callerUid != 1000 && callerUid != 2000) {
            Log.w(TAG, "拒绝 uid=$callerUid 的 Binder 投递")
            return null
        }

        val binder = extras?.getBinder(ServiceConfig.PROVIDER_BINDER_KEY)
        return if (binder?.isBinderAlive == true) {
            storeBinderInternal(binder)
            Log.d(TAG, "收到服务 Binder（uid=$callerUid）")
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
