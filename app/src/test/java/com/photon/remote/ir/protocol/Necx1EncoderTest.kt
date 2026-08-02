package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NECx1 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 4500/4500、首数据位位序（每字节 LSB 先发）、帧尾、总长补零 108800；
 * REPEAT 标准短重复帧（9000/2250+562）、重复间隔 110ms、非法输入异常。
 */
class Necx1EncoderTest {

    @Test
    fun `0x00FF12ED 编码为完整 NECx1 波形`() {
        val pattern = Necx1Encoder.encode("00FF12ED")
        val i = pattern.intervals

        assertEquals(38_400, pattern.frequency)
        assertEquals(68, i.size)   // 前导 2 + 32 位 64 + 帧尾 + 补零

        // 前导 4500/4500（区别于 NEC 的 9000/4500）
        assertEquals(4500, i[0])
        assertEquals(4500, i[1])

        // 首数据位：0x00 字节的 LSB = 0 → mark 562 + space 562
        assertEquals(562, i[2])
        assertEquals(562, i[3])

        // 第 32 位：0xED 字节的 MSB = 1 → space 1687
        assertEquals(562, i[64])
        assertEquals(1687, i[65])

        // 帧尾 562 + 补零到整帧 108800
        assertEquals(562, i[66])
        assertEquals(108_800, i.sum())
    }

    @Test
    fun `REPEAT 输出 NEC 标准短重复帧`() {
        // NECx1 REPEAT 同 NEC：9000/2250 + 562µs mark，不补零
        val pattern = Necx1Encoder.encode("00FF12ED", PressKind.REPEAT)
        assertEquals(38_400, pattern.frequency)
        assertArrayEquals(intArrayOf(9000, 2250, 562), pattern.intervals)
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.NECX1, Necx1Encoder.protocol)
        assertEquals(110, Necx1Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Necx1Encoder.encode("0x00FF12ED") }
        assertThrows(IllegalArgumentException::class.java) { Necx1Encoder.encode("ZZZZ") }
        assertThrows(IllegalArgumentException::class.java) { Necx1Encoder.encode("12G4") }
        assertThrows(IllegalArgumentException::class.java) { Necx1Encoder.encode("0123456789ABCDEF1") }
    }

    @Test
    fun `小写输入自动转大写且左补零`() {
        val pattern = Necx1Encoder.encode("ff12ed")
        assertEquals(108_800, pattern.intervals.sum())
        assertEquals(4500, pattern.intervals[0])
        assertEquals(562, pattern.intervals[3])  // 首数据位仍为 0
    }
}
