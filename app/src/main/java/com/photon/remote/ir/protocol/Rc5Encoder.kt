package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType

/**
 * RC5 协议编码器（计划 §3.2）。
 *
 * 时序参数：
 *  - 载波 36000Hz
 *  - 隐含起始位：2 个固定逻辑 1 起始位（每个单元 889µs，mark/space）
 *  - 单元 889µs bi-phase：1 = mark/space；0 = space/mark（相邻同状态段自动合并，
 *    输出为真实发射波形，保证偶数下标为 mark）
 *  - 帧尾 mark 889 µs，整帧补零到 114000 µs
 *  - 位域 **MSB 先发**：起始 11 → 翻转位 → 地址 5 位 → 命令 6 位，共 14 位
 *  - 输入 "AA CC"（4 位 hex）：地址 = AA 的 bit7..bit3（5 位），命令 = CC 的 bit7..bit2（6 位），其余位忽略
 *  - 翻转位随 PressKind：NEW_PRESS=1（翻转）、REPEAT=0（保持）
 *
 * 注意：编码器为无状态单例，翻转位由 [PressKind] 推导（NEW_PRESS 翻转、REPEAT 保持），
 * 实际翻转状态的维护由调用方（RemoteKey / CodeResolver）负责，后续如需连续按压交替翻转可引入带状态实现。
 */
object Rc5Encoder : IrProtocolEncoder {

    override val protocol: ProtocolType = ProtocolType.RC5

    /** RC5 无协议级重复间隔覆盖，走全局默认 250ms */
    override val repeatIntervalMs: Int? = null

    const val FREQUENCY = 36_000         // RC5 载波
    const val UNIT = 889                 // bi-phase 单元时长 µs
    const val TRAIL_MARK = 889           // 帧尾 mark
    const val TOTAL_US = 114_000         // 整帧目标时长（含补零）

    /**
     * 编码 RC5 波形。
     *
     * @param hex hex 码串：4 位十六进制（AA CC 两个字节）；非法字符 / 超过 4 位抛 [IllegalArgumentException]
     * @param press [PressKind.NEW_PRESS] 翻转位 = 1；[PressKind.REPEAT] 翻转位 = 0（保持）
     */
    override fun encode(hex: String, press: PressKind): IRPattern {
        val normalized = hex.trim().uppercase().padStart(4, '0')
        require(normalized.length <= 4) { "RC5 码最多 4 位十六进制: $hex" }
        require(normalized.all { it.isDigit() || it in 'A'..'F' }) { "非法十六进制: $hex" }

        val addrByte = normalized.substring(0, 2).toInt(16)   // AA
        val cmdByte = normalized.substring(2, 4).toInt(16)    // CC
        // 地址 = AA bit7..bit3（5 位），命令 = CC bit7..bit2（6 位），其余位忽略
        val address = (addrByte shr 3) and 0x1F
        val command = (cmdByte shr 2) and 0x3F
        // 翻转位：NEW_PRESS 翻转（=1）、REPEAT 保持（=0），无状态推导，见类注释
        val toggle = if (press == PressKind.NEW_PRESS) 1 else 0

        // 位域 MSB 先发：起始 11 → 翻转位 → 地址 5 位 → 命令 6 位
        val bits = mutableListOf(1, 1, toggle)
        for (i in 4 downTo 0) bits += (address shr i) and 1
        for (i in 5 downTo 0) bits += (command shr i) and 1

        // bi-phase 波形生成：1 = mark/space、0 = space/mark，相邻同状态合并（真实发射信号）
        val list = mutableListOf<Int>()
        var isMark = true        // 当前累计的信号状态（true=mark）
        var acc = 0              // 当前状态累计时长
        fun push(state: Boolean, dur: Int) {
            if (state == isMark) acc += dur
            else { list += acc; isMark = state; acc = dur }
        }
        for (b in bits) {
            if (b == 1) { push(true, UNIT); push(false, UNIT) }    // 1 = mark/space
            else { push(false, UNIT); push(true, UNIT) }           // 0 = space/mark
        }
        push(true, TRAIL_MARK)                                     // 帧尾 mark 889
        val used = list.sum() + acc
        push(false, (TOTAL_US - used).coerceAtLeast(0))            // 补零到整帧 114000
        list += acc
        return IRPattern(FREQUENCY, list.toIntArray())
    }
}
