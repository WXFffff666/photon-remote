package com.photon.remote.codebase.importer

import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.MacroStep
import com.photon.remote.data.model.Operator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JsonBackup 单元测试：导出→导入往返一致性、非法记录跳过、schemaVersion 校验。
 */
class JsonBackupTest {

    private val device = Device(
        id = 1L,
        name = "客厅电视",
        type = DeviceType.TV,
        brand = "小米",
        region = "广东",
        city = "深圳",
        operator = Operator.CMCC,
        model = "L43M5",
        codeSource = CodeSource.IRDB,
        codeRef = "xiaomi/l43m5.csv",
        layoutId = "custom_json",
        layoutJson = "{\"v\":1}",
        colorSeed = 42L,
        sortOrder = 3,
        isFavorite = true,
        createdAt = 123456789L,
    )

    private val button = RemoteButton(
        id = 10L,
        deviceId = 1L,
        keyId = "POWER",
        label = "电源",
        icon = "power",
        actionJson = """{"type":"SendRaw","frequency":38000,"intervals":[100,200]}""",
        order = 0,
        col = 1, row = 2,
        colSpan = 2, rowSpan = 1,
        shape = ButtonShape.CIRCLE,
        textOnly = true,
    )

    private val macro = Macro(
        id = 100L,
        name = "回家",
        icon = "home",
        stepsJson = MacroStep.codec.encodeToString(
            listOf(MacroStep(deviceId = 1L, buttonId = 10L, delayMs = 500L), MacroStep(1L, 11L)),
        ),
        sortOrder = 1,
        createdAt = 987654321L,
    )

    /** 导出 → 解析 → 字段完全一致（往返） */
    @Test
    fun `导出再导入_字段完全一致`() {
        val content = JsonBackup.export(listOf(device), listOf(button), listOf(macro))

        val result = JsonBackup.import(content)

        assertEquals(listOf(device), result.devices)
        assertEquals(listOf(button), result.buttons)
        assertEquals(listOf(macro), result.macros)
        assertTrue("合法备份不应有跳过记录，实际：${result.skipped}", result.skipped.isEmpty())
    }

    /** 导入含 1 条非法记录（枚举名不存在）：该条跳过、其余保留、不中断 */
    @Test
    fun `导入_非法记录跳过且其余保留`() {
        val content = """
            {
              "schemaVersion": 1,
              "devices": [
                {"id": 1, "name": "电视", "type": "TV", "brand": "小米", "codeSource": "IRDB", "codeRef": "x.csv"},
                {"id": 2, "name": "坏设备", "type": "NOT_A_TYPE", "brand": "坏", "codeSource": "IRDB", "codeRef": "y.csv"}
              ],
              "buttons": [
                {"id": 10, "deviceId": 1, "keyId": "POWER", "label": "电源", "actionJson": "{}"}
              ],
              "macros": []
            }
        """.trimIndent()

        val result = JsonBackup.import(content)

        assertEquals(1, result.devices.size)
        assertEquals("电视", result.devices[0].name)
        // 导入记录补齐默认值：icon/坐标/形状等未在 JSON 中出现的字段回落默认
        assertEquals(
            button.copy(
                actionJson = "{}", icon = null, order = 0, col = 0, row = 0,
                colSpan = 1, rowSpan = 1, shape = ButtonShape.ROUNDED, textOnly = false,
            ),
            result.buttons[0],
        )
        assertTrue(result.macros.isEmpty())
        assertEquals(1, result.skipped.size)
        assertTrue("跳过原因应含设备名「坏设备」，实际：${result.skipped[0]}", result.skipped[0].contains("坏设备"))
    }

    /** 孤儿按键（deviceId 不在备份设备中）跳过；宏内失效步骤记录 skipped 但宏保留 */
    @Test
    fun `导入_孤儿按键跳过且宏内失效步骤记录但宏保留`() {
        val macroJson = MacroStep.codec.encodeToString(
            listOf(MacroStep(deviceId = 99L, buttonId = 1L), MacroStep(deviceId = 1L, buttonId = 10L)),
        )
        val content = """
            {
              "schemaVersion": 1,
              "devices": [
                {"id": 1, "name": "电视", "type": "TV", "brand": "小米", "codeSource": "CUSTOM", "codeRef": "m"}
              ],
              "buttons": [
                {"id": 10, "deviceId": 1, "keyId": "POWER", "label": "电源", "actionJson": "{}"},
                {"id": 11, "deviceId": 99, "keyId": "OK", "label": "孤儿键", "actionJson": "{}"}
              ],
              "macros": [
                {"id": 100, "name": "回家", "stepsJson": "${macroJson.replace("\"", "\\\"")}"}
              ]
            }
        """.trimIndent()

        val result = JsonBackup.import(content)

        // 孤儿按键被跳过，合法按键保留
        assertEquals(1, result.buttons.size)
        assertEquals("电源", result.buttons[0].label)
        // 宏整体保留（stepsJson 原样）
        assertEquals(1, result.macros.size)
        assertEquals(macroJson, result.macros[0].stepsJson)
        // 两条跳过原因：孤儿按键 + 宏内失效步骤
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped[0].contains("孤儿键"))
        assertTrue(result.skipped[1].contains("回家"))
    }

    /** schemaVersion 不支持：抛中文 IllegalArgumentException，无部分结果 */
    @Test
    fun `导入_不支持的版本抛中文异常`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            JsonBackup.import("""{"schemaVersion": 2, "devices": []}""")
        }
        assertTrue("异常信息应为中文，实际：${e.message}", e.message!!.contains("版本"))
    }

    /** 缺少 schemaVersion：抛中文 IllegalArgumentException */
    @Test
    fun `导入_缺少版本字段抛中文异常`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            JsonBackup.import("""{"devices": []}""")
        }
        assertTrue("异常信息应含 schemaVersion，实际：${e.message}", e.message!!.contains("schemaVersion"))
    }
}
