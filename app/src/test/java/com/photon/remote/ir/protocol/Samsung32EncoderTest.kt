package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SAMSUNG32 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 4500/4500、首数据位位序（每字节 LSB 先发）、补码字节、
 * 帧尾 550、不补零（自然帧长 + 1µs 终止 space）、完整帧重发、非法输入异常。
 */
class Samsung32EncoderTest {

    @Test
    fun `0x0702 编码为完整 SAMSUNG32 波形`() {
        // 4 hex = 自定义 0x07 + 命令 0x02 → 32 位帧 = 07 F8 02 FD（每字节 LSB 先发）
        val pattern = Samsung32Encoder.encode("0702")
        val i = pattern.intervals

        assertEquals(38_000, pattern.frequency)
        // 前导 2 + 32 位 64 + 帧尾 + 终止 space = 68 项（偶数）
        assertEquals(68, i.size)

        // 前导 4500/4500
        assertEquals(4500, i[0])
        assertEquals(4500, i[1])

        // 首数据位：自定义字节 0x07 的 LSB = 1 → mark 550 + space 1650
        assertEquals(550, i[2])
        assertEquals(1650, i[3])

        // 第 32 位：0xFD（~命令）字节的 MSB = 1 → space 1650
        assertEquals(550, i[64])
        assertEquals(1650, i[65])

        // 帧尾 550 + 终止 space 1（不补零到帧长）
        assertEquals(550, i[66])
        assertEquals(1, i[67])
        assertEquals(62_351, i.sum())
    }

    @Test
    fun `每字节 LSB 先发且含补码字节`() {
        // 0x07 → 11100000；0xF8（~0x07）→ 00011111；0x02 → 01000000；0xFD（~0x02）→ 10111111
        val pattern = Samsung32Encoder.encode("0702")
        val bits = (0 until 32).joinToString("") { bitIndex ->
            val mark = pattern.intervals[2 + bitIndex * 2]
            val space = pattern.intervals[3 + bitIndex * 2]
            assertEquals(550, mark)
            if (space == 1650) "1" else "0"
        }
        assertEquals("11100000000111110100000010111111", bits)
    }

    @Test
    fun `完整帧重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            Samsung32Encoder.encode("0702", PressKind.NEW_PRESS),
            Samsung32Encoder.encode("0702", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.SAMSUNG32, Samsung32Encoder.protocol)
        assertEquals(null, Samsung32Encoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { Samsung32Encoder.encode("0x0702") }
        assertThrows(IllegalArgumentException::class.java) { Samsung32Encoder.encode("GGGG") }
        assertThrows(IllegalArgumentException::class.java) { Samsung32Encoder.encode("12345") }  // 超过 4 位
    }
}
