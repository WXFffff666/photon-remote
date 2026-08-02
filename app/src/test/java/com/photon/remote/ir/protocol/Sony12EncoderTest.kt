package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SONY 12 位协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 2400/600、首数据位位序（整体 LSB 先发：0x1D bit0 = 1 首发）、
 * 删尾（无帧尾 mark）、总长补零 45000、完整帧重发、非法输入异常。
 */
class Sony12EncoderTest {

    @Test
    fun `0x1D 编码为完整 SONY 12 位波形`() {
        val pattern = Sony12Encoder.encode("1D")
        val i = pattern.intervals

        assertEquals(40_000, pattern.frequency)
        // 前导 2 + 12 位 24 + 补零 = 27 项（偶数）
        assertEquals(27, i.size)

        // 前导 2400/600
        assertEquals(2400, i[0])
        assertEquals(600, i[1])

        // 首数据位：整体 LSB 先发 → 0x1D 的 bit0 = 1 → mark 600 + space 1200
        assertEquals(600, i[2])
        assertEquals(1200, i[3])

        // 删尾：第 12 位（bit11 = 0）后直接补零 space（无帧尾 mark）
        assertEquals(600, i[24])
        assertEquals(600, i[25])
        // 总长补零到 45000；已用 = 前导 3000 + 12×600 + space(4×1200 + 8×600=9600) = 19800
        assertEquals(25_200, i[26])
        assertEquals(45_000, i.sum())
    }

    @Test
    fun `整体 LSB 先发位序正确`() {
        // 0x1D = 000000011101，LSB 先发 → 首 4 位 = 1,0,1,1
        val pattern = Sony12Encoder.encode("1D")
        val bits = (0 until 12).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(600, mark)
            if (space == 1200) "1" else "0"
        }
        assertEquals("101110000000", bits)
    }

    @Test
    fun `完整帧重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            Sony12Encoder.encode("1D", PressKind.NEW_PRESS),
            Sony12Encoder.encode("1D", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.SONY12, Sony12Encoder.protocol)
        assertEquals(null, Sony12Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Sony12Encoder.encode("0x1D") }
        assertThrows(IllegalArgumentException::class.java) { Sony12Encoder.encode("GGG") }
        assertThrows(IllegalArgumentException::class.java) { Sony12Encoder.encode("1234") }  // 超过 3 位
    }
}
