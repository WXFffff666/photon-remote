package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * RC6 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 36000Hz
 *  - 前导 mark/space = 2664/888 µs
 *  - 单元 444µs bi-phase：1 = mark/space；0 = space/mark（相邻同状态段自动合并，
 *    输出为真实发射波形，保证偶数下标为 mark）
 *  - 帧尾 444×6：3 对 444µs mark/space（共 6 个 444µs 元素，以 space 结尾）
 *  - 位域 **MSB 先发**：起始 1 → 模式 000 → 翻转位 → 地址 8 位 → 命令 8 位，共 21 位
 *  - 输入 4 位 hex（AA CC）：AA = 地址 8 位，CC = 命令 8 位（8+8 扩展约定）
 *  - 翻转位随 PressKind：NEW_PRESS=1（翻转）、REPEAT=0（保持）
 *
 * 注意：编码器为无状态单例，翻转位由 [PressKind] 推导（NEW_PRESS 翻转、REPEAT 保持），
 * 实际翻转状态的维护由调用方（RemoteKey / CodeResolver）负责，后续如需连续按压交替翻转可引入带状态实现。
 */
object Rc6Encoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.RC6

    /** RC6 无协议级重复间隔覆盖，走全局默认 250ms */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 36_000         // RC6 载波
    const val PRE_MARK = 2664            // 前导 mark µs
    const val PRE_SPACE = 888            // 前导 space µs
    const val UNIT = 444                 // bi-phase 单元时长 µs
    const val TRAIL_PAIRS = 3            // 帧尾 444×6 = 3 对 mark/space

    /**
     * 编码 RC6 波形。
     *
     * @param hex hex 码串：4 位十六进制（AA CC 两个字节）；非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press [PressKind.NEW_PRESS] 翻转位 = 1；[PressKind.REPEAT] 翻转位 = 0（保持）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "RC6 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val address = normalized.substring(0, 2).toInt(16)   // AA = 地址 8 位
        val command = normalized.substring(2, 4).toInt(16)   // CC = 命令 8 位
        // 翻转位：NEW_PRESS 翻转（=1）、REPEAT 保持（=0），无状态推导，见类注释
        val toggle = if (press == PressKind.NEW_PRESS) 1 else 0

        // 位域 MSB 先发：起始 1 → 模式 000 → 翻转位 → 地址 8 位 → 命令 8 位
        val bits = mutableListOf(1, 0, 0, 0, toggle)
        for (i in 7 downTo 0) bits += (address shr i) and 1
        for (i in 7 downTo 0) bits += (command shr i) and 1

        // bi-phase 波形生成：1 = mark/space、0 = space/mark，相邻同状态合并（真实发射信号）
        val list = mutableListOf<Int>()
        list += PRE_MARK; list += PRE_SPACE                  // 前导 2664/888
        var isMark = true                                    // 当前累计的信号状态（true=mark）
        var acc = 0                                          // 当前状态累计时长
        fun push(state: Boolean, dur: Int) {
            if (state == isMark) acc += dur
            else { list += acc; isMark = state; acc = dur }
        }
        for (b in bits) {
            if (b == 1) { push(true, UNIT); push(false, UNIT) }    // 1 = mark/space
            else { push(false, UNIT); push(true, UNIT) }           // 0 = space/mark
        }
        // 帧尾 444×6 = 3 对（mark/space），以 space 结尾
        repeat(TRAIL_PAIRS) { push(true, UNIT); push(false, UNIT) }
        list += acc
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
