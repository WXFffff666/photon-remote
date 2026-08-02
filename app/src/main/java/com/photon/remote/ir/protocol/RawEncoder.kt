package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * RAW 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 38000Hz
 *  - **原样**：不作物位序转换，直接输出 mark/space 列表
 *  - 输入：空格 / 逗号分隔的整数（µs），如 "9000 4500 562 562" 或 "9000,4500,562,562"
 *  - 若输入以 mark 结尾（奇数长度）自动追加尾 space（规则 2：以 space 结尾）
 *  - 重复行为：完整重发（PressKind 不影响波形），无协议级重复间隔覆盖
 *
 * 无状态编码器，使用单例 object。
 */
object RawEncoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.RAW

    /** RAW 无协议级重复间隔覆盖，走全局默认 250ms（完整重发） */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 38_000         // RAW 默认载波
    const val TRAIL_SPACE = 1            // 奇数长度时追加的尾 space

    /**
     * 编码 RAW 波形。
     *
     * @param hex 空格 / 逗号分隔的正整数 mark/space 列表（µs）；空串 / 非整数 / 非正数抛 [IllegalArgumentException]
     * @param press 任意（RAW 完整重发，PressKind 不影响波形）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val tokens = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "RAW 码不能为空: '$hex'" }
        val intervals = tokens.map { token ->
            val v = token.toIntOrNull()
            require(v != null) { "RAW 码必须为整数（空格/逗号分隔）: '$token'" }
            require(v > 0) { "RAW 码时长必须为正数: $token" }
            v
        }
        // 奇数长度 = 以 mark 结尾 → 自动追加尾 space（保持偶数下标为 mark）
        val list = intervals.toMutableList()
        if (list.size % 2 == 1) list += TRAIL_SPACE
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
