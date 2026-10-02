package io.github.bszapp.wifitoolbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import kotlinx.coroutines.launch

/** Activity 重建可复用连接的 UI 存储；服务断连立即清理全部子 ViewModel。 */
class ServiceUiSessionViewModel(app: Application) : AndroidViewModel(app) {
    private var connectionId: Long? = null
    private var store: ViewModelStore? = null

    init {
        viewModelScope.launch {
            AppControllerProvider.get().startup.state.collect { state ->
                if (state.status != StartupStatus.RUNNING ||
                    (connectionId != null && connectionId != state.connectionId)) clearSession()
            }
        }
    }

    fun storeFor(id: Long): ViewModelStore {
        if (connectionId != id) {
            clearSession()
            connectionId = id
            store = ViewModelStore()
        }
        return checkNotNull(store)
    }

    private fun clearSession() {
        store?.clear()
        store = null
        connectionId = null
    }

    override fun onCleared() { clearSession() }
}
