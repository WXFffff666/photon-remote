package com.photon.remote.codebase.finder

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.protocol.ProtocolEncoders
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 暴力找码引擎（计划 §4.4 / Todo 22）：按协议 + hex 前缀约束迭代候选码，逐个编码发送，
 * 直到用户停止（协程取消）或命中（transmit 返回 true）。
 *
 * 迭代规则：
 *  - 前缀固定（如 "AA" = 高 8 位），其余位从 0x0 起递增；
 *  - 每个候选经 [ProtocolEncoders] 编码为 [IRPattern]，再交给注入的 transmit 回调发送；
 *  - 每次发送后间隔 [BruteForceConfig.intervalMs]（默认 800ms），避免连续发码过快；
 *  - 候选 hex 统一补齐到协议位宽（bitWidth/4 个十六进制位），如 NEC(32bit) → 8 位。
 *
 * 协程化：
 *  - [run] 为 suspend 循环，每次迭代检查 isActive；取消（Job.cancel）时捕获
 *    [CancellationException] 并返回 null（不向上抛异常，UI 无需 try/catch）；
 *  - [onTested] 回调（候选 hex, 已测数, 总数）供 UI 刷新进度。
 */
data class BruteForceConfig(
    val protocol: ProtocolType,   // 目标协议（NEC 等），经 ProtocolEncoders.all 查编码器
    val prefixHex: String = "",   // hex 前缀，可空/空串 = 全量迭代；支持 "AA" / "0xAABB" / "AA:BB"
    val bitWidth: Int,            // 协议目标位宽（如 NEC=32、SONY12=12）
    val intervalMs: Long = 800L,  // 相邻候选发送间隔
)

object IrBruteForcer {

    /**
     * 规范化并校验 hex 前缀。
     * 去除 "0x"/"0X"、冒号、空格并转大写；仅允许 0-9A-F。
     * 空串/空白 → 返回 ""（表示无前缀，全量迭代）。
     * @throws IllegalArgumentException 非法字符时（中文提示）
     */
    fun parsePrefix(prefix: String): String {
        val cleaned = prefix
            .replace("0x", "", ignoreCase = true)
            .replace(":", "")
            .replace(" ", "")
            .uppercase()
        if (cleaned.isEmpty()) return ""
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
            throw IllegalArgumentException("非法 hex 前缀：\"$prefix\"（仅支持 0-9 与 A-F，可带 0x/:/空格）")
        }
        return cleaned
    }

    /**
     * 候选总数：前缀固定后剩余位（bitWidth - 前缀位数）的组合数 2^剩余位。
     * 前缀位宽超过协议位宽、或剩余位 ≥ 63（Long 溢出）时抛 [IllegalArgumentException]。
     */
    fun candidateCount(config: BruteForceConfig): Long {
        val prefix = parsePrefix(config.prefixHex)
        val prefixBits = prefix.length * 4
        require(prefixBits <= config.bitWidth) {
            "前缀位宽 ${prefixBits}bit 超过协议位宽 ${config.bitWidth}bit"
        }
        val remaining = config.bitWidth - prefixBits
        require(remaining < 63) { "剩余位宽 ${remaining}bit 过大，超出 Long 可表示范围" }
        return 1L shl remaining
    }

    /**
     * 暴力找码主循环（suspend，支持取消）。
     *
     * @param transmit 发送回调：编码后的 [IRPattern] 送入发射器（调用方负责经
     *                 IrDispatcher/TransmitterManager 发送），返回 true 表示设备响应命中。
     * @param onTested 进度回调：(候选 hex, 已测数, 总数)，每次发送后调用（含命中那一次）。
     * @return 命中的候选 hex（大写、按位宽补齐）；未命中自然跑完或协程取消时返回 null。
     */
    suspend fun run(
        config: BruteForceConfig,
        transmit: suspend (IRPattern) -> Boolean,
        onTested: (String, Long, Long) -> Unit,
    ): String? {
        val encoder = ProtocolEncoders.all[config.protocol]
            ?: throw IllegalArgumentException("不支持的协议：${config.protocol}")
        val prefix = parsePrefix(config.prefixHex)
        val total = candidateCount(config)
        val prefixValue = prefix.ifEmpty { "0" }.toLong(16)
        val remainingBits = config.bitWidth - prefix.length * 4
        val hexDigits = (config.bitWidth + 3) / 4   // 向上取整，如 32bit→8 位、12bit→3 位

        // 取消视为正常结束：返回 null 而非向上抛 CancellationException
        return try {
            for (i in 0 until total) {
                if (!coroutineContext.isActive) return null
                val value = (prefixValue shl remainingBits) + i
                val hex = value.toString(16).uppercase().padStart(hexDigits, '0')
                val hit = transmit(encoder.encode(hex))
                val tested = i + 1
                onTested(hex, tested, total)
                if (hit) return hex
                if (tested < total) delay(config.intervalMs)   // 未命中且未到末尾才间隔
            }
            null
        } catch (e: CancellationException) {
            null
        }
    }
}
