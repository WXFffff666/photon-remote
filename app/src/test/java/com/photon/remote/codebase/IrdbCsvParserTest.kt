package com.photon.remote.codebase

import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * IrdbCsvParser 单元测试（计划 §4.2 / Todo 20 验收）。
 *
 * 解析真实 irdb CSV（assets/irdb/Samsung/tv/7,7.csv，实测含 NECx2 码组）：
 * 断言 POWER ON 行 protocol/device/subdevice/function 各字段与映射结果。
 */
@RunWith(RobolectricTestRunner::class)
class IrdbCsvParserTest {

    private val parser: IrdbCsvParser by lazy {
        IrdbCsvParser(RuntimeEnvironment.getApplication())
    }

    @Test
    fun 品牌类型型号列举_真实目录() {
        val brands = parser.listBrands()
        assertTrue("应含 Samsung", brands.contains("Samsung"))
        val types = parser.listTypes("Samsung")
        assertTrue("应含 tv", types.contains("tv"))
        val models = parser.listModels("Samsung", "tv")
        assertTrue("应含 7,7 码组", models.contains("7,7"))
    }

    @Test
    fun 解析真实SamsungCSV_POWER行各字段正确() {
        val codes = parser.codes("Samsung", "tv", "7,7")
        assertTrue("CSV 应解析出码记录", codes.isNotEmpty())
        // 实测数据：POWER ON,NECx2,7,7,153
        val powerOn = codes.first { it.functionName == "POWER ON" }
        assertEquals("NECx2", powerOn.protocol)
        assertEquals(ProtocolType.NECX2, powerOn.mappedProtocol)
        assertEquals("7", powerOn.device)
        assertEquals("7", powerOn.subdevice)
        assertEquals("153", powerOn.function)
        // POWER OFF 同样解析
        val powerOff = codes.first { it.functionName == "POWER OFF" }
        assertEquals("152", powerOff.function)
        assertEquals(ProtocolType.NECX2, powerOff.mappedProtocol)
    }

    @Test
    fun 协议映射表_可映射与UNKNOWN() {
        assertEquals(ProtocolType.NECX2, parser.mapProtocol("NECx2"))
        assertEquals(ProtocolType.NECX1, parser.mapProtocol("NECx1"))
        assertEquals(ProtocolType.NEC, parser.mapProtocol("NEC1"))
        assertEquals(ProtocolType.SAMSUNG32, parser.mapProtocol("Samsung36"))
        assertEquals(ProtocolType.SONY12, parser.mapProtocol("SONY"))
        assertEquals(ProtocolType.SONY15, parser.mapProtocol("Sony15"))
        assertEquals(ProtocolType.KASEIKYO, parser.mapProtocol("Panasonic"))
        assertEquals(ProtocolType.RC5, parser.mapProtocol("RC5"))
        assertEquals(ProtocolType.PIONEER, parser.mapProtocol("Pioneer"))
        assertEquals(ProtocolType.SHARP, parser.mapProtocol("Sharp{1}"))
        // 大小写/空白容忍
        assertEquals(ProtocolType.NECX2, parser.mapProtocol("  necx2 "))
        // 映射不了的 → null（= UNKNOWN，ProtocolType 枚举无 UNKNOWN 值）
        assertNull(parser.mapProtocol("Zenith"))
        assertNull(parser.mapProtocol("48-NEC1"))
        assertNull(parser.mapProtocol("Samsung20"))
        assertNull(parser.mapProtocol("Tivo unit=0"))
    }

    @Test
    fun 缺失文件与坏行_不崩溃() {
        assertTrue(parser.codes("NoSuchBrand", "tv", "x").isEmpty())
        // 合并解析不抛异常
        assertNotNull(parser.allCodes("Samsung", "tv"))
    }
}
