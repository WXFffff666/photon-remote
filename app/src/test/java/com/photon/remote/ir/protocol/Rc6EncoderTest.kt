package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RC6 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 2664/888、起始位 1、翻转位随 PressKind（NEW_PRESS=1 / REPEAT=0）、
 * 地址域 MSB 先发（AA bit7 为地址域首发的第一位）、帧尾 444×6、总长 24864、非法输入异常。
 *
 * 注：bi-phase 编码（1=mark/space，0=space/mark）中 0 位的首 space 与前一单元尾 space
 * 合并为真实发射波形——翻转位/位序差异通过合并后的下标值断言。
 */
class Rc6EncoderTest {

    @Test
    fun `AA=0x01 CC=0x00 编码为完整 RC6 波形`() {
        val pattern = Rc6Encoder.encode("0100")
        val i = pattern.intervals

        assertEquals(36_000, pattern.frequency)

        // 前导 2664/888
        assertEquals(2664, i[0])
        assertEquals(888, i[1])

        // 起始位 1 → mark 444
        assertEquals(444, i[2])
        // 起始位尾 space 与模式首 0 位的 space 合并 → 888
        assertEquals(888, i[3])

        // 翻转位 = 1（NEW_PRESS）：模式尾 mark 与翻转位 mark 合并 → i[8] = 888
        assertEquals(888, i[8])

        // 帧尾 444×6（3 对 mark/space）以 space 结尾；整帧无补零，
        // 总长 = 前导 3552 + 21 位 18648 + 帧尾 2664 = 24864
        assertEquals(444, i.last())
        assertEquals(24_864, i.sum())
    }

    @Test
    fun `REPEAT 翻转位保持 0 与 NEW_PRESS 翻转位 1 波形不同`() {
        // NEW_PRESS（翻转位=1）：模式尾 mark 与翻转位 mark 合并 → i[8] = 888
        val newPress = Rc6Encoder.encode("0100", PressKind.NEW_PRESS)
        // REPEAT（翻转位=0）：0 位 = space/mark，无合并 → i[8] = 444
        val repeat = Rc6Encoder.encode("0100", PressKind.REPEAT)

        assertEquals(888, newPress.intervals[8])
        assertEquals(444, repeat.intervals[8])
        assertEquals(24_864, repeat.intervals.sum())
    }

    @Test
    fun `地址域 MSB 先发 - AA bit7 是地址域首发的第一位`() {
        // AA=0x80 → a7 = 1（mark/space 不合并）→ i[9] = 444
        val msb1 = Rc6Encoder.encode("8000")
        // AA=0x01 → a7 = 0（space 合并）→ i[9] = 888
        val msb0 = Rc6Encoder.encode("0100")

        assertEquals(444, msb1.intervals[9])
        assertEquals(888, msb0.intervals[9])
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.RC6, Rc6Encoder.protocol)
        assertEquals(null, Rc6Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Rc6Encoder.encode("0x0100") }
        assertThrows(IllegalArgumentException::class.java) { Rc6Encoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { Rc6Encoder.encode("12345") }
    }
}
