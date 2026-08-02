package com.photon.remote.ir.transmitter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB 红外发射器 RLE 帧构造单元测试（计划 §3.4 / Todo 11 验收，无硬件环境的验证方式）。
 *
 * 只测 [UsbIrTransmitter] 的纯函数（buildRleFrame / decodeRleFrame / fragmentFrame），
 * 不触碰 Android 对象（UsbManager 等），因此可在 JVM 上运行。
 * 接收端协议字节序以实测为准（TODO），本测试校验帧格式自洽性（往返一致、分片无损）。
 */
class UsbIrRleTest {

    @Test
    fun `相邻相同时长合并计数且往返一致`() {
        // NEC bit0 = 562/562：相邻同值应合并为一项；108800 补零段为单值
        val intervals = intArrayOf(9000, 4500, 562, 562, 562, 1687, 108800)
        val frame = UsbIrTransmitter.buildRleFrame(38_000, intervals)
        val (freq, decoded) = UsbIrTransmitter.decodeRleFrame(frame)
        assertEquals(38_000, freq)
        assertArrayEquals(intervals, decoded)   // 合并后展开恢复原序列
    }

    @Test
    fun `帧头包含魔数版本与频率`() {
        val frame = UsbIrTransmitter.buildRleFrame(38_000, intArrayOf(562, 562))
        assertEquals(0x50, frame[0].toInt() and 0xFF)   // 魔数 'P'
        assertEquals(0x49, frame[1].toInt() and 0xFF)   // 魔数 'I'
        assertEquals(0x01, frame[2].toInt() and 0xFF)   // 版本
        val freq = (frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)
        assertEquals(38_000, freq)
    }

    @Test
    fun `56 字节分片拼接还原且无数据丢失`() {
        // 200 个不同时长 → 不触发合并，payload 最大，帧必然超 56 字节
        val intervals = IntArray(200) { i -> 500 + i * 37 }
        val frame = UsbIrTransmitter.buildRleFrame(38_000, intervals)
        assertTrue(frame.size > 56)

        val chunks = UsbIrTransmitter.fragmentFrame(frame)
        assertTrue(chunks.all { it.size == 56 })         // 末片补 0 后也满 56
        val merged = chunks.flatMap { it.toList() }.toByteArray().copyOf(frame.size)  // 截掉尾部填充
        assertArrayEquals(frame, merged)
    }

    @Test
    fun `超长补零段可编码（超过 16 位范围）`() {
        // 108800µs > 65535，需要 3 字节时长字段
        val frame = UsbIrTransmitter.buildRleFrame(38_000, intArrayOf(9000, 4500, 108800))
        val (_, decoded) = UsbIrTransmitter.decodeRleFrame(frame)
        assertArrayEquals(intArrayOf(9000, 4500, 108800), decoded)
    }

    @Test
    fun `计数超过 255 时拆分多段`() {
        // 500 个连续的 562（理论 IR 序列不会这么长，验证编码健壮性）
        val intervals = IntArray(500) { 562 }
        val frame = UsbIrTransmitter.buildRleFrame(38_000, intervals)
        val (_, decoded) = UsbIrTransmitter.decodeRleFrame(frame)
        assertArrayEquals(intervals, decoded)
    }
}
