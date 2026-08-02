package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * SHARP 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 38000Hz
 *  - **无独立前导**（直接发数据位）
 *  - 位时序：0 = 280/860；1 = 280/1720
 *  - 帧尾 mark 280 µs
 *  - 13 位（5 位地址 + 8 位命令）×2：第二段命令取反，两段间 ~40ms（40000µs）
 *  - 位序：**整体 LSB 先发**（13 位值 = 地址在高 5 位、命令在低 8 位）
 *  - 输入 4 位 hex → 13 位值（bit15..bit13 忽略）
 *  - 重复行为：完整双帧重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object SharpEncoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.SHARP

    /** SHARP 无协议级重复间隔覆盖，走全局默认 250ms（完整双帧重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 38_000         // SHARP 载波
    const val BIT_MARK = 280             // 位 mark
    const val BIT0_SPACE = 860           // 0 的 space
    const val BIT1_SPACE = 1720          // 1 的 space
    const val TRAIL_MARK = 280           // 帧尾 mark
    const val SEGMENT_GAP = 40_000       // 两段间 ~40ms
    const val TRAIL_SPACE = 1            // 终止 space（规则 2：输出以 space 结尾）

    /**
     * 编码 SHARP 波形（13 位 ×2，第二段命令取反，两段间 40ms）。
     *
     * @param hex hex 码串：4 位十六进制（bit12..bit8 = 地址 5 位，bit7..bit0 = 命令 8 位）；
     *            非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press 任意（SHARP 完整双帧重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "SHARP 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val value = normalized.toInt(16) and 0x1FFF       // 13 位值
        val address = (value shr 8) and 0x1F              // 5 位地址（bit12..bit8）
        val command = value and 0xFF                      // 8 位命令（bit7..bit0）

        // 单段：13 位整体 LSB 先发（bit0 = 命令 LSB 先发）+ 帧尾 280
        fun segment(cmd: Int): List<Int> {
            val bits = (0 until 13).map { i ->
                when {
                    i < 8 -> (cmd shr i) and 1                    // 命令 8 位（LSB 先发）
                    else -> (address shr (i - 8)) and 1           // 地址 5 位（LSB 先发）
                }
            }
            val list = mutableListOf<Int>()                       // 无独立前导
            for (b in bits) {
                list += BIT_MARK
                list += if (b == 0) BIT0_SPACE else BIT1_SPACE
            }
            list += TRAIL_MARK                                   // 帧尾
            return list
        }

        // 两段：第一段命令原样，第二段命令取反，两段间 40000µs
        val list = mutableListOf<Int>()
        list += segment(command)
        list += SEGMENT_GAP
        list += segment(command.inv() and 0xFF)
        list += TRAIL_SPACE                                      // 终止 space
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
