package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 单个弱密码检测顶层任务内部的窗口调度器；单次连接由弱密码检测专用执行器完成。
 *
 * 调度线程只负责领号、开窗与收尾，[entries] 的读写也只发生在它身上；真正阻塞在连接上的
 * 工作交给工作线程，结果经 [outcomes] 回流。这样两种模式共用同一套状态机：
 *
 * - 系统API使用唯一的连接焦点名额。
 * - 网卡方式只使用选中的可用网卡，空闲窗口按最久未尝试
 *   优先领一个排队记录；一张网卡一次只跑一个密码，做完一次尝试（成功/错误/异常）就回到
 *   队尾重新排号，窗口随即交还给下一个任务。
 *
 * 共享字典池按领取法运转：成员手头空了就向池子申领一个互不相同的候选并占住它，
 * 得到确定结果才交还，重试冷却期间保持归属。
 */
internal class PskSecurityAuditDispatcher(
    initialConfig: PskSecurityAuditConfig,
    private val worker: PskSecurityAuditConnectWorker,
    /** 可作为普通 Wi-Fi 客户端的网卡；多线程模式下作为可分配的窗口。 */
    private val interfaces: () -> List<String> = { emptyList() },
) {
    @Volatile private var config = initialConfig
    private val updates = LinkedBlockingQueue<Command>()
    private val entries = mutableListOf<Entry>()
    /** 工作线程写、调度线程读；进度只搬数据，不直接改记录。 */
    private val stages = LinkedBlockingQueue<StageUpdate>()
    private val outcomes = LinkedBlockingQueue<Outcome>()
    /** 统计键由 SSID 和候选池 ID 组成；未共享时以记录 ID 作池 ID。 */
    private val groupStats = mutableMapOf<Pair<String, String>, AuditAttemptStats>()
    private val cooldownLog = AuditCooldownLog()
    /** recordId -> 正占用某张网卡的尝试。 */
    private val running = mutableMapOf<String, Job>()
    private var historyRevision = 0L
    private var historyUpdatedAt = 0L
    @Volatile private var accepting = true

    private var cachedInterfaces: List<String> = emptyList()
    private var interfaceScannedAt = 0L

    fun isAccepting(): Boolean = synchronized(updates) { accepting }

    fun currentConfig(): PskSecurityAuditConfig = config

    fun submitGroup(input: PskSecurityAuditNetworkInput, passwords: List<String>,
        count: Int, context: TaskContext): Boolean =
        synchronized(updates) {
            if (!accepting) return false
            require(count > 0)
            updates.offer(Command.AddGroup(input, passwords, count, context))
            return true
        }

    fun joinGroup(input: PskSecurityAuditNetworkInput, count: Int, context: TaskContext): Boolean =
        synchronized(updates) {
            if (!accepting) return false
            require(count > 0)
            updates.offer(Command.JoinGroup(input, count, context))
            true
        }

    fun stopRecord(recordId: String): Boolean = synchronized(updates) {
        if (!accepting) return false
        updates.offer(Command.StopRecord(recordId))
        true
    }

    fun stopNetwork(ssid: String): Boolean = synchronized(updates) {
        if (!accepting) return false
        updates.offer(Command.StopNetwork(ssid))
        true
    }

    fun stopGroup(ssid: String, groupId: String): Boolean = synchronized(updates) {
        if (!accepting) return false
        updates.offer(Command.StopGroup(ssid, groupId))
        true
    }

    fun stopAll(): Boolean = synchronized(updates) {
        if (!accepting) return false
        accepting = false
        updates.offer(Command.StopAll)
        true
    }

    fun configure(value: PskSecurityAuditConfig): Boolean = synchronized(updates) {
        if (!accepting) return false
        updates.offer(Command.Configure(value))
        true
    }

    fun run() {
        try {
            while (true) {
                try {
                    drainUpdates()
                    drainStages()
                    reap()
                    closeUnusedNetworkCardSessions()
                    retireExhaustedPools()
                    dispatch()
                    retireExhaustedPools()
                    val active = entries.filter { it.record.active }
                    if (active.isEmpty() && running.isEmpty()) {
                        synchronized(updates) {
                            if (updates.isNotEmpty()) return@synchronized
                            accepting = false
                        }
                        if (accepting) continue
                        return
                    }
                    cooldownLog.update(active, running.isEmpty() &&
                        active.all { it.record.state == PskSecurityAuditNetworkState.COOLDOWN }) { text ->
                        active.firstOrNull()?.context?.logAuditResult(text)
                    }
                    // 各个改状态的地方都自带 publish，这里不再重复广播。
                    if (running.isNotEmpty()) {
                        // 有窗口在跑就短等，保证停止/配置变更与进度能及时被处理。
                        outcomes.poll(POLL_WITH_RUNNING_MS, TimeUnit.MILLISECONDS)?.let(::finish)
                    } else {
                        // 共享池被占满的成员此刻领不到候选，拿它算等待会算成 0 并空转；
                        // 只把“手里有候选”或“池子还能发号”的成员纳入等待计算。
                        val schedulable = active.filter { entry ->
                            val pool = entry.pool
                            pool == null || entry.heldIndex != null || pool.claimable()
                        }
                        val wait = if (schedulable.isEmpty() ||
                            config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD && freeWindows().isEmpty()) IDLE_POLL_MS
                        else PskSecurityAuditQueue.waitMillis(schedulable).coerceIn(1L, 2000L)
                        updates.poll(wait, TimeUnit.MILLISECONDS)?.let(::applyUpdate)
                    }
                } catch (stop: InterruptedException) {
                    throw stop
                } catch (error: ConnectionCleanupException) {
                    entries.filter { it.record.active }.forEach {
                        it.context.log("上一轮断开未确认，停止提交后续连接：${error.cause?.message ?: error.message}")
                    }
                    throw error
                } catch (error: Exception) {
                    entries.filter { it.record.active }.forEach {
                        it.context.log("本轮异常，保留当前位置继续：${error.message ?: error.javaClass.name}")
                    }
                    Thread.sleep(100)
                }
            }
        } finally {
            synchronized(updates) { accepting = false }
            drainRunning()
            entries.lastOrNull()?.context?.let { context ->
                runCatching { worker.closeNetworkCardSession(context) }
                    .onFailure { context.log("关闭持久网卡脚本会话失败：${it.message ?: it.javaClass.name}") }
            }
            entries.filter { it.record.active }.forEach {
                it.record = it.record.copy(state = PskSecurityAuditNetworkState.STOPPED,
                    stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(),
                    window = null, attemptPassword = null, message = "已停止")
            }
            entries.filter { it.record.state != PskSecurityAuditNetworkState.SUCCEEDED }.forEach {
                forgetNetwork(it, it.context)
            }
            publish()
        }
    }

    // ------------------------------------------------------------------ 调度

    /** 当前没被占用的窗口；系统 API 的窗口是连接焦点名额，网卡方式是接口名。 */
    private fun freeWindows(): List<String> {
        val all = when {
            config.connectionMethod == PskSecurityAuditConnectionMethod.SYSTEM -> listOf(SYSTEM_CONNECTION_FOCUS)
            else -> if (config.selectedInterfaces.isEmpty()) emptyList()
                else enumerateInterfaces().filter { it in config.selectedInterfaces }
        }
        val used = running.values.mapTo(mutableSetOf()) { it.window }
        return all.filterNot { it in used }
    }

    /**
     * 枚举可作为普通 Wi-Fi 客户端的网卡：p2p*、没有 phy80211 的、以及非 managed
     * 类型（如 monitor）的接口都会被 [WirelessInterfaceNetlink.managedInterfaceNames] 排除。
     * 每隔一小段时间才重新探测，避免每个调度轮次都打一次 nl80211。
     */
    private fun enumerateInterfaces(): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedInterfaces.isEmpty() || now - interfaceScannedAt >= INTERFACE_SCAN_INTERVAL_MS) {
            interfaceScannedAt = now
            cachedInterfaces = interfaces().filter { it.matches(INTERFACE_NAME) }.distinct()
        }
        return cachedInterfaces
    }

    /** 把空闲窗口交给排队记录：最久未尝试的先上，领不到候选的（共享池被占满）跳过。 */
    private fun dispatch() {
        if (running.values.any { it.config.connectionMethod == PskSecurityAuditConnectionMethod.SYSTEM } ||
            config.connectionMethod == PskSecurityAuditConnectionMethod.SYSTEM && running.isNotEmpty()) return
        val free = freeWindows()
        if (free.isEmpty()) return
        val waiting = entries.filter { it.record.active && !running.containsKey(it.record.id) }
            .sortedBy { it.lastAttemptAt }
        if (waiting.isEmpty()) return
        for (window in free) {
            waiting.firstOrNull { entry ->
                entry.record.active && !running.containsKey(entry.record.id) &&
                    entry.readyAt <= SystemClock.elapsedRealtime() && start(entry, window)
            } ?: break
        }
    }

    /** 取消选择后先等本轮清理完毕，再关闭持久会话；wlan0会话关闭时恢复系统supplicant。 */
    private fun closeUnusedNetworkCardSessions() {
        val context = entries.firstOrNull()?.context ?: return
        val keep = if (config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD)
            config.selectedInterfaces.toMutableSet() else mutableSetOf<String>()
        running.values.filter { it.config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD }
            .forEach { keep.add(it.window) }
        worker.closeUnusedNetworkCardSessions(keep, context)
    }

    /**
     * 让一个记录占用 [window] 跑一次密码尝试；返回 false 表示这次没跑起来（共享池领不到
     * 候选或字典已尽），调用方应把窗口让给下一个候选。
     */
    private fun start(entry: Entry, window: String): Boolean {
        if (running.containsKey(entry.record.id)) return false
        val pool = entry.pool
        val position = if (pool == null) entry.record.completed
        else entry.heldIndex ?: pool.claim(entry.record.id) ?: return false
        entry.heldIndex = if (pool == null) null else position
        val password = entry.passwords.getOrNull(position)
        if (password == null) {
            exhaust(entry)
            publish()
            return false
        }
        val token = ++entry.attemptToken
        val selectedConfig = config
        entry.lastAttemptAt = SystemClock.elapsedRealtime()
        val started = entry.lastAttemptAt
        // 网卡方式的随机策略在本轮连接前决定并保持；返回 null 即使用设备原 MAC。
        val mac = selectMac(entry, selectedConfig)
        val child = entry.context.forAttempt(entry.record.input.ssid, { progress ->
            // 工作线程只投递数据，记录改写统一回到调度线程，避免并发写同一条记录。
            if (progress is TaskProgress.ConnectWifi) stages.offer(StageUpdate(entry.record.id, token, progress.stage))
        }, { if (token != entry.attemptToken) throw AttemptChanged() }, { config })
        val job = Job(entry.record.id, window, entry.record.input.ssid, password, mac,
            selectedConfig, child, started, position)
        running[entry.record.id] = job
        val previousAttemptIndex = entry.record.lastAttemptIndex
        try {
            entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.CONNECTING, config = selectedConfig,
                attempts = entry.record.attempts + 1, nextAttemptAt = null,
                updatedAt = System.currentTimeMillis(),
                window = window, attemptPassword = password, lastAttemptIndex = position,
                message = if (mac != null) "正在尝试：$password\nMAC：$mac" else "正在尝试：$password",
                stage = ConnectWifiStage.ROUTER_COMMUNICATION)
            entry.context.log("[${entry.record.input.ssid}] 尝试字典第 ${position + 1}/${entry.passwords.size} 位" +
                " 窗口=$window" + (mac?.let { " mac=$it" } ?: ""))
            publish()
            Thread({ runJob(job, entry) }, "psk-security-audit-$window").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            // 开窗失败必须原样交还候选与窗口，否则共享池会永远少一个候选。
            running.remove(entry.record.id)
            entry.heldIndex?.let { pool?.release(it) }
            entry.heldIndex = null
            entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.QUEUED,
                stage = null, window = null, attemptPassword = null,
                attempts = entry.record.attempts - 1, lastAttemptIndex = previousAttemptIndex, message = "排队中",
                updatedAt = System.currentTimeMillis())
            throw error
        }
        return true
    }

    /** 工作线程：只跑阻塞的连接与清理，全程不改写 [entries] 里的记录字段。 */
    private fun runJob(job: Job, entry: Entry) {
        var result = ConnectionAttemptResult.ERROR
        var duration = 0L
        try {
            try {
                result = worker.performTaskLogic(job.ssid, job.password, job.mac, job.config,
                    job.window, job.child) {
                    // 该标记是给调度线程的 forgetNetwork 用的，因此必须写回记录本身。
                    entry.systemConfiguration = true
                    entry.configurationCleanupAttempted = false
                }
            } finally {
                // 旧版先记录连接耗时，再清理连接；清理不计入密码尝试耗时。
                duration = SystemClock.elapsedRealtime() - job.startedAt
                try { worker.cleanConnection(job.config.connectionMethod, job.window, job.child) }
                catch (error: Exception) { throw ConnectionCleanupException(error) }
            }
            outcomes.offer(Outcome.Finished(job.recordId, job.window, result, duration))
        } catch (_: AttemptChanged) {
            Thread.interrupted()
            outcomes.offer(Outcome.Aborted(job.recordId, job.window))
        } catch (_: InterruptedException) {
            // 任务停止：交还窗口，收尾由调度线程统一处理。
            outcomes.offer(Outcome.Aborted(job.recordId, job.window))
        } catch (error: ConnectionCleanupException) {
            outcomes.offer(Outcome.CleanupFailed(job.recordId, job.window, error))
        } catch (error: Exception) {
            entry.context.log("[${job.ssid}] 执行异常：${error.message ?: error.javaClass.name}")
            outcomes.offer(Outcome.Finished(job.recordId, job.window, ConnectionAttemptResult.ERROR, duration,
                error.message ?: error.javaClass.name))
        }
    }

    private fun reap() {
        while (true) finish(outcomes.poll() ?: break)
    }

    private fun drainStages() {
        var changed = false
        while (true) {
            val update = stages.poll() ?: break
            entries.firstOrNull { it.record.id == update.recordId }?.let { entry ->
                if (entry.attemptToken == update.token && running.containsKey(update.recordId) &&
                    entry.record.state == PskSecurityAuditNetworkState.CONNECTING) {
                    entry.record = entry.record.copy(stage = update.stage, updatedAt = System.currentTimeMillis())
                    changed = true
                }
            }
        }
        if (changed) publish()
    }

    /** 共享池再没有可领取的候选时，把仍然排队的成员整体收尾。 */
    private fun retireExhaustedPools() {
        var retired = false
        while (true) {
            val victim = entries.firstOrNull { it.record.active && it.pool?.exhausted() == true } ?: break
            exhaust(victim)
            retired = true
        }
        if (retired) publish()
    }

    // ------------------------------------------------------------------ 收尾

    private fun finish(outcome: Outcome) {
        val job = running.remove(outcome.recordId) ?: return
        val entry = entries.firstOrNull { it.record.id == outcome.recordId } ?: return
        val context = entry.context
        logAttemptOutcome(job, outcome, context)
        // 窗口已经交还：受理者与候选密码一并撤下，避免界面/通知继续显示上一轮的现场。
        if (entry.record.window != null || entry.record.attemptPassword != null) {
            entry.record = entry.record.copy(window = null, attemptPassword = null,
                updatedAt = System.currentTimeMillis())
        }
        if (outcome is Outcome.CleanupFailed) {
            entry.heldIndex?.let { entry.pool?.release(it) }
            entry.heldIndex = null
            entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.ERROR, stage = null,
                updatedAt = System.currentTimeMillis(), message = "上一轮断开未确认，已停止继续连接")
            publish()
            throw outcome.cause
        }
        if (!entry.record.active) {
            // 记录已被停止或被共享池的成功结论收走：本轮结果作废，只交还候选与窗口。
            entry.heldIndex?.let { entry.pool?.release(it) }
            entry.heldIndex = null
            forgetNetwork(entry, context)
            publish()
            return
        }
        when (outcome) {
            is Outcome.Aborted -> {
                // 配置或共享关系变化，本轮作废但候选与位置都保留，稍后重新排号。
                entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.QUEUED, stage = null,
                    nextAttemptAt = null, message = "排队中", attempts = entry.record.attempts - 1)
                publish()
            }
            is Outcome.Finished -> finishAttempt(entry, job, outcome, context)
            is Outcome.CleanupFailed -> Unit
        }
    }

    /** 仅在占用窗口的一次尝试已结束并回收窗口后输出一条精简记录。 */
    private fun logAttemptOutcome(job: Job, outcome: Outcome, context: TaskContext) {
        val errorText = when (outcome) {
            is Outcome.Aborted -> return
            is Outcome.CleanupFailed -> outcome.cause.cause?.message ?: outcome.cause.message ?: "连接清理异常"
            is Outcome.Finished -> if (outcome.result == ConnectionAttemptResult.ERROR)
                outcome.errorMessage ?: job.child.attemptError ?: "连接执行异常，未返回错误详情"
                else null
        }
        val ssid = job.ssid.replace("\r", "\\r").replace("\n", "\\n")
        val interfacePrefix = if (job.config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD)
            "${job.window}:" else ""
        if (errorText != null) {
            context.logAuditResult("[$interfacePrefix$ssid] E:${errorText.replace('\r', ' ').replace('\n', ' ')}")
            return
        }
        val finished = outcome as Outcome.Finished
        val result = when (finished.result) {
            ConnectionAttemptResult.SUCCESS -> "成功"
            ConnectionAttemptResult.FAILED -> "失败(${finished.duration}ms)"
            ConnectionAttemptResult.TIMEOUT -> "超时"
            ConnectionAttemptResult.REJECTED -> "路由器拒绝接入"
            ConnectionAttemptResult.ERROR -> error("异常结果应输出异常日志")
        }
        val password = job.password.replace("\r", "\\r").replace("\n", "\\n")
        context.logAuditResult("$interfacePrefix($ssid,$password):$result")
    }

    private fun finishAttempt(entry: Entry, job: Job, outcome: Outcome.Finished, context: TaskContext) {
        val result = outcome.result
        val duration = outcome.duration
        val position = job.position
        val stats = groupStats.getOrPut(groupKey(entry)) { AuditAttemptStats() }
        // 旧版语义：只有明确的密码错误结论成为新耗时样本；瞬态轮并入上一样本。
        when (result) {
            ConnectionAttemptResult.FAILED -> stats.recordConclusive(duration)
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED,
            ConnectionAttemptResult.ERROR,
            -> stats.recordTransient(duration)
            ConnectionAttemptResult.SUCCESS -> Unit
        }
        entry.record = entry.record.copy(stage = null, updatedAt = System.currentTimeMillis(),
            groupElapsedMillis = stats.elapsedMillis,
            groupAverageAttemptMillis = stats.averageAttemptMillis)
        context.log("[${entry.record.input.ssid}] 本次结果=$result，耗时=${duration}ms")
        when (result) {
            ConnectionAttemptResult.SUCCESS -> {
                entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.SUCCEEDED,
                    password = job.password, dictionaryPosition = position + 1,
                    completed = position + 1, message = "发现弱密码，建议立即更换")
                entry.heldIndex = null
                entry.pool?.let { pool ->
                    pool.resolved(position + 1)
                    entries.filter { it.pool === pool }.forEach { member ->
                        member.record = member.record.copy(completed = pool.completed,
                            poolCompleted = pool.completed, updatedAt = System.currentTimeMillis())
                        if (member !== entry && member.record.active) {
                            // 多线程下同组成员可能正占着别的网卡在跑，一并撤销。
                            member.attemptToken++
                            member.record = member.record.copy(state = PskSecurityAuditNetworkState.STOPPED,
                                stage = null, nextAttemptAt = null, message = "共享候选池已找到密码")
                            forgetNetwork(member, member.context)
                        }
                    }
                }
                context.log("[${entry.record.input.ssid}] 已在字典第 ${entry.record.dictionaryPosition} 位发现弱密码")
            }
            ConnectionAttemptResult.FAILED -> advance(entry)
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED -> {
                val count = entry.record.retryCount + 1
                if (PskSecurityAuditQueue.retryExhausted(config.retryCount, count)) advance(entry)
                else {
                    val delay = cooldownMillis(count)
                    // 时间使用单调钟；仅用于展示的下次时间转换成墙上时间。
                    entry.readyAt = PskSecurityAuditQueue.readyAt(entry.lastAttemptAt, delay)
                    entry.record = entry.record.copy(retryCount = count,
                        state = if (delay > 0) PskSecurityAuditNetworkState.COOLDOWN else PskSecurityAuditNetworkState.QUEUED,
                        message = "${if (result == ConnectionAttemptResult.TIMEOUT) "超时" else worker.rejectionDescription ?: "路由器拒绝接入"}，第 $count 次重试",
                        nextAttemptAt = PskSecurityAuditQueue.displayTime(entry.readyAt))
                }
            }
            ConnectionAttemptResult.ERROR -> {
                // 结果未知但候选交还，让同池其他成员接着试它。
                entry.heldIndex?.let { entry.pool?.release(it) }
                entry.heldIndex = null
                entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.ERROR,
                    message = "连接执行异常，请查看任务日志")
            }
        }
        if (!entry.record.active && entry.record.state != PskSecurityAuditNetworkState.SUCCEEDED) {
            forgetNetwork(entry, context)
        }
        entry.pool?.let { pool ->
            entries.filter { it.pool === pool && !it.record.active && it.record.state != PskSecurityAuditNetworkState.SUCCEEDED }
                .forEach { forgetNetwork(it, it.context) }
        }
        // 一次尝试完成后，公共候选池的每个 SSID 都收到相同的历史事件。
        historyRevision++
        historyUpdatedAt = maxOf(System.currentTimeMillis(), historyUpdatedAt + 1)
        (entry.pool?.let { pool -> entries.filter { it.pool === pool } } ?: listOf(entry)).forEach { member ->
            member.record = member.record.copy(historyRevision = historyRevision,
                historyUpdatedAt = historyUpdatedAt)
        }
        publish()
    }

    /** 任务结束前等所有窗口交还；停止指令会让工作线程在一次轮询内主动退出。 */
    private fun drainRunning() {
        val interrupted = Thread.interrupted()
        val deadline = SystemClock.elapsedRealtime() + SHUTDOWN_DEADLINE_MS
        try {
            while (running.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                val outcome = try { outcomes.poll(WINDOW_DRAIN_POLL_MS, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { null }
                if (outcome != null) {
                    try { finish(outcome) }
                    catch (error: Exception) {
                        entries.firstOrNull { it.record.id == outcome.recordId }?.context
                            ?.log("收尾窗口未确认断开：${error.message ?: error.javaClass.name}")
                    }
                }
            }
            if (running.isNotEmpty()) {
                running.keys.forEach { id -> entries.firstOrNull { it.record.id == id }?.context
                    ?.log("等待窗口交还超时，直接关闭脚本会话") }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun advance(entry: Entry) {
        val pool = entry.pool
        if (pool == null) {
            val completed = entry.record.completed + 1
            entry.readyAt = 0
            entry.record = entry.record.copy(completed = completed, retryCount = 0, nextAttemptAt = null,
                state = if (completed >= entry.passwords.size) PskSecurityAuditNetworkState.EXHAUSTED
                else PskSecurityAuditNetworkState.QUEUED,
                message = if (completed >= entry.passwords.size) "给定字典未发现密码" else "排队中")
            return
        }
        // 共享池：交还手头这个候选，公共进度前进一位，其他成员可以接着领。
        entry.heldIndex?.let(pool::consume)
        entry.heldIndex = null
        val completed = pool.completed
        val now = System.currentTimeMillis()
        entries.filter { it.pool === pool }.forEach { member ->
            if (member === entry) {
                member.readyAt = 0
                member.record = member.record.copy(completed = completed, poolCompleted = completed,
                    retryCount = 0, nextAttemptAt = null, state = PskSecurityAuditNetworkState.QUEUED,
                    updatedAt = now, message = "排队中")
            } else {
                // 公共游标前进不影响其他任务手中候选、重试次数或冷却时间。
                member.record = member.record.copy(completed = completed, poolCompleted = completed,
                    updatedAt = now)
            }
        }
    }

    private fun exhaust(entry: Entry) {
        val members = entry.pool?.let { pool -> entries.filter { it.pool === pool } } ?: listOf(entry)
        members.filter { it.record.active }.forEach { member ->
            member.heldIndex = null
            member.record = member.record.copy(state = PskSecurityAuditNetworkState.EXHAUSTED,
                stage = null, message = "给定字典未发现密码")
            forgetNetwork(member, member.context)
        }
    }

    private fun drainUpdates() {
        while (true) applyUpdate(updates.poll() ?: break)
    }

    private fun applyUpdate(update: Command) {
        when (update) {
            is Command.AddGroup -> {
                val source = add(update.network, update.passwords, update.context)
                source.context.log("[${source.record.input.ssid}] 已加入候号队列，候选字典固定")
                repeat(update.count - 1) {
                    val joined = sharePool(source, update.network, update.context)
                    joined.context.log("[${joined.record.input.ssid}] 已加入共享候选池 ${joined.pool?.id}")
                }
            }
            is Command.JoinGroup -> {
                val source = entries.firstOrNull { it.record.id == update.network.sharedWithRecordId && it.record.active }
                if (source == null) {
                    update.context.log("[${update.network.ssid}] 共享来源测试记录不存在，加入失败")
                } else {
                    repeat(update.count) {
                        val joined = sharePool(source, update.network, update.context)
                        joined.context.log("[${joined.record.input.ssid}] 已加入共享候选池 ${joined.pool?.id}")
                    }
                }
            }
            is Command.StopRecord -> {
                val entry = entries.firstOrNull { it.record.id == update.recordId && it.record.active }
                if (entry != null) stopEntry(entry, "用户停止此测试")
            }
            is Command.StopNetwork -> entries.filter { it.record.active && it.record.ssid == update.ssid }
                .forEach { stopEntry(it, "用户停止此网络的测试") }
            is Command.StopGroup -> entries.filter {
                it.record.active && it.record.ssid == update.ssid &&
                    (it.record.groupId ?: it.record.id) == update.groupId
            }.forEach { stopEntry(it, "用户停止此候选池的测试") }
            Command.StopAll -> entries.filter { it.record.active }
                .forEach { stopEntry(it, "用户停止弱密码检测") }
            is Command.Configure -> {
                config = update.config
                running.values.filter { job ->
                    job.config.connectionMethod != config.connectionMethod ||
                        job.config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD &&
                        job.window !in config.selectedInterfaces
                }.forEach { job -> entries.firstOrNull { it.record.id == job.recordId }?.let { it.attemptToken++ } }
                entries.filter { it.record.active }.forEach {
                    it.record = it.record.copy(config = config)
                    if (it.record.retryCount > 0 && !running.containsKey(it.record.id)) {
                        it.readyAt = PskSecurityAuditQueue.readyAt(it.lastAttemptAt, cooldownMillis(it.record.retryCount))
                        it.record = it.record.copy(nextAttemptAt = PskSecurityAuditQueue.displayTime(it.readyAt),
                            state = if (it.readyAt > SystemClock.elapsedRealtime()) PskSecurityAuditNetworkState.COOLDOWN else PskSecurityAuditNetworkState.QUEUED)
                    }
                    it.context.log("弱密码检测运行配置已更新")
                }
            }
        }
        publish()
    }

    private fun stopEntry(entry: Entry, reason: String) {
        if (running.containsKey(entry.record.id)) entry.attemptToken++
        else {
            entry.heldIndex?.let { entry.pool?.release(it) }
            entry.heldIndex = null
        }
        entry.record = entry.record.copy(state = PskSecurityAuditNetworkState.STOPPED,
            stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(),
            window = null, attemptPassword = null, message = reason)
        if (!running.containsKey(entry.record.id)) forgetNetwork(entry, entry.context)
        entry.context.log("[${entry.record.ssid}] $reason")
    }

    private fun add(input: PskSecurityAuditNetworkInput, passwords: List<String>, context: TaskContext): Entry {
        val now = System.currentTimeMillis()
        val source = Entry(context, AuditRecord.Dictionary(UUID.randomUUID().toString(), input, config,
            now, now, PskSecurityAuditNetworkState.QUEUED, passwords.size,
            completed = input.startIndex.coerceIn(0, passwords.size)), passwords)
        entries += source
        return source
    }

    /**
     * 把一条新记录接进 [source] 的候选池；首次调用会把来源记录升级为共享池模式。
     * 正在跑的来源本轮作废，保证它仍占着的候选能被正确搬进新池。
     */
    private fun sharePool(source: Entry, network: PskSecurityAuditNetworkInput, context: TaskContext): Entry {
        if (running.containsKey(source.record.id)) source.attemptToken++
        val held = running[source.record.id]?.position ?: source.heldIndex
        val pool = source.pool ?: Pool(source.record.id, source.passwords,
            startCursor = maxOf(source.record.completed, (held ?: -1) + 1),
            initialCompleted = source.record.completed).also {
                source.pool = it
                source.heldIndex = held
                source.record = source.record.copy(groupId = it.id, poolCompleted = it.completed)
                if (held != null) it.owners[held] = source.record.id
            }
        val now = System.currentTimeMillis()
        val joined = Entry(context, AuditRecord.Dictionary(UUID.randomUUID().toString(), network, config, now, now,
            if (source.record.active) PskSecurityAuditNetworkState.QUEUED else PskSecurityAuditNetworkState.STOPPED,
            pool.passwords.size, completed = pool.completed,
            message = if (source.record.active) "排队中" else "共享来源记录已结束",
            groupId = pool.id, poolCompleted = pool.completed), pool.passwords, pool = pool)
        entries += joined
        return joined
    }

    private fun publish() {
        entries.forEach { entry ->
            if (!entry.record.active) return@forEach
            val stats = groupStats[groupKey(entry)] ?: return@forEach
            if (entry.record.groupElapsedMillis != stats.elapsedMillis ||
                entry.record.groupAverageAttemptMillis != stats.averageAttemptMillis) {
                entry.record = entry.record.copy(groupElapsedMillis = stats.elapsedMillis,
                    groupAverageAttemptMillis = stats.averageAttemptMillis,
                    updatedAt = System.currentTimeMillis())
            }
        }
        // 所有内部测试记录归属同一个顶层任务；窗口清单是该调度器的资源视图。
        val windows = if (config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD)
            enumerateInterfaces().filter { it in config.selectedInterfaces } else emptyList()
        entries.firstOrNull()?.context?.publishAuditNetworks(config, entries.map { it.record }, windows)
    }

    private fun forgetNetwork(entry: Entry, context: TaskContext) {
        if (!entry.systemConfiguration || entry.configurationCleanupAttempted) return
        entry.configurationCleanupAttempted = true
        try {
            val successful = entries.lastOrNull {
                it.record.input.ssid == entry.record.input.ssid &&
                    it.record.state == PskSecurityAuditNetworkState.SUCCEEDED
            }
            if (successful != null) {
                val password = successful.record.password
                if (password.isNullOrEmpty()) {
                    context.log("[${entry.record.input.ssid}] 已有成功记录但无可恢复的密码，保留网络配置")
                } else {
                    worker.retainSuccessfulNetwork(entry.record.input.ssid, password)
                    context.log("[${entry.record.input.ssid}] 已恢复成功记录的网络配置")
                }
            } else if (worker.forgetNetwork(entry.record.input.ssid)) context.log("[${entry.record.input.ssid}] 已遗忘网络配置")
            else context.log("[${entry.record.input.ssid}] 未找到可遗忘的网络配置")
        } catch (error: Exception) {
            context.log("[${entry.record.input.ssid}] 遗忘网络失败：${error.message ?: error.javaClass.name}")
        }
    }

    /**
     * 决定本次连接使用的 MAC；null 表示使用设备原 MAC。
     *
     * 异常时更换/每次更换在首次尝试就使用随机地址，并在该网络的后续尝试中一直保持，
     * 不再回退到设备地址；同一密码连续重试次数达到阈值时即换成新的随机地址。
     * 每次更换等价于阈值 0，即每次尝试都换新。
     */
    private fun selectMac(entry: Entry, config: PskSecurityAuditConfig): String? {
        if (config.connectionMethod != PskSecurityAuditConnectionMethod.NETWORK_CARD) {
            entry.mac = null
            return null
        }
        return when (config.macMode) {
            PskSecurityAuditMacMode.DEVICE -> { entry.mac = null; null }
            PskSecurityAuditMacMode.EVERY_ATTEMPT -> randomMacAddress().also { entry.mac = it }
            PskSecurityAuditMacMode.ON_FAILURE ->
                if (entry.mac == null || entry.record.retryCount >= config.macChangeThreshold) {
                    randomMacAddress().also { entry.mac = it }
                } else entry.mac
        }
    }

    /** 本地管理的单播随机 MAC，避开多播位。 */
    private fun randomMacAddress(): String =
        (1..6).joinToString(":") { "%02x".format(SECURE_RANDOM.nextInt(256) and 0xfc or 0x02) }

    private fun cooldownMillis(retries: Int): Long =
        PskSecurityAuditQueue.cooldownMillis(config.cooldownBaseMillis, retries)

    private class AttemptChanged : InterruptedException()

    private fun groupKey(entry: Entry): Pair<String, String> =
        entry.record.input.ssid to (entry.record.groupId ?: entry.record.id)

    /** 一次占用某张网卡的尝试；只有调度线程会创建和销毁它。 */
    private class Job(val recordId: String, val window: String, val ssid: String, val password: String,
        val mac: String?, val config: PskSecurityAuditConfig, val child: TaskContext,
        val startedAt: Long, val position: Int)

    private sealed interface Outcome {
        val recordId: String
        val window: String
        data class Finished(override val recordId: String, override val window: String,
            val result: ConnectionAttemptResult, val duration: Long, val errorMessage: String? = null) : Outcome
        data class Aborted(override val recordId: String, override val window: String) : Outcome
        data class CleanupFailed(override val recordId: String, override val window: String,
            val cause: ConnectionCleanupException) : Outcome
    }

    private class StageUpdate(val recordId: String, val token: Long, val stage: ConnectWifiStage)

    /**
     * 共享字典池：同组成员共用一份候选，按领取法各占一个互不相同的下标。
     *
     * [claim] 优先发回被成员放回（停止/异常）的下标，其次才是从未领取过的新下标；
     * [consume] 表示该下标已判定为错误并丢弃；[release] 表示结果未知、交还给别人重试。
     * 正被占领的下标在重试冷却期间保持归属，别的成员领不走。
     */
    private class Pool(val id: String, val passwords: List<String>, startCursor: Int = 0,
        initialCompleted: Int = 0) {
        /** 已判定为错误的候选数量，即公共进度。 */
        var completed = initialCompleted
            private set
        private var cursor = startCursor.coerceIn(0, passwords.size)
        val owners = mutableMapOf<Int, String>()
        val released = sortedSetOf<Int>()

        fun claimable(): Boolean = cursor < passwords.size || released.isNotEmpty()
        fun exhausted(): Boolean = !claimable() && owners.isEmpty()

        fun claim(memberId: String): Int? {
            if (!claimable()) return null
            val index = released.firstOrNull()?.also { released.remove(it) } ?: cursor++
            owners[index] = memberId
            return index
        }

        fun consume(index: Int) { if (owners.remove(index) != null) completed++ }

        fun release(index: Int) { if (owners.remove(index) != null) released += index }

        /** 某成员发现密码，整池收束：进度取已确定的那一项，之后不再发号。 */
        fun resolved(foundAt: Int) {
            completed = maxOf(completed, foundAt)
            owners.clear()
            released.clear()
            cursor = passwords.size
        }
    }

    private data class Entry(val context: TaskContext,
        var record: AuditRecord.Dictionary, val passwords: List<String>,
        override var lastAttemptAt: Long = 0, override var readyAt: Long = 0,
        var pool: Pool? = null,
        /** 随机 MAC 策略下该网络当前保持的地址；null 表示使用设备原 MAC。 */
        var mac: String? = null,
        // 排队中的记录尚未提交系统配置；停止它不能清理同 SSID 其他记录正在使用的配置。
        // 工作线程回写、调度线程读取，因此需要 volatile。
        @field:Volatile var systemConfiguration: Boolean = false,
        @field:Volatile var configurationCleanupAttempted: Boolean = false,
        /** 共享池中当前占领的候选；重试冷却期间保持，独立记录恒为 null。 */
        var heldIndex: Int? = null,
        /** 递增即撤销该记录进行中的尝试；调度线程写、工作线程读。 */
        @field:Volatile var attemptToken: Long = 0,
    ) : PskSecurityAuditQueueEntry {
        override val active get() = record.active
    }

    private sealed interface Command {
        data class AddGroup(val network: PskSecurityAuditNetworkInput, val passwords: List<String>,
            val count: Int, val context: TaskContext) : Command
        data class JoinGroup(val network: PskSecurityAuditNetworkInput,
            val count: Int, val context: TaskContext) : Command
        data class StopRecord(val recordId: String) : Command
        data class StopNetwork(val ssid: String) : Command
        data class StopGroup(val ssid: String, val groupId: String) : Command
        data object StopAll : Command
        data class Configure(val config: PskSecurityAuditConfig) : Command
    }

    private companion object {
        val SECURE_RANDOM = java.security.SecureRandom()
        const val SYSTEM_CONNECTION_FOCUS = "系统连接名额"
        val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        const val INTERFACE_SCAN_INTERVAL_MS = 1000L
        const val POLL_WITH_RUNNING_MS = 200L
        const val IDLE_POLL_MS = 200L
        const val WINDOW_DRAIN_POLL_MS = 200L
        const val SHUTDOWN_DEADLINE_MS = 20_000L
    }
}
