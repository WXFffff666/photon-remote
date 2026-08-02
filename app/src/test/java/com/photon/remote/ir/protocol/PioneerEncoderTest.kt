package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PIONEER 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 8500/4225、首数据位位序（整体 LSB 先发）、隐式停止位（无帧尾 mark）、
 * 26000µs 静默 + 整帧 ×2、完整双帧重发、非法输入异常。
 */
class PioneerEncoderTest {

    @Test
    fun `0x07F802FD 编码为完整 PIONEER 双帧波形`() {
        // 8 hex → 32 bit（Addr8 + ~Addr8 + Cmd8 + ~Cmd8，补码字节由输入直接提供）
        val pattern = PioneerEncoder.encode("07F802FD")
        val i = pattern.intervals

        assertEquals(40_000, pattern.frequency)
        // 单帧 = 前导 2 + 32 位 64 + 静默 1 = 67，双帧 = 134 项（偶数）
        assertEquals(134, i.size)

        // 前导 8500/4225
        assertEquals(8500, i[0])
        assertEquals(4225, i[1])

        // 首数据位：整体 LSB 先发 → 32 位值的 LSB = 0xFD 字节的 bit0 = 1 → mark 500 + space 1500
        assertEquals(500, i[2])
        assertEquals(1500, i[3])

        // 隐式停止位：无帧尾 mark，帧以最后一个数据位的 space 结束，随后 26000µs 静默
        // 第 32 位 = 32 位值的 MSB = 首字节 0x07 的 bit7 = 0 → space 500
        assertEquals(500, i[65])
        assertEquals(26_000, i[66])

        // 第二帧从下标 67 开始（单帧 67 项）
        assertEquals(8500, i[67])
        assertEquals(4225, i[68])
        assertEquals(26_000, i[133])
        // 总长 = 2 × (12725 + 16000 + 32000 + 26000)
        assertEquals(173_450, i.sum())
    }

    @Test
    fun `整体 LSB 先发位序正确`() {
        // 0x07F802FD 的字节序（高→低）：07 F8 02 FD；整体 LSB 先发
        // → 首 8 位 = 最低字节 0xFD 的 LSB 先发 = 1,0,1,1,1,1,1,1
        val pattern = PioneerEncoder.encode("07F802FD")
        val bits = (0 until 8).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(500, mark)
            if (space == 1500) "1" else "0"
        }
        assertEquals("10111111", bits)
    }

    @Test
    fun `完整双帧重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            PioneerEncoder.encode("07F802FD", PressKind.NEW_PRESS),
            PioneerEncoder.encode("07F802FD", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.PIONEER, PioneerEncoder.protocol)
        assertEquals(null, PioneerEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { PioneerEncoder.encode("0x07F802FD") }
        assertThrows(IllegalArgumentException::class.java) { PioneerEncoder.encode("GGGGGGGG") }
        assertThrows(IllegalArgumentException::class.java) { PioneerEncoder.encode("0123456789ABCDEF1") }  // 超过 8 位
    }
}
