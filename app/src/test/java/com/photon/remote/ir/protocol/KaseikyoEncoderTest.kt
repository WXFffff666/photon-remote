package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * KASEIKYO 协议编码器单元测试（计划 §3.2 / Todo 9）。
 *
 * 断言完整波形：前导 3456/1728、首数据位位序（整体 LSB 先发）、48 位帧结构
 * （16 厂商 + 8 parity + 8 设备 + 16 命令）、parity = XOR(厂商高字节, 厂商低字节)、
 * 帧尾 432、完整帧重发、非法输入异常。
 */
class KaseikyoEncoderTest {

    /** 从波形中还原 48 位数值（整体 LSB 先发） */
    private fun decode48(intervals: IntArray): Long {
        var v = 0L
        for (bit in 0 until 48) {
            if (intervals[3 + bit * 2] == 1296) v = v or (1L shl bit)
        }
        return v
    }

    @Test
    fun `Panasonic 0x4004 编码为完整 KASEIKYO 波形`() {
        // 输入布局 = 厂商 4hex + parity 2hex + 设备 2hex + 命令 4hex
        val pattern = KaseikyoEncoder.encode("400444000000")
        val i = pattern.intervals

        assertEquals(37_000, pattern.frequency)
        // 前导 2 + 48 位 96 + 帧尾 + 终止 space = 100 项（偶数）
        assertEquals(100, i.size)

        // 前导 3456/1728
        assertEquals(3456, i[0])
        assertEquals(1728, i[1])

        // 首数据位：整体 LSB 先发 → 帧 LSB = 命令 bit0 = 0 → mark 432 + space 432
        assertEquals(432, i[2])
        assertEquals(432, i[3])

        // 帧尾 432 + 终止 space
        assertEquals(432, i[98])
        assertEquals(1, i[99])
        // 总长 = 前导 5184 + 48×432 + space(4×1296 + 44×432=24192) + 432 + 1
        assertEquals(50_545, i.sum())
    }

    @Test
    fun `48 位帧结构正确且 parity 由厂商计算`() {
        // 厂商 0x4004 → parity = XOR(0x40, 0x04) = 0x44
        val pattern = KaseikyoEncoder.encode("400444000000")
        val v = decode48(pattern.intervals)

        assertEquals(0x4004L, (v shr 32) and 0xFFFF)   // 厂商 16 位
        assertEquals(0x44L, (v shr 24) and 0xFF)       // parity 8 位
        assertEquals(0x00L, (v shr 16) and 0xFF)       // 设备 8 位
        assertEquals(0x0000L, v and 0xFFFF)            // 命令 16 位
    }

    @Test
    fun `parity 由编码器计算覆盖输入槽位`() {
        // 输入 parity 槽位为 00（错误值），编码器按 XOR(厂商高字节, 厂商低字节) 重新计算 → 0x44
        val pattern = KaseikyoEncoder.encode("400400000000")
        val v = decode48(pattern.intervals)
        assertEquals(0x44L, (v shr 24) and 0xFF)
    }

    @Test
    fun `完整帧重发 - REPEAT 与 NEW_PRESS 波形一致`() {
        assertEquals(
            KaseikyoEncoder.encode("400444000000", PressKind.NEW_PRESS),
            KaseikyoEncoder.encode("400444000000", PressKind.REPEAT)
        )
    }

    @Test
    fun `协议标识与重复间隔`() {
        assertEquals(ProtocolType.KASEIKYO, KaseikyoEncoder.protocol)
        assertEquals(null, KaseikyoEncoder.repeatIntervalMs)
    }

    @Test
    fun `非法 hex 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { KaseikyoEncoder.encode("0x400444000000") }
        assertThrows(IllegalArgumentException::class.java) { KaseikyoEncoder.encode("GGGGGGGGGGGG") }
        assertThrows(IllegalArgumentException::class.java) { KaseikyoEncoder.encode("4004440000000") }  // 超过 12 位
    }
}
