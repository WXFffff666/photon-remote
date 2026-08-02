package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议编码器注册表单元测试（计划 §3.2 / Todo 9 交付物 4）。
 *
 * 断言全部 14 种协议均已注册、映射键与编码器声明的协议一致、重复间隔覆盖符合 §3.2 表。
 */
class ProtocolEncodersTest {

    @Test
    fun `注册表包含全部 14 种协议`() {
        assertEquals(14, ProtocolEncoders.all.size)
        for (type in ProtocolType.entries) {
            assertNotNull("缺少协议编码器: $type", ProtocolEncoders.all[type])
        }
    }

    @Test
    fun `映射键与编码器声明的协议一致`() {
        for ((type, encoder) in ProtocolEncoders.all) {
            assertEquals(type, encoder.protocol)
        }
    }

    @Test
    fun `重复间隔覆盖符合计划`() {
        // NEC 家族与 JVC = 110ms（短重复帧/短爆发），其余 null → 250ms 全局默认
        assertEquals(110, ProtocolEncoders.all[ProtocolType.NEC]!!.repeatIntervalMs)
        assertEquals(110, ProtocolEncoders.all[ProtocolType.NECX1]!!.repeatIntervalMs)
        assertEquals(110, ProtocolEncoders.all[ProtocolType.NECX2]!!.repeatIntervalMs)
        assertEquals(110, ProtocolEncoders.all[ProtocolType.JVC]!!.repeatIntervalMs)
        for (type in listOf(
            ProtocolType.RC5, ProtocolType.RC6,
            ProtocolType.SONY12, ProtocolType.SONY15, ProtocolType.SONY20,
            ProtocolType.SAMSUNG32, ProtocolType.SHARP,
            ProtocolType.KASEIKYO, ProtocolType.PIONEER, ProtocolType.RAW
        )) {
            assertEquals(null, ProtocolEncoders.all[type]!!.repeatIntervalMs)
        }
        // null 语义 = 使用全局默认 250ms
        assertTrue(ProtocolEncoders.all[ProtocolType.RC5]!!.repeatIntervalMs == null)
    }
}
