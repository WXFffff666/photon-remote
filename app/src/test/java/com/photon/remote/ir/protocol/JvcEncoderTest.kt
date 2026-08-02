package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVC 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 8400/4200、首数据位位序（整体 LSB 先发）、帧尾 525、21000 gap；
 * REPEAT 仅发 525µs mark 短爆发、重复间隔 110ms、非法输入异常。
 */
class JvcEncoderTest {

    @Test
    fun `0x0402 编码为完整 JVC 波形`() {
        val pattern = JvcEncoder.encode("0402")
        val i = pattern.intervals

        assertEquals(38_000, pattern.frequency)
        // 前导 2 + 16 位 32 + 帧尾 + gap = 36 项（偶数）
        assertEquals(36, i.size)

        // 前导 8400/4200
        assertEquals(8400, i[0])
        assertEquals(4200, i[1])

        // 首数据位：整体 LSB 先发 → 0x0402 的 bit0 = 0 → mark 525 + space 525
        assertEquals(525, i[2])
        assertEquals(525, i[3])

        // 帧尾 525 + 21000 gap
        assertEquals(525, i[34])
        assertEquals(21_000, i[35])
        // 总长 = 12600 + 8400 + space(2×1575 + 14×525=10500) + 525 + 21000
        assertEquals(53_025, i.sum())
    }

    @Test
    fun `整体 LSB 先发位序正确`() {
        // 0x0402 = 0000010000000010 → 整体 LSB 先发：bit1 = 1、bit9 = 1
        val pattern = JvcEncoder.encode("0402")
        val bits = (0 until 16).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(525, mark)
            if (space == 1575) "1" else "0"
        }
        assertEquals("0100000000100000", bits)
    }

    @Test
    fun `REPEAT 仅发 525 微秒 mark 短爆发`() {
        // JVC 标准重复帧 = 仅 525µs mark 短爆发，与 hex 无关
        val pattern = JvcEncoder.encode("0402", PressKind.REPEAT)
        assertEquals(38_000, pattern.frequency)
        assertArrayEquals(intArrayOf(525), pattern.intervals)
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.JVC, JvcEncoder.protocol)
        // JVC 重复间隔按协议覆盖为 110ms（短爆发，不受统一 250ms 限制）
        assertEquals(110, JvcEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { JvcEncoder.encode("0x0402") }
        assertThrows(IllegalArgumentException::class.java) { JvcEncoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { JvcEncoder.encode("12345") }  // 超过 4 位
    }
}
