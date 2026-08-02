package com.photon.remote.ir.irext

import net.irext.decode.sdk.bean.ACStatus
import net.irext.decode.sdk.bean.TemperatureRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACStatusHelper 单元测试（计划 §3.3 / Todo 15 验收：校验与钳制边界）。
 *
 * 纯 JVM 状态机，覆盖：
 *  1) isValid 各字段边界（合法/非法）
 *  2) clamp 钳制边界（含温度范围钳制与 null 输入）
 *  3) 应用层 ↔ irext 原生层语义转换（power 反转、temp 索引、范围换算）
 */
class ACStatusHelperTest {

    /**
     * 构造 ACStatus 的便捷函数（Java bean 构造器为位置参数，此处保持应用层语义可读性：
     * 电源/模式/温度/风速/风向/换风向；display/sleep/timer 恒为 0）。
     */
    private fun acStatus(
        acPower: Int = 0, acMode: Int = 0, acTemp: Int = 0, acWindSpeed: Int = 0,
        acWindDir: Int = 0, changeWindDir: Int = 0,
    ): ACStatus = ACStatus(acPower, acMode, acTemp, acWindSpeed, acWindDir, 0, 0, 0, changeWindDir)

    // ---------- isValid：校验 ----------

    @Test
    fun isValid_合法状态返回true() {
        // 应用层语义：0关1开、0..4 模式、16..30℃、0..3 风速、0/1 风向、0/1 换风向
        assertTrue(ACStatusHelper.isValid(0, 0, 16, 0, 0, 0))
        assertTrue(ACStatusHelper.isValid(1, 4, 30, 3, 1, 1))
        assertTrue(ACStatusHelper.isValid(1, 2, 26, 2, 0, 0))
    }

    @Test
    fun isValid_电源非法返回false() {
        assertFalse(ACStatusHelper.isValid(2, 0, 26, 0, 0, 0))   // 电源只能 0/1
        assertFalse(ACStatusHelper.isValid(-1, 0, 26, 0, 0, 0))
    }

    @Test
    fun isValid_模式越界返回false() {
        assertFalse(ACStatusHelper.isValid(1, 5, 26, 0, 0, 0))    // 模式最大 4
        assertFalse(ACStatusHelper.isValid(1, -1, 26, 0, 0, 0))
    }

    @Test
    fun isValid_温度越界返回false() {
        assertFalse(ACStatusHelper.isValid(1, 0, 15, 0, 0, 0))    // 低于 16
        assertFalse(ACStatusHelper.isValid(1, 0, 31, 0, 0, 0))    // 高于 30
    }

    @Test
    fun isValid_风速风向换风向越界返回false() {
        assertFalse(ACStatusHelper.isValid(1, 0, 26, 4, 0, 0))    // 风速最大 3
        assertFalse(ACStatusHelper.isValid(1, 0, 26, 0, 2, 0))    // 风向最大 1
        assertFalse(ACStatusHelper.isValid(1, 0, 26, 0, 0, 2))    // 换风向最大 1
    }

    @Test
    fun isValid_null返回false() {
        assertFalse(ACStatusHelper.isValid(null))
    }

    // ---------- clamp：钳制 ----------

    @Test
    fun clamp_各字段越界被钳制回合法区间() {
        val src = acStatus(acPower = 5, acMode = 9, acTemp = 40, acWindSpeed = 7,
            acWindDir = 3, changeWindDir = 2)
        val out = ACStatusHelper.clamp(src, null)
        assertNotSame("钳制必须返回新实例，不修改输入", src, out)
        assertEquals(ACStatusHelper.POWER_ON, out.acPower)        // 5 → 1
        assertEquals(ACStatusHelper.MODE_DEHUMIDITY, out.acMode)  // 9 → 4
        assertEquals(ACStatusHelper.TEMP_ABSOLUTE_MAX, out.acTemp) // 40 → 30
        assertEquals(ACStatusHelper.SPEED_HIGH, out.acWindSpeed)  // 7 → 3
        assertEquals(ACStatusHelper.WIND_DIR_MAX, out.acWindDir)  // 3 → 1
        assertEquals(ACStatusHelper.CHANGE_WIND_DIR_MAX, out.changeWindDir) // 2 → 1
    }

    @Test
    fun clamp_负值越界被钳制回合法区间() {
        val out = ACStatusHelper.clamp(
            acStatus(acPower = -1, acMode = -1, acTemp = -5, acWindSpeed = -1,
                acWindDir = -1, changeWindDir = -1), null,
        )
        assertEquals(ACStatusHelper.POWER_OFF, out.acPower)
        assertEquals(ACStatusHelper.MODE_COOL, out.acMode)
        assertEquals(ACStatusHelper.TEMP_ABSOLUTE_MIN, out.acTemp)
        assertEquals(ACStatusHelper.SPEED_AUTO, out.acWindSpeed)
        assertEquals(ACStatusHelper.WIND_DIR_MIN, out.acWindDir)
        assertEquals(ACStatusHelper.CHANGE_WIND_DIR_MIN, out.changeWindDir)
    }

    @Test
    fun clamp_合法输入保持不变() {
        val out = ACStatusHelper.clamp(acStatus(acPower = 1, acMode = 2, acTemp = 26,
            acWindSpeed = 2, acWindDir = 1, changeWindDir = 0), null)
        assertEquals(1, out.acPower)
        assertEquals(2, out.acMode)
        assertEquals(26, out.acTemp)
        assertEquals(2, out.acWindSpeed)
        assertEquals(1, out.acWindDir)
        assertEquals(0, out.changeWindDir)
    }

