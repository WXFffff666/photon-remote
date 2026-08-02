package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * SAMSUNG32 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 38000Hz
 *  - 前导 mark/space = 4500/4500 µs
 *  - 位时序：0 = 550/550；1 = 550/1650
 *  - 帧尾 mark 550 µs
 *  - **不额外补零**（自然帧长：前导 9000 + 32 位 + 帧尾 550，约 44.8-80ms）；
 *    仅按实现规则追加 1µs 终止 space（规则 2：以 space 结尾，非帧长补零）
 *  - 位序：**每字节 LSB 先发**
 *  - 输入 4 位 hex（16 bit = 自定义 8 位 + 命令 8 位）→ 32 位帧
 *    = 自定义 16bit（自定义 + ~自定义）+ 命令 8bit + ~命令 8bit
 *  - 重复行为：完整帧重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object Samsung32Encoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.SAMSUNG32

    /** SAMSUNG32 无协议级重复间隔覆盖，走全局默认 250ms（完整帧重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 38_000         // SAMSUNG32 载波
    const val PRE_MARK = 4500            // 前导 mark µs
    const val PRE_SPACE = 4500           // 前导 space µs
    const val BIT_MARK = 550             // 位 mark
    const val BIT0_SPACE = 550           // 0 的 space
    const val BIT1_SPACE = 1650          // 1 的 space
    const val TRAIL_MARK = 550           // 帧尾 mark
    const val TRAIL_SPACE = 1            // 终止 space（规则 2：输出以 space 结尾）

    /**
     * 编码 SAMSUNG32 波形。
     *
     * @param hex hex 码串：4 位十六进制（自定义 8 位 + 命令 8 位）；非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press 任意（SAMSUNG32 完整帧重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "SAMSUNG32 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val custom = normalized.substring(0, 2).toInt(16)   // 自定义 8 位
        val command = normalized.substring(2, 4).toInt(16)  // 命令 8 位
        // 32 位帧 = 自定义 + ~自定义 + 命令 + ~命令（每字节 LSB 先发）
        val bytes = intArrayOf(custom, custom.inv() and 0xFF, command, command.inv() and 0xFF)
        val bits = bytes.flatMap { b -> (0..7).map { (b shr it) and 1 } }

        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE          // 前导
        for (b in bits) {                             // 32 位
            list += BIT_MARK
            list += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        list += TRAIL_MARK                           // 帧尾
        list += TRAIL_SPACE                          // 终止 space（不额外补零到帧长）
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
