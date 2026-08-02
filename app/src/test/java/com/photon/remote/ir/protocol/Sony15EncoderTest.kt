package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SONY 15 位协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 2400/600、首数据位位序（整体 LSB 先发：0x001D bit0 = 1 首发）、
 * 删尾、总长补零 45000、完整帧重发、非法输入异常。
 */
class Sony15EncoderTest {

    @Test
    fun `0x001D 编码为完整 SONY 15 位波形`() {
        val pattern = Sony15Encoder.encode("001D")
        val i = pattern.intervals

        assertEquals(40_000, pattern.frequency)
        // 前导 2 + 15 位 30 + 补零 = 33 项（偶数）
        assertEquals(33, i.size)

        // 前导 2400/600
        assertEquals(2400, i[0])
        assertEquals(600, i[1])

        // 首数据位：整体 LSB 先发 → 0x001D 的 bit0 = 1 → mark 600 + space 1200
        assertEquals(600, i[2])
        assertEquals(1200, i[3])

        // 第 15 位（bit14 = 0）后直接补零 space（删尾）
        assertEquals(600, i[30])
        assertEquals(600, i[31])
        // 已用 = 前导 3000 + 15×600 + space(4×1200 + 11×600=11400) = 23400
        assertEquals(21_600, i[32])
        assertEquals(45_000, i.sum())
    }

    @Test
    fun `整体 LSB 先发位序正确`() {
        // 0x001D 低 12 位 = 000000011101，15 位整体 LSB 先发 → 首 4 位 = 1,0,1,1，末 3 位 = 0,0,0
        val pattern = Sony15Encoder.encode("001D")
        val bits = (0 until 15).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(600, mark)
            if (space == 1200) "1" else "0"
        }
        assertEquals("101110000000000", bits)
    }

    @Test
    fun `4 位 hex 取低 15 位`() {
        // 0x8001 的 bit15 被丢弃（15 位掩码）→ 0x0001，首数据位 = 1
        val pattern = Sony15Encoder.encode("8001")
        assertEquals(1200, pattern.intervals[3])
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.SONY15, Sony15Encoder.protocol)
        assertEquals(null, Sony15Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Sony15Encoder.encode("0x001D") }
        assertThrows(IllegalArgumentException::class.java) { Sony15Encoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { Sony15Encoder.encode("12345") }  // 超过 4 位
    }
}
