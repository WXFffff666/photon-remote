package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * KASEIKYO（Panasonic）协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 37000Hz
 *  - 前导 mark/space = 3456/1728 µs
 *  - 位时序：0 = 432/432；1 = 432/1296
 *  - 帧尾 mark 432 µs；不额外补零（按实现规则追加 1µs 终止 space）
 *  - 位序：**整体 LSB 先发**（48 位）
 *  - 48 位帧：16bit 厂商 + 8bit parity + 8bit 设备 + 16bit 命令；
 *    parity = XOR(厂商高字节, 厂商低字节)（由编码器计算，覆盖输入中的 parity 槽位）
 *  - 输入 12 位 hex → 48 bit（布局 = 厂商 4hex + parity 2hex + 设备 2hex + 命令 4hex）
 *  - 重复行为：完整帧重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object KaseikyoEncoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.KASEIKYO

    /** KASEIKYO 无协议级重复间隔覆盖，走全局默认 250ms（完整帧重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 37_000         // KASEIKYO 载波
    const val PRE_MARK = 3456            // 前导 mark µs
    const val PRE_SPACE = 1728           // 前导 space µs
    const val BIT_MARK = 432             // 位 mark
    const val BIT0_SPACE = 432           // 0 的 space
    const val BIT1_SPACE = 1296          // 1 的 space
    const val TRAIL_MARK = 432           // 帧尾 mark
    const val TRAIL_SPACE = 1            // 终止 space（规则 2：输出以 space 结尾）

    /**
     * 编码 KASEIKYO 48 位波形。
     *
     * @param hex hex 码串：12 位十六进制，布局 = 厂商 16bit + parity 8bit（将被计算值覆盖）+
     *            设备 8bit + 命令 16bit；非法字符 / 不足 12 位以外的输入抛 [IllegalArgumentException]
     * @param press 任意（KASEIKYO 完整帧重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(12, '0')
        require(normalized.length <= 12) { "KASEIKYO 码最多 12 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val vendor = normalized.substring(0, 4).toInt(16)      // 厂商 16 位
        val device = normalized.substring(6, 8).toInt(16)      // 设备 8 位（跳过输入 parity 槽位）
        val command = normalized.substring(8, 12).toInt(16)    // 命令 16 位
        // parity = XOR(厂商高字节, 厂商低字节)，由编码器计算并覆盖输入中的 parity 槽位
        val parity = ((vendor shr 8) and 0xFF) xor (vendor and 0xFF)

        // 48 位帧 = 厂商 16 + parity 8 + 设备 8 + 命令 16（整体 LSB 先发）
        val frame = ((vendor.toLong() shl 32) or
            (parity.toLong() shl 24) or
            (device.toLong() shl 16) or
            command.toLong()) and 0xFFFF_FFFF_FFFFL
        val bits = (0 until 48).map { ((frame shr it) and 1L).toInt() }

        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE          // 前导
        for (b in bits) {                             // 48 位
            list += BIT_MARK
            list += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        list += TRAIL_MARK                           // 帧尾
        list += TRAIL_SPACE                          // 终止 space
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
