package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * JVC 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 38000Hz
 *  - 前导 mark/space = 8400/4200 µs
 *  - 位时序：0 = 525/525；1 = 525/1575
 *  - 帧尾 mark 525 µs，随后 21000µs gap
 *  - 位序：**整体 LSB 先发**（16 位）
 *  - 输入 4 位 hex → 16 bit
 *  - PressKind.REPEAT：**仅发 525µs mark 短爆发**（标准 JVC 重复帧），
 *    重复间隔按协议覆盖为 110ms（不受统一 250ms 限制）
 *
 * 无状态编码器，使用单例 object。
 */
object JvcEncoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.JVC

    /** JVC 长按重复间隔 110ms（短爆发重复帧，见 §3.2 表） */
    override val repeatIntervalMs: Int = 110

    const val FREQUENCY = 38_000         // JVC 载波
    const val PRE_MARK = 8400            // 前导 mark µs
    const val PRE_SPACE = 4200           // 前导 space µs
    const val BIT_MARK = 525             // 位 mark
    const val BIT0_SPACE = 525           // 0 的 space
    const val BIT1_SPACE = 1575          // 1 的 space
    const val TRAIL_MARK = 525           // 帧尾 mark
    const val FRAME_GAP = 21_000         // 帧后 gap µs

    /**
     * 编码 JVC 波形。
     *
     * @param hex hex 码串：4 位十六进制，左补 0 到 16 位；非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press [PressKind.NEW_PRESS] 输出完整帧（前导 + 16 位 + 帧尾 525 + 21000 gap）；
     *              [PressKind.REPEAT] 仅发 525µs mark 短爆发（与 hex 无关，不补 gap）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        // 长按重复：标准 JVC 重复帧 = 仅 525µs mark 短爆发，与 hex 无关
        if (press == PressKind.REPEAT) {
            return IRPattern(FREQUENCY, intArrayOf(BIT_MARK))
        }

        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "JVC 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val value = normalized.toInt(16) and 0xFFFF
        // 整体 LSB 先发（bit0 先发）
        val bits = (0 until 16).map { (value shr it) and 1 }

        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE          // 前导
        for (b in bits) {                             // 16 位
            list += BIT_MARK
            list += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        list += TRAIL_MARK                           // 帧尾
        list += FRAME_GAP                            // 21000 gap
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
