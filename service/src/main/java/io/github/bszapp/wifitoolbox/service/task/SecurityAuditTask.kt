package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 旧版多网络轮询、冷却、重试调度；单次连接由安全测试专用执行器完成。
 *
 * 调度线程只负责领号、开窗与收尾，[entries] 的读写也只发生在它身上；真正阻塞在连接上的
 * 工作交给工作线程，结果经 [outcomes] 回流。这样两种模式共用同一套状态机：
 *
 * - 未开启多线程时固定占用 wlan0 这一张窗口，同一时刻只有一个任务在跑，与旧版串行行为一致。
 * - 开启多线程（仅网卡方式）时可作为普通 Wi-Fi 客户端的网卡即窗口，空闲窗口按最久未尝试
 *   优先领一个排队记录；一张网卡一次只跑一个密码，做完一次尝试（成功/错误/异常）就回到
 *   队尾重新排号，窗口随即交还给下一个任务。
 *
 * 共享字典池按领取法运转：成员手头空了就向池子申领一个互不相同的候选并占住它，
 * 得到确定结果才交还，重试冷却期间保持归属。
 */
internal class SecurityAuditTask(
    input: SecurityAuditTaskInput,
    private val dictionary: (String) -> List<String>,
    private val worker: SecurityAuditConnectWorker,
    /** 可作为普通 Wi-Fi 客户端的网卡；多线程模式下作为可分配的窗口。 */
    private val interfaces: () -> List<String> = { emptyList() },
) : ServiceTask {
    @Volatile private var config = input.config
    private val updates = LinkedBlockingQueue<TaskUpdatePayload>()
    private val entries = mutableListOf<Entry>()
    /** 工作线程写、调度线程读；进度只搬数据，不直接改记录。 */
    private val stages = LinkedBlockingQueue<StageUpdate>()
    private val outcomes = LinkedBlockingQueue<Outcome>()
    /** recordId -> 正占用某张网卡的尝试。 */
    private val running = mutableMapOf<String, Job>()
    @Volatile private var accepting = true

    private var cachedInterfaces: List<String> = emptyList()
    private var interfaceScannedAt = 0L

    init { add(input.network) }

    override fun update(update: TaskUpdateRequest): Boolean {
        synchronized(updates) {
            if (!accepting) return false
            when (update.payload) {
                is TaskUpdatePayload.SecurityAuditAdd -> {
                    // TaskManager 校验并持有候选字典 ID；同一 SSID 可重复追加独立记录。
                }
                is TaskUpdatePayload.SecurityAuditJoin,
                is TaskUpdatePayload.SecurityAuditStop,
                is TaskUpdatePayload.SecurityAuditConfigure -> Unit
                else -> return false
            }
            updates.offer(update.payload)
            return true
        }
    }

    override fun run(context: TaskContext) {
        context.log(if (parallel()) "授权网络安全测试开始：网卡即窗口，多任务并行领取窗口" +
            "，每个窗口一次只跑一个密码"
        else "授权网络安全测试开始：多网络排队，单次连接串行执行")
        publish(context)
        try {
            while (true) {
                try {
                    context.ensureRunning()
                    drainUpdates(context)
                    drainStages(context)
                    reap(context)
                    retireExhaustedPools(context)
                    dispatch(context)
                    retireExhaustedPools(context)
                    val active = entries.filter { it.record.active }
                    if (active.isEmpty() && running.isEmpty()) {
                        synchronized(updates) {
                            if (updates.isNotEmpty()) return@synchronized
                            accepting = false
                        }
                        if (accepting) continue
                        context.log("运行队列为空，本批安全测试结束")
                        return
                    }
                    // 各个改状态的地方都自带 publish，这里不再重复广播。
                    if (running.isNotEmpty()) {
                        // 有窗口在跑就短等，保证停止/配置变更与进度能及时被处理。
                        outcomes.poll(POLL_WITH_RUNNING_MS, TimeUnit.MILLISECONDS)?.let { finish(it, context) }
                    } else {
                        // 共享池被占满的成员此刻领不到候选，拿它算等待会算成 0 并空转；
                        // 只把“手里有候选”或“池子还能发号”的成员纳入等待计算。
                        val schedulable = active.filter { entry ->
                            val pool = entry.pool
                            pool == null || entry.heldIndex != null || pool.claimable()
                        }
                        val wait = if (schedulable.isEmpty()) IDLE_POLL_MS
                        else SecurityAuditQueue.waitMillis(schedulable).coerceIn(1L, 2000L)
                        updates.poll(wait, TimeUnit.MILLISECONDS)?.let { applyUpdate(it, context) }
                    }
                } catch (stop: InterruptedException) {
                    throw stop
                } catch (error: ConnectionCleanupException) {
                    context.log("上一轮断开未确认，停止提交后续连接：${error.cause?.message ?: error.message}")
                    throw error
                } catch (error: Exception) {
                    context.log("本轮异常，保留当前位置继续：${error.message ?: error.javaClass.name}")
                    Thread.sleep(100)
                }
            }
        } finally {
            accepting = false
            drainRunning(context)
            runCatching { worker.closeNetworkCardSession(context) }
                .onFailure { context.log("关闭持久网卡脚本会话失败：${it.message ?: it.javaClass.name}") }
            entries.filter { it.record.active }.forEach {
                it.record = it.record.copy(state = SecurityAuditNetworkState.STOPPED,
                    stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(),
                    window = null, attemptPassword = null, message = "已停止")
            }
            entries.filter { it.record.state != SecurityAuditNetworkState.SUCCEEDED }.forEach {
                forgetNetwork(it, context)
            }
            publish(context)
        }
    }

    // ------------------------------------------------------------------ 调度

    /** 多线程仅在勾选且走网卡方式时生效；系统方式共用 supplicant，始终只开一张窗口。 */
    private fun parallel(): Boolean =
        config.multithreaded && config.connectionMethod == SecurityAuditConnectionMethod.NETWORK_CARD

    /** 当前没被占用的窗口；未开多线程时恒为单张 wlan0，与脚本旧行为一致。 */
    private fun freeWindows(): List<String> {
        val all = if (parallel()) enumerateInterfaces() else listOf(DEFAULT_INTERFACE)
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
        return cachedInterfaces.ifEmpty { listOf(DEFAULT_INTERFACE) }
    }

    /** 把空闲窗口交给排队记录：最久未尝试的先上，领不到候选的（共享池被占满）跳过。 */
    private fun dispatch(context: TaskContext) {
        if (!parallel() && running.isNotEmpty()) return
        val free = freeWindows()
        if (free.isEmpty()) return
        val waiting = entries.filter { it.record.active && !running.containsKey(it.record.id) }
            .sortedBy { it.lastAttemptAt }
        if (waiting.isEmpty()) return
        // 串行模式只用第一张窗口；未开多线程时窗口列表本身也只有一张。
        for (window in free.take(if (parallel()) free.size else 1)) {
            waiting.firstOrNull { entry ->
                entry.record.active && !running.containsKey(entry.record.id) &&
                    entry.readyAt <= SystemClock.elapsedRealtime() && start(entry, window, context)
            } ?: break
        }
    }

    /**
     * 让一个记录占用 [window] 跑一次密码尝试；返回 false 表示这次没跑起来（共享池领不到
     * 候选或字典已尽），调用方应把窗口让给下一个候选。
     */
    private fun start(entry: Entry, window: String, context: TaskContext): Boolean {
        if (running.containsKey(entry.record.id)) return false
        val pool = entry.pool
        val position = if (pool == null) entry.record.completed
        else entry.heldIndex ?: pool.claim(entry.record.id) ?: return false
        entry.heldIndex = if (pool == null) null else position
        val password = entry.passwords.getOrNull(position)
        if (password == null) {
            exhaust(entry, context)
            publish(context)
            return false
        }
        val token = entry.attemptToken
        val selectedConfig = config
        entry.lastAttemptAt = SystemClock.elapsedRealtime()
        val started = entry.lastAttemptAt
        // 网卡方式的随机策略在本轮连接前决定并保持；返回 null 即使用设备原 MAC。
        val mac = selectMac(entry, selectedConfig)
        val child = context.forAttempt(entry.record.input.ssid, { progress ->
            // 工作线程只投递数据，记录改写统一回到调度线程，避免并发写同一条记录。
            if (progress is TaskProgress.ConnectWifi) stages.offer(StageUpdate(entry.record.id, progress.stage))
        }, { if (token != entry.attemptToken) throw AttemptChanged() }, { config })
        val job = Job(entry.record.id, window, entry.record.input.ssid, password, mac,
            selectedConfig, child, started, position)
        running[entry.record.id] = job
        try {
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.CONNECTING, config = selectedConfig,
                attempts = entry.record.attempts + 1, nextAttemptAt = null,
                updatedAt = System.currentTimeMillis(),
                window = window, attemptPassword = password,
                message = if (mac != null) "正在尝试：$password\nMAC：$mac" else "正在尝试：$password",
                stage = ConnectWifiStage.ROUTER_COMMUNICATION)
            context.log("[${entry.record.input.ssid}] 尝试字典第 ${position + 1}/${entry.passwords.size} 位" +
                " 窗口=$window" + (mac?.let { " mac=$it" } ?: ""))
            publish(context)
            Thread({ runJob(job, context, entry) }, "security-audit-$window").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            // 开窗失败必须原样交还候选与窗口，否则共享池会永远少一个候选。
            running.remove(entry.record.id)
            entry.heldIndex?.let { pool?.release(it) }
            entry.heldIndex = null
            throw error
        }
        return true
    }

    /** 工作线程：只跑阻塞的连接与清理，全程不改写 [entries] 里的记录字段。 */
    private fun runJob(job: Job, context: TaskContext, entry: Entry) {
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
            context.log("[${job.ssid}] 执行异常：${error.message ?: error.javaClass.name}")
            outcomes.offer(Outcome.Finished(job.recordId, job.window, ConnectionAttemptResult.ERROR, duration))
        }
    }

    private fun reap(context: TaskContext) {
        while (true) finish(outcomes.poll() ?: break, context)
    }

    private fun drainStages(context: TaskContext) {
        var changed = false
        while (true) {
            val update = stages.poll() ?: break
            changed = true
            entries.firstOrNull { it.record.id == update.recordId }?.let { entry ->
                entry.record = entry.record.copy(stage = update.stage, updatedAt = System.currentTimeMillis())
            }
        }
        if (changed) publish(context)
    }

    /** 共享池再没有可领取的候选时，把仍然排队的成员整体收尾。 */
    private fun retireExhaustedPools(context: TaskContext) {
        var retired = false
        while (true) {
            val victim = entries.firstOrNull { it.record.active && it.pool?.exhausted() == true } ?: break
            exhaust(victim, context)
            retired = true
        }
        if (retired) publish(context)
    }

    // ------------------------------------------------------------------ 收尾

    private fun finish(outcome: Outcome, context: TaskContext) {
        val job = running.remove(outcome.recordId) ?: return
        val entry = entries.firstOrNull { it.record.id == outcome.recordId } ?: return
        // 窗口已经交还：受理者与候选密码一并撤下，避免界面/通知继续显示上一轮的现场。
        if (entry.record.window != null || entry.record.attemptPassword != null) {
            entry.record = entry.record.copy(window = null, attemptPassword = null,
                updatedAt = System.currentTimeMillis())
        }
        if (outcome is Outcome.CleanupFailed) {
            entry.heldIndex?.let { entry.pool?.release(it) }
            entry.heldIndex = null
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.ERROR, stage = null,
                updatedAt = System.currentTimeMillis(), message = "上一轮断开未确认，已停止继续连接")
            publish(context)
            throw outcome.cause
        }
        if (!entry.record.active) {
            // 记录已被停止或被共享池的成功结论收走：本轮结果作废，只交还候选与窗口。
            entry.heldIndex?.let { entry.pool?.release(it) }
            entry.heldIndex = null
            forgetNetwork(entry, context)
            publish(context)
            return
        }
        when (outcome) {
            is Outcome.Aborted -> {
                // 配置或共享关系变化，本轮作废但候选与位置都保留，稍后重新排号。
                entry.record = entry.record.copy(state = SecurityAuditNetworkState.QUEUED, stage = null,
                    nextAttemptAt = null, message = "排队中", attempts = entry.record.attempts - 1)
                publish(context)
            }
            is Outcome.Finished -> finishAttempt(entry, job, outcome, context)
            is Outcome.CleanupFailed -> Unit
        }
    }

    private fun finishAttempt(entry: Entry, job: Job, outcome: Outcome.Finished, context: TaskContext) {
        val result = outcome.result
        val duration = outcome.duration
        val position = job.position
        val totalDuration = entry.record.elapsedMillis + duration
        when (result) {
            ConnectionAttemptResult.FAILED -> {
                entry.costs += duration
                if (entry.costs.size > 20) entry.costs.removeAt(0)
            }
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED -> {
                if (entry.costs.isNotEmpty()) entry.costs[entry.costs.lastIndex] += duration
            }
            else -> Unit
        }
        entry.record = entry.record.copy(elapsedMillis = totalDuration, stage = null,
            updatedAt = System.currentTimeMillis(), averageAttemptMillis =
                if (entry.costs.size >= 5) entry.costs.sum() / entry.costs.size else null)
        context.log("[${entry.record.input.ssid}] 本次结果=$result，耗时=${duration}ms")
        when (result) {
            ConnectionAttemptResult.SUCCESS -> {
                entry.record = entry.record.copy(state = SecurityAuditNetworkState.SUCCEEDED,
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
                            member.record = member.record.copy(state = SecurityAuditNetworkState.STOPPED,
                                stage = null, nextAttemptAt = null, message = "共享候选池已找到密码")
                            forgetNetwork(member, context)
                        }
                    }
                }
                context.log("[${entry.record.input.ssid}] 已在字典第 ${entry.record.dictionaryPosition} 位发现弱密码")
            }
            ConnectionAttemptResult.FAILED -> advance(entry)
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED -> {
                val count = entry.record.retryCount + 1
                if (SecurityAuditQueue.retryExhausted(config.retryCount, count)) advance(entry)
                else {
                    val delay = cooldownMillis(count)
                    // 时间使用单调钟；仅用于展示的下次时间转换成墙上时间。
                    entry.readyAt = SecurityAuditQueue.readyAt(entry.lastAttemptAt, delay)
                    entry.record = entry.record.copy(retryCount = count,
                        state = if (delay > 0) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED,
                        message = "${if (result == ConnectionAttemptResult.TIMEOUT) "超时" else worker.rejectionDescription ?: "路由器拒绝接入"}，第 $count 次重试",
                        nextAttemptAt = SecurityAuditQueue.displayTime(entry.readyAt))
                }
            }
            ConnectionAttemptResult.ERROR -> {
                // 结果未知但候选交还，让同池其他成员接着试它。
                entry.heldIndex?.let { entry.pool?.release(it) }
                entry.heldIndex = null
                entry.record = entry.record.copy(state = SecurityAuditNetworkState.ERROR,
                    message = "连接执行异常，请查看任务日志")
            }
        }
        if (!entry.record.active && entry.record.state != SecurityAuditNetworkState.SUCCEEDED) {
            forgetNetwork(entry, context)
        }
        entry.pool?.let { pool ->
            entries.filter { it.pool === pool && !it.record.active && it.record.state != SecurityAuditNetworkState.SUCCEEDED }
                .forEach { forgetNetwork(it, context) }
        }
        publish(context)
    }

    /** 任务结束前等所有窗口交还；停止指令会让工作线程在一次轮询内主动退出。 */
    private fun drainRunning(context: TaskContext) {
        val interrupted = Thread.interrupted()
        val deadline = SystemClock.elapsedRealtime() + SHUTDOWN_DEADLINE_MS
        try {
            while (running.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                val outcome = try { outcomes.poll(WINDOW_DRAIN_POLL_MS, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { null }
                if (outcome != null) {
                    try { finish(outcome, context) }
                    catch (error: Exception) {
                        context.log("收尾窗口未确认断开：${error.message ?: error.javaClass.name}")
                    }
                }
            }
            if (running.isNotEmpty()) {
                context.log("等待 ${running.size} 个窗口交还超时，直接关闭脚本会话")
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
                state = if (completed >= entry.passwords.size) SecurityAuditNetworkState.EXHAUSTED
                else SecurityAuditNetworkState.QUEUED,
                message = if (completed >= entry.passwords.size) "给定字典未发现密码" else "排队中")
            return
        }
        // 共享池：交还手头这个候选，公共进度前进一位，其他成员可以接着领。
        entry.heldIndex?.let(pool::consume)
        entry.heldIndex = null
        val completed = pool.completed
        val now = System.currentTimeMillis()
        entries.filter { it.pool === pool }.forEach { member ->
            member.readyAt = 0
            member.record = member.record.copy(completed = completed, poolCompleted = completed,
                retryCount = 0, nextAttemptAt = null, updatedAt = now)
        }
    }

    private fun exhaust(entry: Entry, context: TaskContext) {
        val members = entry.pool?.let { pool -> entries.filter { it.pool === pool } } ?: listOf(entry)
        members.filter { it.record.active }.forEach { member ->
            member.heldIndex = null
            member.record = member.record.copy(state = SecurityAuditNetworkState.EXHAUSTED,
                stage = null, message = "给定字典未发现密码")
            forgetNetwork(member, context)
        }
    }

    private fun drainUpdates(context: TaskContext) {
        var applied = false
        while (true) { applyUpdate(updates.poll() ?: break, context); applied = true }
        if (applied) publish(context)
    }

    private fun applyUpdate(update: TaskUpdatePayload, context: TaskContext) {
        when (update) {
            is TaskUpdatePayload.SecurityAuditAdd -> {
                add(update.network)
                context.log("[${update.network.ssid}] 已添加运行网络，候选字典固定")
            }
            is TaskUpdatePayload.SecurityAuditJoin -> {
                val source = entries.firstOrNull { it.record.id == update.network.sharedWithRecordId }
                if (source == null) {
                    context.log("[${update.network.ssid}] 共享来源记录不存在，加入失败")
                } else {
                    val joined = sharePool(source, update.network)
                    context.log("[${joined.record.input.ssid}] 已加入共享候选池 ${joined.pool?.id}，" +
                        "记录 ${joined.record.id}")
                }
            }
            is TaskUpdatePayload.SecurityAuditStop -> {
                val entry = entries.firstOrNull { it.record.id == update.recordId && it.record.active }
                if (entry != null) {
                    if (running.containsKey(entry.record.id)) {
                        // 占着窗口：撤销本轮，候选与窗口由收尾统一交还。
                        entry.attemptToken++
                    } else {
                        entry.heldIndex?.let { entry.pool?.release(it) }
                        entry.heldIndex = null
                    }
                    entry.record = entry.record.copy(state = SecurityAuditNetworkState.STOPPED,
                        stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(), message = "用户停止此任务")
                    forgetNetwork(entry, context)
                    context.log("[${entry.record.input.ssid}] 已停止运行记录 ${entry.record.id}")
                }
            }
            is TaskUpdatePayload.SecurityAuditConfigure -> {
                config = update.config
                entries.filter { it.record.active }.forEach {
                    it.record = it.record.copy(config = config)
                    if (it.record.retryCount > 0) {
                        it.readyAt = SecurityAuditQueue.readyAt(it.lastAttemptAt, cooldownMillis(it.record.retryCount))
                        it.record = it.record.copy(nextAttemptAt = SecurityAuditQueue.displayTime(it.readyAt),
                            state = if (it.readyAt > SystemClock.elapsedRealtime()) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED)
                    }
                }
                context.log("安全测试运行配置已更新")
            }
            else -> error("安全测试不支持此更新")
        }
    }

    private fun add(input: SecurityAuditNetworkInput) {
        val passwords = dictionary(input.dictionaryId)
        val now = System.currentTimeMillis()
        val source = Entry(AuditRecord.Dictionary(UUID.randomUUID().toString(), input, config,
            now, now, SecurityAuditNetworkState.QUEUED, passwords.size,
            completed = input.startIndex.coerceIn(0, passwords.size)), passwords)
        entries += source
        // 一次「运行」的多条并行实例：接进同一个候选池，各网卡领取互不相同的候选。
        repeat(input.subtaskCount.coerceIn(1, MAX_SUBTASK_COUNT) - 1) { sharePool(source, input) }
    }

    /**
     * 把一条新记录接进 [source] 的候选池；首次调用会把来源记录升级为共享池模式。
     * 正在跑的来源本轮作废，保证它仍占着的候选能被正确搬进新池。
     */
    private fun sharePool(source: Entry, network: SecurityAuditNetworkInput): Entry {
        if (running.containsKey(source.record.id)) source.attemptToken++
        val held = source.heldIndex
        val pool = source.pool ?: Pool(source.record.id, source.passwords,
            startCursor = maxOf(source.record.completed, (held ?: -1) + 1)).also {
                source.pool = it
                source.record = source.record.copy(groupId = it.id, poolCompleted = it.completed)
                if (held != null) it.owners[held] = source.record.id
            }
        val now = System.currentTimeMillis()
        val joined = Entry(AuditRecord.Dictionary(UUID.randomUUID().toString(), network, config, now, now,
            if (source.record.active) SecurityAuditNetworkState.QUEUED else SecurityAuditNetworkState.STOPPED,
            pool.passwords.size, completed = pool.completed,
            message = if (source.record.active) "排队中" else "共享来源记录已结束",
            groupId = pool.id, poolCompleted = pool.completed), pool.passwords, pool = pool)
        entries += joined
        return joined
    }

    private fun publish(context: TaskContext) {
        // 窗口清单只在多线程模式下发；通知与界面据此按窗口列出各网卡正在做什么。
        context.publishAuditNetworks(config, entries.map { it.record },
            if (parallel()) enumerateInterfaces() else emptyList())
    }

    private fun forgetNetwork(entry: Entry, context: TaskContext) {
        if (!entry.systemConfiguration || entry.configurationCleanupAttempted) return
        entry.configurationCleanupAttempted = true
        try {
            val successful = entries.lastOrNull {
                it.record.input.ssid == entry.record.input.ssid &&
                    it.record.state == SecurityAuditNetworkState.SUCCEEDED
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
    private fun selectMac(entry: Entry, config: SecurityAuditConfig): String? {
        if (config.connectionMethod != SecurityAuditConnectionMethod.NETWORK_CARD) {
            entry.mac = null
            return null
        }
        return when (config.macMode) {
            SecurityAuditMacMode.DEVICE -> { entry.mac = null; null }
            SecurityAuditMacMode.EVERY_ATTEMPT -> randomMacAddress().also { entry.mac = it }
            SecurityAuditMacMode.ON_FAILURE ->
                if (entry.mac == null || entry.record.retryCount >= config.macChangeThreshold) {
                    randomMacAddress().also { entry.mac = it }
                } else entry.mac
        }
    }

    /** 本地管理的单播随机 MAC，避开多播位。 */
    private fun randomMacAddress(): String =
        (1..6).joinToString(":") { "%02x".format(SECURE_RANDOM.nextInt(256) and 0xfc or 0x02) }

    private fun cooldownMillis(retries: Int): Long =
        SecurityAuditQueue.cooldownMillis(config.cooldownBaseMillis, retries)

    private class AttemptChanged : InterruptedException()

    /** 一次占用某张网卡的尝试；只有调度线程会创建和销毁它。 */
    private class Job(val recordId: String, val window: String, val ssid: String, val password: String,
        val mac: String?, val config: SecurityAuditConfig, val child: TaskContext,
        val startedAt: Long, val position: Int)

    private sealed interface Outcome {
        val recordId: String
        val window: String
        data class Finished(override val recordId: String, override val window: String,
            val result: ConnectionAttemptResult, val duration: Long) : Outcome
        data class Aborted(override val recordId: String, override val window: String) : Outcome
        data class CleanupFailed(override val recordId: String, override val window: String,
            val cause: ConnectionCleanupException) : Outcome
    }

    private class StageUpdate(val recordId: String, val stage: ConnectWifiStage)

    /**
     * 共享字典池：同组成员共用一份候选，按领取法各占一个互不相同的下标。
     *
     * [claim] 优先发回被成员放回（停止/异常）的下标，其次才是从未领取过的新下标；
     * [consume] 表示该下标已判定为错误并丢弃；[release] 表示结果未知、交还给别人重试。
     * 正被占领的下标在重试冷却期间保持归属，别的成员领不走。
     */
    private class Pool(val id: String, val passwords: List<String>, startCursor: Int = 0) {
        /** 已判定为错误的候选数量，即公共进度。 */
        var completed = 0
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

    private data class Entry(var record: AuditRecord.Dictionary, val passwords: List<String>,
        override var lastAttemptAt: Long = 0, override var readyAt: Long = 0, val costs: MutableList<Long> = mutableListOf(),
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
    ) : SecurityAuditQueueEntry {
        override val active get() = record.active
    }

    private companion object {
        val SECURE_RANDOM = java.security.SecureRandom()
        const val DEFAULT_INTERFACE = "wlan0"
        val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        const val INTERFACE_SCAN_INTERVAL_MS = 1000L
        const val POLL_WITH_RUNNING_MS = 200L
        const val IDLE_POLL_MS = 200L
        const val WINDOW_DRAIN_POLL_MS = 200L
        const val SHUTDOWN_DEADLINE_MS = 20_000L
        /** 一次「运行」最多创建的并行实例数，与界面滑块 1～6 对齐。 */
        const val MAX_SUBTASK_COUNT = 6
    }
}
