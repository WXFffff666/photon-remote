package com.photon.remote.codebase.importer

import com.photon.remote.data.model.ButtonAction
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlipperIrParser 单元测试（计划 §4.2 / Flipper .ir 导入验收）。
 *
 * 覆盖 raw / parsed 两类信号解析、损坏条目跳过、未知协议拒绝。
 */
class FlipperIrParserTest {

    /** 解析含 2 个 raw 信号 + 1 个损坏信号的示例：频率/间隔正确，损坏条目标记并跳过 */
    @Test
    fun 解析raw示例_频率与间隔正确_损坏信号跳过() {
        val content = """
            Filetype: IR signals file
            Version: 1
            #
            name: Power
            type: raw
            frequency: 38000
            duty_cycle: 0.33
            data: 9000 4500 560 560 560 1690
            #
            name: VolUp
            type: raw
            frequency: 38000
            duty_cycle: 0.33
            data: 9000 4500 560 1690 560 560
            #
            name: Broken
            type: raw
            frequency: abc
            duty_cycle: 0.33
            data: 9000 xyz 560
        """.trimIndent()

        val signals = FlipperIrParser.parse(content)

        // 损坏信号（frequency 非数字）被跳过，仅剩 2 个有效信号
        assertEquals("应跳过损坏信号，剩余 2 条", 2, signals.size)

        // 第一条：Power
        assertEquals("Power", signals[0].name)
        val power = signals[0].action as ButtonAction.SendRaw
        assertEquals("频率应为 38000", 38000, power.frequency)
        assertEquals("间隔应为数据序列", listOf(9000, 4500, 560, 560, 560, 1690), power.intervals)

        // 第二条：VolUp
        assertEquals("VolUp", signals[1].name)
        val volUp = signals[1].action as ButtonAction.SendRaw
        assertEquals("频率应为 38000", 38000, volUp.frequency)
        assertEquals("间隔应为数据序列", listOf(9000, 4500, 560, 1690, 560, 560), volUp.intervals)
    }

    /** 解析 parsed NEC 信号 → SendProtocol.hex 为 address 与 command 去空格拼接 */
    @Test
    fun 解析parsedNEC_协议映射与hex拼接正确() {
        val content = """
            Filetype: IR signals file
            Version: 1
            #
            name: Power
            type: parsed
            protocol: NEC
            address: 00 00 00 00
            command: 01 02 03 04
        """.trimIndent()

        val signals = FlipperIrParser.parse(content)

        assertEquals("应解析出 1 条", 1, signals.size)
        assertEquals("Power", signals[0].name)
        val parsed = signals[0].action as ButtonAction.SendProtocol
        assertEquals("协议应映射为 NEC", ProtocolType.NEC, parsed.protocol)
        assertEquals("hex 应为 address+command 拼接", "0000000001020304", parsed.hex)
    }

    /** 未知协议（如 Samsung36）无法映射 → 拒绝该条并跳过 */
    @Test
    fun 解析parsed未知协议_拒绝该条并跳过() {
        val content = """
            Filetype: IR signals file
            Version: 1
            #
            name: UnknownProto
            type: parsed
            protocol: Samsung36
            address: 00 00 00 00
            command: 01 02 03 04
        """.trimIndent()

        val signals = FlipperIrParser.parse(content)

        assertTrue("未知协议信号应被跳过", signals.isEmpty())
    }

    /** parsed 信号缺 command 字段 → 视为损坏跳过 */
    @Test
    fun 解析parsed缺字段_跳过该条() {
        val content = """
            Filetype: IR signals file
            Version: 1
            #
            name: NoCommand
            type: parsed
            protocol: NEC
            address: 00 00 00 00
        """.trimIndent()

        val signals = FlipperIrParser.parse(content)

        assertTrue("缺 command 字段的信号应被跳过", signals.isEmpty())
    }
}
