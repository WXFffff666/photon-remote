package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * PIONEER 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 40000Hz
 *  - 前导 mark/space = 8500/4225 µs
 *  - 位时序：0 = 500/500；1 = 500/1500
 *  - **隐式停止位**：无显式帧尾 mark，帧以最后一个数据位的 space 结束
 *  - 26000µs 静默 + 整帧 ×2（每帧后跟 26000µs 静默，输出以静默 space 结尾）
 *  - 位序：**整体 LSB 先发**（32 位）
 *  - 输入 8 位 hex → 32 bit（Addr8 + ~Addr8 + Cmd8 + ~Cmd8，补码字节由输入直接提供）
 *  - 重复行为：完整双帧重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object PioneerEncoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.PIONEER

    /** PIONEER 无协议级重复间隔覆盖，走全局默认 250ms（完整双帧重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 40_000         // PIONEER 载波
    const val PRE_MARK = 8500            // 前导 mark µs
    const val PRE_SPACE = 4225           // 前导 space µs
    const val BIT_MARK = 500             // 位 mark
    const val BIT0_SPACE = 500           // 0 的 space
    const val BIT1_SPACE = 1500          // 1 的 space
    const val SILENCE = 26_000           // 帧间/帧尾静默 µs

    /**
     * 编码 PIONEER 波形（整帧 ×2，各跟 26000µs 静默）。
     *
     * @param hex hex 码串：8 位十六进制，左补 0 到 32 位（Addr8 + ~Addr8 + Cmd8 + ~Cmd8）；
     *            非法字符 / 超过 8 位抛 [IllegalArgumentException]
     * @param press 任意（PIONEER 完整双帧重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(8, '0')
        require(normalized.length <= 8) { "PIONEER 码最多 8 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        // 32 位整体 LSB 先发（隐式停止位：无帧尾 mark，帧以最后一个数据位的 space 结束）
        val value = normalized.toLong(16) and 0xFFFF_FFFFL
        val bits = (0 until 32).map { ((value shr it) and 1L).toInt() }

        // 单帧：前导 + 32 位（隐式停止位，无帧尾 mark）+ 26000µs 静默
        val single = mutableListOf<Int>()
        single += PRE_MARK; single += PRE_SPACE
        for (b in bits) {
            single += BIT_MARK
            single += if (b == 0) BIT0_SPACE else BIT1_SPACE
        }
        single += SILENCE

        // 整帧 ×2
        val list = mutableListOf<Int>()
        list += single
        list += single
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
