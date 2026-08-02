package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RAW 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言：原样输出 mark/space 列表、空格/逗号分隔、奇数长度自动追加尾 space、
 * 完整重发、非法输入异常。
 */
class RawEncoderTest {

    @Test
    fun `偶数长度输入原样输出`() {
        val pattern = RawEncoder.encode("9000 4500 562 562 562 1687")
        assertEquals(38_000, pattern.frequency)
        assertArrayEquals(intArrayOf(9000, 4500, 562, 562, 562, 1687), pattern.intervals)
    }

    @Test
    fun `逗号与空格混合分隔`() {
        val pattern = RawEncoder.encode("9000,4500 562,562")
        assertArrayEquals(intArrayOf(9000, 4500, 562, 562), pattern.intervals)
    }

    @Test
    fun `奇数长度自动追加尾 space`() {
        // 以 mark 结尾（奇数长度）→ 自动追加尾 space，保持偶数下标为 mark
        val pattern = RawEncoder.encode("9000 4500 562")
        assertArrayEquals(intArrayOf(9000, 4500, 562, 1), pattern.intervals)
    }

    @Test
    fun `完整重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            RawEncoder.encode("9000 4500 562 562", PressKind.NEW_PRESS),
            RawEncoder.encode("9000 4500 562 562", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.RAW, RawEncoder.protocol)
        assertEquals(null, RawEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法输入抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { RawEncoder.encode("") }
        assertThrows(IllegalArgumentException::class.java) { RawEncoder.encode("abc") }
        assertThrows(IllegalArgumentException::class.java) { RawEncoder.encode("1 -2 3") }   // 负数
        assertThrows(IllegalArgumentException::class.java) { RawEncoder.encode("1 2 0") }    // 零
        assertThrows(IllegalArgumentException::class.java) { RawEncoder.encode("0x12 3 4") } // 非十进制
    }
}
