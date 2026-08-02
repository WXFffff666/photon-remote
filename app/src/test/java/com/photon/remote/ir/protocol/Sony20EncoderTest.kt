package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SONY 20 位协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 2400/600、首数据位位序（整体 LSB 先发：0x0001D bit0 = 1 首发）、
 * 删尾、总长补零 45000、完整帧重发、非法输入异常。
 */
class Sony20EncoderTest {

    @Test
    fun `0x0001D 编码为完整 SONY 20 位波形`() {
        val pattern = Sony20Encoder.encode("0001D")
        val i = pattern.intervals

        assertEquals(40_000, pattern.frequency)
        // 前导 2 + 20 位 40 + 补零 = 43 项（偶数）
        assertEquals(43, i.size)

        // 前导 2400/600
        assertEquals(2400, i[0])
        assertEquals(600, i[1])

        // 首数据位：整体 LSB 先发 → 0x0001D 的 bit0 = 1 → mark 600 + space 1200
        assertEquals(600, i[2])
        assertEquals(1200, i[3])

        // 第 20 位（bit19 = 0）后直接补零 space（删尾）
        assertEquals(600, i[40])
        assertEquals(600, i[41])
        // 已用 = 前导 3000 + 20×600 + space(4×1200 + 16×600=14400) = 29400
        assertEquals(15_600, i[42])
        assertEquals(45_000, i.sum())
    }

    @Test
    fun `整体 LSB 先发位序正确`() {
        // 0x0001D 低 12 位 = 000000011101，20 位整体 LSB 先发 → 首 4 位 = 1,0,1,1，末 8 位 = 0
        val pattern = Sony20Encoder.encode("0001D")
        val bits = (0 until 20).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(600, mark)
            if (space == 1200) "1" else "0"
        }
        assertEquals("10111000000000000000", bits)
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.SONY20, Sony20Encoder.protocol)
        assertEquals(null, Sony20Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Sony20Encoder.encode("0x0001D") }
        assertThrows(IllegalArgumentException::class.java) { Sony20Encoder.encode("GGGGG") }
        assertThrows(IllegalArgumentException::class.java) { Sony20Encoder.encode("123456") }  // 超过 5 位
    }
}
