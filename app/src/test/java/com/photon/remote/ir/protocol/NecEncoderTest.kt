package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEC 协议编码器单元测试（计划 §3.2 / Todo 3 验收基准）。
 *
 * 断言完整波形：前导、首数据位位序（每字节 LSB 先发）、第 32 位、帧尾、总长补零；
 * 以及 REPEAT 短重复帧、重复间隔、非法输入异常。
 */
class NecEncoderTest {

    @Test
    fun `0x00FF12ED 编码为完整 NEC 波形`() {
        val pattern = NecEncoder.encode("00FF12ED")
        val i = pattern.intervals

        // 载波与帧结构：前导 2 项 + 32 位数据 64 项 + 帧尾 562 + 补零 space = 68 项（偶数）
        assertEquals(38_000, pattern.frequency)
        assertEquals(68, i.size)

        // 前导 9000/4500
        assertEquals(9000, i[0])
        assertEquals(4500, i[1])

        // 首数据位：0x00 字节的 LSB = 0 → mark 562 + space 562
        assertEquals(562, i[2])
        assertEquals(562, i[3])

        // 第 32 位（最后一个数据位）：0xED 字节的 MSB = 1 → mark 562 + space 1687
        assertEquals(562, i[64])
        assertEquals(1687, i[65])

        // 帧尾 562 + 补零到整帧 108800
        assertEquals(562, i[66])
        assertTrue(i[67] > 0)
        assertEquals(108_800, i.sum())
    }

    @Test
    fun `每字节 LSB 先发位序正确`() {
        // 0x00FF12ED → 地址 0x00、反码 0xFF、命令 0x12、反码 0xED
        // 每字节 LSB 先发：00000000 11111111 01001000 10110111
        val pattern = NecEncoder.encode("00FF12ED")
        val bits = (0 until 32).joinToString("") { bitIndex ->
            // 数据位从下标 2 开始，每 bit 占 mark + space 两格
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(562, mark)
            if (space == 1687) "1" else "0"
        }
        assertEquals("00000000111111110100100010110111", bits)
    }

    @Test
    fun `REPEAT 输出标准短重复帧`() {
        // NEC 规范重复帧：9000/2250 + 562µs mark，不补零到 108800
        val pattern = NecEncoder.encode("00FF12ED", PressKind.REPEAT)
        assertEquals(38_000, pattern.frequency)
        assertArrayEquals(intArrayOf(9000, 2250, 562), pattern.intervals)
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.NEC, NecEncoder.protocol)
        // NEC 长按重复间隔 110ms（§3.2 表）
        assertEquals(110, NecEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        // 带 0x 前缀（含非法字符 x）
        assertThrows(IllegalArgumentException::class.java) { NecEncoder.encode("0x00FF12ED") }
        // 非法字符
        assertThrows(IllegalArgumentException::class.java) { NecEncoder.encode("ZZZZ") }
        assertThrows(IllegalArgumentException::class.java) { NecEncoder.encode("12G4") }
        // 超过 8 位
        assertThrows(IllegalArgumentException::class.java) { NecEncoder.encode("0123456789ABCDEF1") }
    }

    @Test
    fun `小写输入自动转大写且左补零`() {
        // 与 "00FF12ED" 等价（小写 + 省略前导零）
        val pattern = NecEncoder.encode("ff12ed")
        assertEquals(108_800, pattern.intervals.sum())
        assertEquals(562, pattern.intervals[3])  // 首数据位仍为 0
    }
}
