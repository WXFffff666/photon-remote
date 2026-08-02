package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SHARP 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：无独立前导（直接发数据位）、首数据位位序（整体 LSB 先发：命令 bit0 首发）、
 * 双段结构（第二段命令取反）、两段间 ~40ms、帧尾 280、完整双帧重发、非法输入异常。
 */
class SharpEncoderTest {

    @Test
    fun `0x0100 编码为完整 SHARP 双段波形`() {
        // 4 hex → 13 位值：bit12..bit8 = 地址 0x01，bit7..bit0 = 命令 0x00
        val pattern = SharpEncoder.encode("0100")
        val i = pattern.intervals

        assertEquals(38_000, pattern.frequency)
        // 第一段 27（13 位 26 + 帧尾）+ 间隔 + 第二段 27 + 终止 space = 56 项（偶数）
        assertEquals(56, i.size)

        // 无独立前导：直接发数据位；首数据位 = 命令 bit0 = 0 → mark 280 + space 860
        assertEquals(280, i[0])
        assertEquals(860, i[1])

        // 第一段帧尾 280 → 两段间 40000µs
        assertEquals(280, i[26])
        assertEquals(40_000, i[27])

        // 第二段：命令取反 0xFF → 首数据位 = 1 → space 1720
        assertEquals(280, i[28])
        assertEquals(1720, i[29])

        // 第二段帧尾 280 + 终止 space
        assertEquals(280, i[54])
        assertEquals(1, i[55])
        // 总长 = 15960 + 40000 + 22840 + 1
        assertEquals(78_801, i.sum())
    }

    @Test
    fun `第二段命令取反且地址不变`() {
        // 0x0110：地址 0x01、命令 0x10 → 第二段命令 0xEF
        val pattern = SharpEncoder.encode("0110")
        val i = pattern.intervals
        // 第一段命令 bit7 = 0（0x10 的 bit7=0）→ 数据位下标 7 → 元素 14/15 → space 860
        assertEquals(280, i[14])
        assertEquals(860, i[15])
        // 第二段命令 bit7 = 1（~0x10 = 0xEF 的 bit7=1）→ 第二段从元素 28 起，数据位下标 7 → 元素 42/43 → space 1720
        assertEquals(280, i[42])
        assertEquals(1720, i[43])
        // 地址保持：第一段地址位（元素 16..25）与第二段地址位（元素 44..53）一致
        assertEquals(i[17], i[45])
        assertEquals(i[19], i[47])
        assertEquals(i[21], i[49])
        assertEquals(i[23], i[51])
        assertEquals(i[25], i[53])
    }

    @Test
    fun `完整双帧重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            SharpEncoder.encode("0100", PressKind.NEW_PRESS),
            SharpEncoder.encode("0100", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.SHARP, SharpEncoder.protocol)
        assertEquals(null, SharpEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { SharpEncoder.encode("0x0100") }
        assertThrows(IllegalArgumentException::class.java) { SharpEncoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { SharpEncoder.encode("12345") }  // 超过 4 位
    }
}
