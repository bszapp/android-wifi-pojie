package io.github.bszapp.wifitoolbox.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次测试连通性配置会话。
 *
 * [close] 负责结束连接并删除测试配置。
 */
internal class TemporaryWifiNetworkRequest internal constructor(
    val networkId: Int,
    val removedExistingConfiguration: Boolean,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}
