package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # WPS 安全测试
 *
 * 评估弱 PIN、随机数缺陷（pixie dust）漏洞与本次批量 PIN 尝试的实际结果。
 * 一批网络占用一个通用任务；同一 SSID 在运行队列中只出现一次。
 *
 * ## 三阶段状态机
 *
 * 每个网络按下列顺序推进，进度写入 [AuditRecord.Wps] 并随历史持久化，可断点续跑：
 *
 * 1. [WpsSecurityAuditPhase.FAIRY_DUST]：先用 `12345670` 发起一次握手抓取 WPS 握手数据，
 *    由 Service 在 chroot 中调用 `/bin/pixiewps` 现场计算漏洞 PIN，再用该 PIN 发起第二次
 *    握手。历史只保存整体阶段；握手数据与临时计算出的 PIN 仅存在于运行内存。
 *    最终结果由 PIN、密码及发现阶段记录，不保存额外的漏洞判定。
 * 2. [WpsSecurityAuditPhase.FIRST_HALF]：`0000`~`9999` 逐个尝试前四位（占位后三位为 `000`
 *    并按七位 PIN 规则补校验位）；收到 M5 即表示前半段正确，记录后进入第三阶段。
 * 3. [WpsSecurityAuditPhase.SECOND_HALF]：已知前四位后逐个尝试后三位（同样补校验位），
 *    取到密码即完成。
 *
 * 任意阶段取得密码即结束该网络。未取得密码时只记录本次实际事件，不根据消息编号推断错误。
 *
 * 未推进到 M5 的结果一律算单次异常，包括 WSC_NACK、WPS-FAIL、超时与未进入握手，
 * 全部按 [WpsSecurityAuditConfig.retryCount] / [WpsSecurityAuditConfig.cooldownBaseMillis]
 * 重试当前 PIN 并指数冷却，重试用尽后才推进游标。脚本会把 NACK 的具体原因
 * （前/后半段不匹配、CONFIG_ERROR）随结果回传，仅用于日志与展示，不参与游标推进判断。
 * MAC 策略复用 [SecurityAuditMacMode]。
 *
 * 字典内容不涉及本任务；凭据与安全测试历史使用同一数据源，按
 * [AuditRecordKind] 区分。
 */
enum class WpsSecurityAuditPhase { FAIRY_DUST, FIRST_HALF, SECOND_HALF, DONE }

/**
 * 一次握手捕获到的 WPS 关键数据，全部为大写 hex 字符串，由 wps.py 通过 FIFO result 回传。
 * [essid] 为空表示脚本尚未解析到，仅影响展示，不参与 pixiewps 计算。
 */
@Parcelize
data class WpsHandshake(
    val pke: String,
    val pkr: String,
    val eNonce: String,
    val authKey: String,
    val eHash1: String,
    val eHash2: String,
    val essid: String = "",
) : Parcelable {
    val complete: Boolean
        get() = pke.isNotEmpty() && pkr.isNotEmpty() && eNonce.isNotEmpty() &&
            authKey.isNotEmpty() && eHash1.isNotEmpty() && eHash2.isNotEmpty()
}

@Parcelize
data class WpsSecurityAuditConfig(
    /** 复用安全测试的 MAC 策略；WPS 走网卡通道，任何策略都会实际改写本机 MAC。 */
    val macMode: SecurityAuditMacMode = SecurityAuditMacMode.DEVICE,
    val macChangeThreshold: Int = 1,
    /** 超时/异常的重试次数；6 表示无限，与字典安全测试一致。 */
    val retryCount: Int = 6,
    val cooldownBaseMillis: Long = 1000,
    /** 单网络握手次数上限，0 表示不限。达到上限只记录测试已结束，不推断防护结论。 */
    val maxAttemptsPerNetwork: Int = 0,
) : Parcelable

@Parcelize
data class WpsSecurityAuditTarget(
    val ssid: String,
    val mac: String? = null,
    /** 断点续跑：从历史记录恢复的阶段。 */
    val resumePhase: WpsSecurityAuditPhase = WpsSecurityAuditPhase.FAIRY_DUST,
    val resumeFirstHalf: String? = null,
    val resumeFirstHalfIndex: Int = 0,
    val resumeSecondHalfIndex: Int = 0,
) : Parcelable

/** 隐藏名称按明确选择的接入点区分，禁止用空名称猜测目标。 */
fun wpsAuditTargetKey(ssid: String, mac: String?): String =
    if (ssid.isEmpty()) "bssid:${requireNotNull(mac).lowercase()}" else "ssid:$ssid"

@Parcelize
data class WpsSecurityAuditTaskInput(
    val config: WpsSecurityAuditConfig,
    val targets: List<WpsSecurityAuditTarget>,
) : Parcelable
