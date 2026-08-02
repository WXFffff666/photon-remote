package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * SONY 15 位协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 40000Hz
 *  - 前导 mark/space = 2400/600 µs
 *  - 位时序：0 = 600/600；1 = 1200/600
 *  - **删尾**：无帧尾 mark，帧以最后一个数据位的 space 结束
 *  - 整帧补零到 45000 µs
 *  - 位序：**整体 LSB 先发**（15 位 = 8 位地址 + 7 位命令，编码器按整体数值处理，仅取低 15 位）
 *  - 输入 4 位 hex → 15 bit
 *  - 重复行为：完整帧重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object Sony15Encoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.SONY15

    /** SONY 无协议级重复间隔覆盖，走全局默认 250ms（完整帧重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 40_000         // SONY 载波
    const val PRE_MARK = 2400            // 前导 mark µs
    const val PRE_SPACE = 600            // 前导 space µs
    const val BIT_MARK = 600             // 位 mark
    const val BIT0_SPACE = 600           // 0 的 space
    const val BIT1_SPACE = 1200          // 1 的 space
    const val TOTAL_US = 45_000          // 整帧目标时长（含补零）
    const val BIT_COUNT = 15             // 数据位宽

    /**
     * 编码 SONY 15 位波形。
     *
     * @param hex hex 码串：1-4 位十六进制，左补 0 到 15 位（取低 15 位）；非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press 任意（SONY 完整帧重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "SONY15 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val value = normalized.toInt(16) and ((1 shl BIT_COUNT) - 1)
        // 整体 LSB 先发（bit0 先发）
        val bits = (0 until BIT_COUNT).map { (value shr it) and 1 }

        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE          // 前导
        for (b in bits) {                             // 15 位（删尾：无帧尾 mark）
            list += BIT_MARK
            list += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        val used = list.sum()
        list += (TOTAL_US - used).coerceAtLeast(0)   // 补零到整帧 45000
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
