package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * NECx2 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：两帧、每帧各自补零到 108800（合计 217600）、首数据位位序（每字节 LSB 先发）；
 * REPEAT 标准短重复帧（9000/2250+562）、重复间隔 110ms、非法输入异常。
 */
class Necx2EncoderTest {

    @Test
    fun `0x00FF12ED 编码为两帧完整 NECx2 波形`() {
        val pattern = Necx2Encoder.encode("00FF12ED")
        val i = pattern.intervals

        assertEquals(38_400, pattern.frequency)
        // 单帧 68 项 × 2 帧
        assertEquals(136, i.size)

        // 第一帧前导 4500/4500，首数据位 = 0x00 LSB = 0
        assertEquals(4500, i[0])
        assertEquals(4500, i[1])
        assertEquals(562, i[2])
        assertEquals(562, i[3])
        // 第一帧帧尾 + 补零
        assertEquals(562, i[66])
        assertEquals(108_800, i.copyOfRange(0, 68).sum())

        // 第二帧从下标 68 开始，前导与第一帧一致
        assertEquals(4500, i[68])
        assertEquals(4500, i[69])
        assertEquals(562, i[70])
        assertEquals(562, i[71])
        assertEquals(562, i[134])
        assertEquals(108_800, i.copyOfRange(68, 136).sum())

        // 两帧各自补零 → 合计约 217ms
        assertEquals(217_600, i.sum())
    }

    @Test
    fun `REPEAT 输出 NEC 标准短重复帧`() {
        val pattern = Necx2Encoder.encode("00FF12ED", PressKind.REPEAT)
        assertEquals(38_400, pattern.frequency)
        assertArrayEquals(intArrayOf(9000, 2250, 562), pattern.intervals)
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.NECX2, Necx2Encoder.protocol)
        assertEquals(110, Necx2Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Necx2Encoder.encode("0x00FF12ED") }
        assertThrows(IllegalArgumentException::class.java) { Necx2Encoder.encode("ZZZZ") }
        assertThrows(IllegalArgumentException::class.java) { Necx2Encoder.encode("12G4") }
        assertThrows(IllegalArgumentException::class.java) { Necx2Encoder.encode("0123456789ABCDEF1") }
    }

    @Test
    fun `小写输入自动转大写且左补零`() {
        val pattern = Necx2Encoder.encode("ff12ed")
        assertEquals(217_600, pattern.intervals.sum())
        assertEquals(4500, pattern.intervals[0])
    }
}
