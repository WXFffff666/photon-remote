package com.photon.remote.viewmodel

import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.action
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.IrextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * DefaultButtonFactory 单元测试（Todo 41 补全）。
 *
 * 覆盖三条生成路径：
 * 1) IREXT 非 AC 设备 → 标准 24 键（COMMON_KEY_ORDER），电源圆形、按键序递增；
 * 2) IREXT AC 设备 → 仅电源/静音两键；
 * 3) IRDB 设备 → 用真实 Samsung CSV（assets/irdb/Samsung/tv/7,7.csv）生成按键，
 *    断言电源键映射为 SendProtocol(NECx2)。
 */
@RunWith(RobolectricTestRunner::class)
class DefaultButtonFactoryTest {

    private val parser: IrdbCsvParser by lazy {
        IrdbCsvParser(RuntimeEnvironment.getApplication())
    }

    /** IREXT 电视设备：24 键、电源圆形、序递增、actionJson 反序列化正确 */
    @Test
    fun irext电视_生成标准24键_电源圆形且顺序递增() {
        val device = Device(
            name = "电视", type = DeviceType.TV, brand = "小米",
            model = "小米电视", codeSource = CodeSource.IREXT, codeRef = "ab12cd34.bin",
        )
        val buttons = DefaultButtonFactory.buttonsFor(device, parser)

        assertEquals("IREXT 非 AC 设备应生成 24 个标准键", 24, buttons.size)
        assertEquals("首键应为电源", "POWER", buttons.first().keyId)
        assertEquals("电源键应为圆形", ButtonShape.CIRCLE, buttons.first().shape)
        assertEquals("末键应为数字 9", "NUM_9", buttons.last().keyId)

        // 按键序必须 0..23 递增（布局按序渲染）
        assertEquals(buttons.indices.toList(), buttons.map { it.order })

        // actionJson 往返：电源 → IrextKey(keyCode=APP_KEY_POWER, binaryRef=codeRef)
        val power = buttons.first().action() as ButtonAction.IrextKey
        assertEquals(IrextDecoder.APP_KEY_POWER, power.keyCode)
        assertEquals("ab12cd34.bin", power.binaryRef)

        // 数字键 keyCode 与键位对应（NUM_0=APP_KEY_NUM_0，其余顺延）
        val num0 = buttons.first { it.keyId == "NUM_0" }.action() as ButtonAction.IrextKey
        assertEquals(IrextDecoder.APP_KEY_NUM_0, num0.keyCode)
    }

    /** IREXT 空调设备：仅电源/静音两键 */
    @Test
    fun irext空调_仅电源与静音两键() {
        val device = Device(
            name = "空调", type = DeviceType.AC, brand = "格力",
            model = "格力空调", codeSource = CodeSource.IREXT, codeRef = "ef567890.bin",
        )
        val buttons = DefaultButtonFactory.buttonsFor(device, parser)

        assertEquals(listOf("POWER", "MUTE"), buttons.map { it.keyId })
        assertEquals("电源", buttons.first().label)
    }

    /** IRDB 设备：真实 Samsung CSV 生成按键，电源映射为 SendProtocol(NECx2) */
    @Test
    fun irdb电视_真实SamsungCSV_电源映射SendProtocolNECx2() {
        val device = Device(
            name = "电视", type = DeviceType.TV, brand = "Samsung",
            model = "7,7", codeSource = CodeSource.IRDB, codeRef = "Samsung/tv/7,7.csv",
        )
        val buttons = DefaultButtonFactory.buttonsFor(device, parser)

        assertTrue("Samsung 7,7 应能生成按键集", buttons.isNotEmpty())
        val power = buttons.first { it.keyId == "POWER" }
        assertEquals("电源", power.label)
        assertEquals(ButtonShape.CIRCLE, power.shape)
        val action = power.action() as ButtonAction.SendProtocol
        assertEquals(ProtocolType.NECX2, action.protocol)
        // 实测 irdb 行：POWER ON,NECx2,7,7,153 → 07F89966（addr,~addr,cmd,~cmd）
        assertEquals("07F89966", action.hex)

        // 音量/频道键也应按标准键位生成且序不重复
        val keyIds = buttons.map { it.keyId }
        // 该码组实测只有输入源类按键（ANTENNA INPUT / VIDEO 1 …），应映射为 INPUT 标准键位
        assertTrue("应含输入源键 INPUT", keyIds.contains("INPUT"))
        assertEquals("按键序应唯一", buttons.size, keyIds.distinct().size)
    }

    /** IRDB 设备：码组文件缺失时返回空列表（不崩溃） */
    @Test
    fun irdb设备_码组缺失_返回空列表() {
        val device = Device(
            name = "电视", type = DeviceType.TV, brand = "NoSuchBrand",
            model = "7,7", codeSource = CodeSource.IRDB, codeRef = "NoSuchBrand/tv/7,7.csv",
        )
        assertTrue(DefaultButtonFactory.buttonsFor(device, parser).isEmpty())
    }

    /** IREXT 设备键序与 irdb 键序不应混用（IREXT 分支不依赖 CSV 目录） */
    @Test
    fun irext设备_键位与AC分支不同() {
        val tv = Device(
            name = "电视", type = DeviceType.TV, brand = "小米",
            codeSource = CodeSource.IREXT, codeRef = "ab12cd34.bin",
        )
        val ac = Device(
            name = "空调", type = DeviceType.AC, brand = "格力",
            codeSource = CodeSource.IREXT, codeRef = "ef567890.bin",
        )
        assertNotEquals(
            "电视 24 键与空调 2 键不应相同",
            DefaultButtonFactory.buttonsFor(tv, parser).map { it.keyId },
            DefaultButtonFactory.buttonsFor(ac, parser).map { it.keyId },
        )
    }
}
