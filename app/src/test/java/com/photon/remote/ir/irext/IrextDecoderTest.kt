package com.photon.remote.ir.irext

import kotlinx.coroutines.runBlocking
import net.irext.decode.sdk.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IrextDecoder 单元测试（计划 §3.3 / Todo 14-15 验收）。
 *
 * JVM 环境没有 libirdecode.so（仅 arm 真机打包），因此：
 *  1) isAvailable 必须为 false（惰性加载 catch 住 UnsatisfiedLinkError，绝不崩溃）
 *  2) open/decode/close 在不可用环境返回 false/null 且不抛异常
 *  3) translateKeyCode 纯函数映射表（应用层语义 → irext 官方键位）全量验证
 *
 * 真机（有 so）路径无法在 JVM 单测覆盖：decode 成功路径由 Todo 15 Acceptance
 * "用 irext 示例 bin 解码得到非空波形" 在 arm 真机 QA 验证。
 */
class IrextDecoderTest {

    // ---------- 无 so 环境降级（Todo 14 Acceptance：x86 不崩溃） ----------

    @Test
    fun isAvailable_无so环境为false且不抛异常() {
        // JVM 无 libirdecode.so：惰性加载 catch(Throwable) → false，不得抛异常
        assertFalse(IrextDecoder.isAvailable)
        // 重复访问不重新加载、不崩溃
        assertFalse(IrextDecoder.isAvailable)
    }

    @Test
    fun open_不可用环境返回false且不抛异常() = runBlocking {
        assertFalse(IrextDecoder.open("test.bin", Constants.CategoryID.TV.getValue(), 1, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun decode_不可用环境返回null且不抛异常() {
        // 未 open + 不可用：直接 null
        assertNull(IrextDecoder.decode(IrextDecoder.APP_KEY_POWER, net.irext.decode.sdk.bean.ACStatus()))
    }

    @Test
    fun close_不可用环境不抛异常且状态清空() {
        IrextDecoder.close()   // 不得抛异常（含 NPE/UnsatisfiedLinkError）
        assertFalse(IrextDecoder.isOpen)
        assertNull(IrextDecoder.currentOpenRef)
    }

    @Test
    fun AC支持查询_不可用环境返回空值不崩溃() {
        assertNull(IrextDecoder.getTemperatureRange(0))
        assertFalse(IrextDecoder.getACSupportedMode().any { it })
        assertFalse(IrextDecoder.getACSupportedWindSpeed(0).any { it })
        assertFalse(IrextDecoder.getACSupportedSwing(0).any { it })
    }

    // ---------- translateKeyCode：空调（ACFunction 1..7 官方约定） ----------

    @Test
    fun translate_空调按键映射功能码() {
        val ac = Constants.CategoryID.AIR_CONDITIONER.getValue()
        assertEquals(Constants.ACFunction.FUNCTION_SWITCH_POWER.getValue(),   // 1
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_POWER))
        assertEquals(Constants.ACFunction.FUNCTION_SWITCH_WIND_SPEED.getValue(), // 5
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_UP))
        assertEquals(Constants.ACFunction.FUNCTION_SWITCH_WIND_DIR.getValue(),  // 6
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_DOWN))
        assertEquals(Constants.ACFunction.FUNCTION_CHANGE_MODE.getValue(),      // 2
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_RIGHT))
        assertEquals(Constants.ACFunction.FUNCTION_SWITCH_SWING.getValue(),     // 7
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_OK))
        assertEquals(Constants.ACFunction.FUNCTION_TEMPERATURE_UP.getValue(),   // 3
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_VOL_UP))
        assertEquals(Constants.ACFunction.FUNCTION_TEMPERATURE_DOWN.getValue(), // 4
            IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_VOL_DOWN))
    }

    @Test
    fun translate_空调不适用按键返回负一() {
        val ac = Constants.CategoryID.AIR_CONDITIONER.getValue()
        assertEquals(-1, IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_NUM_0))
        assertEquals(-1, IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_MUTE))
        assertEquals(-1, IrextDecoder.translateKeyCode(ac, IrextDecoder.APP_KEY_MENU))
    }

    // ---------- translateKeyCode：电视/机顶盒（irext 官方标准键位） ----------

    @Test
    fun translate_电视标准键位映射() {
        val tv = Constants.CategoryID.TV.getValue()
        assertEquals(0, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_POWER))   // KEY_TV_POWER
        assertEquals(1, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_MUTE))    // KEY_TV_MUTE
        assertEquals(2, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_UP))      // KEY_TV_UP
        assertEquals(3, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_DOWN))
        assertEquals(4, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_LEFT))
        assertEquals(5, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_RIGHT))
        assertEquals(6, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_OK))
        assertEquals(7, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_VOL_UP))  // KEY_TV_VOL_PLUS
        assertEquals(8, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_VOL_DOWN))
        assertEquals(9, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_BACK))
        assertEquals(10, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_INPUT))
        assertEquals(11, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_MENU))
    }

    @Test
    fun translate_数字键映射通道槽位() {
        val tv = Constants.CategoryID.TV.getValue()
        // 数字 0..9 → 通道槽位 14..23（STB/TV 二进制通用布局）
        for (digit in 0..8) {
            assertEquals(14 + digit,
                IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_NUM_0 + digit))
        }
        assertEquals(23, IrextDecoder.translateKeyCode(tv, IrextDecoder.APP_KEY_NUM_9))
    }

    @Test
    fun translate_频道加减映射机顶盒翻页键() {
        val stb = Constants.CategoryID.STB.getValue()
        assertEquals(12, IrextDecoder.translateKeyCode(stb, IrextDecoder.APP_KEY_CH_UP))   // KEY_STB_PAGE_UP
        assertEquals(13, IrextDecoder.translateKeyCode(stb, IrextDecoder.APP_KEY_CH_DOWN)) // KEY_STB_PAGE_DOWN
    }

    @Test
    fun translate_未知按键原样透传() {
        val tv = Constants.CategoryID.TV.getValue()
        assertEquals(99, IrextDecoder.translateKeyCode(tv, 99))
        assertEquals(42, IrextDecoder.translateKeyCode(tv, 42))
    }
}
