package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RC5 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：隐含起始位（2×889 mark）、翻转位随 PressKind（NEW_PRESS=1 / REPEAT=0）、
 * 地址域 MSB 先发（AA bit7 为地址域首发的第一位）、帧尾 889、总长补零 114000、非法输入异常。
 *
 * 注：bi-phase 编码（1=mark/space，0=space/mark）中 0 位的首 space 与前一单元尾 space
 * 合并为真实发射波形——翻转位/位序差异通过合并后的下标值断言。
 */
class Rc5EncoderTest {

    @Test
    fun `AA=0x80 CC=0x04 编码为完整 RC5 波形`() {
        // 位域：起始 11 → 翻转位 1 → 地址 10000 → 命令 000001
        val pattern = Rc5Encoder.encode("8004")
        val i = pattern.intervals

        assertEquals(36_000, pattern.frequency)
        // 14 位 ×2 = 28 槽 - 2 处合并 + 帧尾 + 补零 = 28 项（偶数）
        assertEquals(28, i.size)

        // 隐含起始位：2 个逻辑 1 → 2×889 mark
        assertEquals(889, i[0])
        assertEquals(889, i[1])
        assertEquals(889, i[2])
        assertEquals(889, i[3])

        // 翻转位 = 1（NEW_PRESS）→ mark/space，不合并
        assertEquals(889, i[4])
        assertEquals(889, i[5])

        // 地址域 MSB 先发：AA=0x80 → 地址 = bit7..bit3 = 10000 → 首位 a4 = 1（mark 不合并）
        assertEquals(889, i[6])
        // a3 = 0：首 space 与 a4 的尾 space 合并 → 1778
        assertEquals(1778, i[7])

        // 帧尾 mark 889 + 补零 space 到整帧 114000（已用 25781）
        assertEquals(889, i[26])
        assertEquals(88_219, i[27])
        assertEquals(114_000, i.sum())
    }

    @Test
    fun `REPEAT 翻转位保持 0 与 NEW_PRESS 翻转位 1 波形不同`() {
        // NEW_PRESS（翻转位=1）：起始位后紧跟 mark/space，i[3] 仍为 889
        val newPress = Rc5Encoder.encode("8004", PressKind.NEW_PRESS)
        // REPEAT（翻转位=0）：0 位 = space/mark，首 space 与起始位尾 space 合并 → i[3] = 1778
        val repeat = Rc5Encoder.encode("8004", PressKind.REPEAT)

        assertEquals(889, newPress.intervals[3])
        assertEquals(889, newPress.intervals[4])
        assertEquals(1778, repeat.intervals[3])
        assertEquals(1778, repeat.intervals[4])
        assertEquals(114_000, repeat.intervals.sum())
    }

    @Test
    fun `地址域 MSB 先发 - AA bit7 是地址域首发的第一位`() {
        // AA=0x80 → 地址 = 10000 → a4 = 1：翻转位后第一对 mark/space 不合并 → i[5] = 889
        val msb1 = Rc5Encoder.encode("8004")
        // AA=0x00 → 地址 = 00000 → a4 = 0：首 space 与翻转位尾 space 合并 → i[5] = 1778
        val msb0 = Rc5Encoder.encode("0004")

        assertEquals(889, msb1.intervals[5])
        assertEquals(1778, msb0.intervals[5])
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.RC5, Rc5Encoder.protocol)
        assertEquals(null, Rc5Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Rc5Encoder.encode("0x8004") }
        assertThrows(IllegalArgumentException::class.java) { Rc5Encoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { Rc5Encoder.encode("12345") }
    }
}