    @Test
    fun clamp_null返回应用层默认状态() {
        val out = ACStatusHelper.clamp(null, null)
        assertEquals(ACStatusHelper.POWER_OFF, out.acPower)       // 0=关
        assertEquals(ACStatusHelper.MODE_COOL, out.acMode)        // 0=制冷
        assertEquals(26, out.acTemp)                              // 26℃
        assertEquals(ACStatusHelper.SPEED_AUTO, out.acWindSpeed)  // 0=自动
        assertEquals(ACStatusHelper.WIND_DIR_MIN, out.acWindDir)
        assertEquals(ACStatusHelper.CHANGE_WIND_DIR_MIN, out.changeWindDir)
    }

    @Test
    fun clamp_按模式温度范围钳制() {
        // 模式温度范围 22..26℃：低于下限钳到 22，高于上限钳到 26，范围内不动
        val range = TemperatureRange(6, 10)   // 原生索引 6..10 = 22..26℃
        assertEquals(22, ACStatusHelper.clamp(acStatus(acTemp = 16), range).acTemp)
        assertEquals(25, ACStatusHelper.clamp(acStatus(acTemp = 25), range).acTemp)
        assertEquals(26, ACStatusHelper.clamp(acStatus(acTemp = 30), range).acTemp)
    }

    // ---------- 语义转换：toNativeAcStatus ----------

    @Test
    fun toNative_电源反转() {
        // 应用 0=关 → 原生 1=关；应用 1=开 → 原生 0=开
        assertEquals(1, ACStatusHelper.toNativeAcStatus(acStatus(acPower = 0)).acPower)
        assertEquals(0, ACStatusHelper.toNativeAcStatus(acStatus(acPower = 1)).acPower)
    }

    @Test
    fun toNative_温度转索引() {
        assertEquals(0, ACStatusHelper.toNativeAcStatus(acStatus(acTemp = 16)).acTemp)
        assertEquals(14, ACStatusHelper.toNativeAcStatus(acStatus(acTemp = 30)).acTemp)
        assertEquals(10, ACStatusHelper.toNativeAcStatus(acStatus(acTemp = 26)).acTemp)
    }

    @Test
    fun toNative_越界输入先钳制再转换() {
        // 温度 5 先钳到 16 再转索引 0；电源 5 先钳到 1 再反转成 0
        val out = ACStatusHelper.toNativeAcStatus(acStatus(acPower = 5, acTemp = 5))
        assertEquals(0, out.acPower)
        assertEquals(0, out.acTemp)
    }

    @Test
    fun toNative_null返回应用默认转换结果() {
        val out = ACStatusHelper.toNativeAcStatus(null)
        // 应用默认 0关0制26℃ → 原生 1关、mode 0、temp 索引 10
        assertEquals(1, out.acPower)
        assertEquals(0, out.acMode)
        assertEquals(10, out.acTemp)
        assertEquals(0, out.acWindSpeed)
        assertEquals(0, out.acWindDir)
        assertEquals(0, out.changeWindDir)
    }

    @Test
    fun toNative_返回新实例不改输入() {
        val src = acStatus(acPower = 1, acTemp = 26)
        val out = ACStatusHelper.toNativeAcStatus(src)
        assertNotSame(src, out)
        assertEquals(1, src.acPower)   // 输入保持应用层语义
        assertEquals(26, src.acTemp)
    }

    // ---------- 语义转换：温度范围 ----------

    @Test
    fun toAppTempRange_索引区间转摄氏区间() {
        assertEquals(16..30, ACStatusHelper.toAppTempRange(TemperatureRange(0, 14)))
        assertEquals(22..26, ACStatusHelper.toAppTempRange(TemperatureRange(6, 10)))
        assertEquals(16..30, ACStatusHelper.toAppTempRange(TemperatureRange(0, 0)))
        assertEquals(16..30, ACStatusHelper.toAppTempRange(null))
    }

    @Test
    fun toAppTempRange_特殊值按无限制处理() {
        // 原生 all_temp=1 时返回 (-1,-1)：退化为绝对区间
        assertEquals(16..30, ACStatusHelper.toAppTempRange(TemperatureRange(-1, -1)))
        // 防御原生越界异常值
        assertEquals(16..30, ACStatusHelper.toAppTempRange(TemperatureRange(0, 99)))
        assertEquals(16..30, ACStatusHelper.toAppTempRange(TemperatureRange(-5, 14)))
    }

    @Test
    fun indexTemp_往返一致() {
        for (temp in 16..30) {
            assertEquals(temp, ACStatusHelper.indexToTemp(ACStatusHelper.tempToIndex(temp)))
        }
        assertEquals(16, ACStatusHelper.indexToTemp(0))
        assertEquals(30, ACStatusHelper.indexToTemp(14))
        assertEquals(0, ACStatusHelper.tempToIndex(16))
        assertEquals(14, ACStatusHelper.tempToIndex(30))
    }

    // ---------- 掩码转布尔数组 ----------

    @Test
    fun maskToBooleans_按位展开() {
        // 0x1F = 全部 5 模式支持
        assertArrayEquals(BooleanArray(5) { true }, ACStatusHelper.maskToBooleans(0x1F, 5))
        // 0x05 = bit0 + bit2 → [true, false, true, false, false]
        assertArrayEquals(
            booleanArrayOf(true, false, true, false, false),
            ACStatusHelper.maskToBooleans(0x05, 5),
        )
        // 0 = 全不支持
        assertArrayEquals(BooleanArray(4) { false }, ACStatusHelper.maskToBooleans(0, 4))
    }
}
