package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * NECx1 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 38400Hz
 *  - 前导 mark/space = 4500/4500 µs
 *  - 位时序同 NEC：0 = 562/562；1 = 562/1687
 *  - 帧尾 mark 562 µs，整帧补零到 108800 µs
 *  - 位序：**每字节 LSB 先发**（32 位数据 = 4 字节，字节序从左到右）
 *  - PressKind.REPEAT：同 NEC 标准短重复帧 9000/2250 + 562µs mark（不补零），重复间隔 110ms
 *
 * 无状态编码器，使用单例 object。
 */
object Necx1Encoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.NECX1

    /** NECx1 长按重复间隔 110ms（标准短重复帧，见 §3.2 表） */
    override val repeatIntervalMs: Int = 110

    const val FREQUENCY = 38400          // NECx1 载波
    const val PRE_MARK = 4500            // 前导 mark µs（NECx1 为 4500，短于 NEC 的 9000）
    const val PRE_SPACE = 4500           // 前导 space µs
    const val BIT_MARK = 562             // 位 mark
    const val BIT0_SPACE = 562           // 0 的 space
    const val BIT1_SPACE = 1687          // 1 的 space
    const val TRAIL_MARK = 562           // 帧尾 mark
    const val TOTAL_US = 108_800         // 整帧目标时长（含补零）
    const val REPEAT_PRE_MARK = 9000     // 重复帧前导 mark（NEC 规范 9000/2250 + 562）
    const val REPEAT_PRE_SPACE = 2250    // 重复帧前导 space

    /**
     * 编码 NECx1 波形。
     *
     * @param hex hex 码串：1-8 位十六进制，左补 0 到 32 位；非法字符 / 超过 8 位抛 [IllegalArgumentException]
     * @param press [PressKind.NEW_PRESS] 输出完整帧（补零到 108800）；
     *              [PressKind.REPEAT] 输出 NEC 标准短重复帧 9000/2250+562（不补零）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        // 长按重复：NEC 规范短重复帧 = 前导 9000/2250 + 562µs mark，与 hex 无关，不补零
        if (press == PressKind.REPEAT) {
            return IRPattern(FREQUENCY, intArrayOf(REPEAT_PRE_MARK, REPEAT_PRE_SPACE, TRAIL_MARK))
        }

        val normalized = hex.trim().uppercase().padStart(8, '0')
        require(normalized.length <= 8) { "NECx1 码最多 8 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        // 位序：每字节 LSB 先发（先发第一个字节的 LSB…）
        val bytes = normalized.chunked(2).map { it.toInt(16) }
        val bits = bytes.flatMap { b -> (0..7).map { (b shr it) and 1 } }

        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE          // 前导
        for (b in bits) {                             // 32 位
            list += BIT_MARK
            list += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        list += TRAIL_MARK                           // 帧尾
        val used = list.sum()
        list += (TOTAL_US - used).coerceAtLeast(0)   // 补零到整帧
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
