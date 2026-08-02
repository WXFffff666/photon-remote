package com.photon.remote.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.photon.remote.data.local.AppDatabase
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.MacroStep
import com.photon.remote.data.model.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DeviceRepository 单测（计划 §2.2 宏清理规则 / Todo 8，Robolectric + Room in-memory）。
 *
 * 覆盖：设备 CRUD、重命名、排序、收藏、按键级联删除、删除设备时宏步骤清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DeviceRepository(db.deviceDao(), db.buttonDao(), db.macroDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 设备 CRUD ----------

    @Test
    fun 新增设备_返回自增id且可读回() = runBlocking {
        val id = repository.addDevice(newDevice("电视"))
        assertTrue(id > 0)
        val all = repository.devices.first()
        assertEquals(1, all.size)
        assertEquals("电视", all[0].name)
        assertEquals(id, all[0].id)
    }

    @Test
    fun 重命名设备_名称更新() = runBlocking {
        val id = repository.addDevice(newDevice("电视"))
        repository.renameDevice(id, "客厅小米电视")
        assertEquals("客厅小米电视", repository.devices.first()[0].name)
    }

    @Test
    fun 更新设备_字段持久化() = runBlocking {
        val id = repository.addDevice(newDevice("空调"))
        val updated = repository.devices.first()[0].copy(name = "卧室格力空调", type = DeviceType.AC)
        repository.updateDevice(updated)
        val after = repository.devices.first()[0]
        assertEquals("卧室格力空调", after.name)
        assertEquals(DeviceType.AC, after.type)
    }

    @Test
    fun 删除设备_设备与按键级联删除() = runBlocking {
        val deviceId = repository.addDevice(newDevice("电视"))
        val buttonId = repository.addButton(
            newButton(deviceId, "POWER", "电源", ButtonAction.SendProtocol(com.photon.remote.ir.core.ProtocolType.NEC, "00FF12ED")),
        )
        assertTrue(buttonId > 0)
        assertEquals(1, repository.getButtons(deviceId).size)

        repository.deleteDevice(repository.devices.first()[0])

        assertTrue(repository.devices.first().isEmpty())
        assertTrue(repository.getButtons(deviceId).isEmpty())
    }

    // ---------- 排序 / 收藏 ----------

    @Test
    fun 收藏切换_持久化() = runBlocking {
        val id = repository.addDevice(newDevice("电视"))
        assertFalse(repository.devices.first()[0].isFavorite)

        repository.setFavorite(id, true)
        assertTrue(repository.devices.first()[0].isFavorite)

        repository.setFavorite(id, false)
        assertFalse(repository.devices.first()[0].isFavorite)
    }

    @Test
    fun moveDevice_重排sortOrder与列表顺序() = runBlocking {
        val a = repository.addDevice(newDevice("A", sortOrder = 0))
        val b = repository.addDevice(newDevice("B", sortOrder = 1))
        val c = repository.addDevice(newDevice("C", sortOrder = 2))
        assertEquals(listOf("A", "B", "C"), repository.devices.first().map { it.name })

        // 把 C 移到最前
        repository.moveDevice(c, 0)
        assertEquals(listOf("C", "A", "B"), repository.devices.first().map { it.name })
        assertEquals(listOf(0, 1, 2), repository.devices.first().map { it.sortOrder })

        // 把 A 移到末尾
        repository.moveDevice(a, 10)   // 越界值应钳制到末尾
        assertEquals(listOf("C", "B", "A"), repository.devices.first().map { it.name })

        // 不存在的设备忽略
        repository.moveDevice(999L, 0)
        assertEquals(listOf("C", "B", "A"), repository.devices.first().map { it.name })
    }

    @Test
    fun sortDevice_直接设置序号() = runBlocking {
        val a = repository.addDevice(newDevice("A", sortOrder = 0))
        val b = repository.addDevice(newDevice("B", sortOrder = 1))
        repository.sortDevice(a, 5)
        repository.sortDevice(b, 3)
        val orders = repository.devices.first().associate { it.name to it.sortOrder }
        assertEquals(5, orders["A"])
        assertEquals(3, orders["B"])
    }

    // ---------- 按键 CRUD ----------

    @Test
    fun 按键_按布局顺序返回() = runBlocking {
        val deviceId = repository.addDevice(newDevice("电视"))
        repository.addButton(newButton(deviceId, "CH_UP", "频道+", null).copy(order = 1))
        repository.addButton(newButton(deviceId, "POWER", "电源", null).copy(order = 0))
        val buttons = repository.getButtons(deviceId)
        assertEquals(listOf("POWER", "CH_UP"), buttons.map { it.keyId })
    }

    // ---------- 宏 CRUD 与清理（§2.2 宏清理规则） ----------

    @Test
    fun 宏_增删改查() = runBlocking {
        val deviceId = repository.addDevice(newDevice("电视"))
        val buttonId = repository.addButton(newButton(deviceId, "POWER", "电源", null))
        val steps = listOf(MacroStep(deviceId, buttonId, 300L))

        val macroId = repository.addMacro(
            Macro(name = "开机宏", icon = "play", stepsJson = MacroStep.codec.encodeToString(steps), sortOrder = 0),
        )
        assertTrue(macroId > 0)
        assertEquals(1, repository.macros.first().size)

        // 更新步骤
        val saved = repository.getMacro(macroId)!!
        repository.updateMacro(saved.copy(name = "重命名宏", stepsJson = MacroStep.codec.encodeToString<List<MacroStep>>(emptyList())))
        val updated = repository.macros.first()[0]
        assertEquals("重命名宏", updated.name)
        assertEquals(emptyList<MacroStep>(), MacroStep.codec.decodeFromString<List<MacroStep>>(updated.stepsJson))

        // 删除
        repository.deleteMacro(updated)
        assertTrue(repository.macros.first().isEmpty())
    }

    @Test
    fun 删除设备_宏步骤中引用该设备的步骤被移除_其他步骤保留() = runBlocking {
        // 设备 A（将删除）+ 设备 B（保留）
        val deviceA = repository.addDevice(newDevice("电视A"))
        val deviceB = repository.addDevice(newDevice("电视B"))
        val buttonA = repository.addButton(newButton(deviceA, "POWER", "电源", null))
        val buttonB = repository.addButton(newButton(deviceB, "VOL_UP", "音量+", null))

        // 宏1：同时引用 A 与 B（清理后应只剩 B 步骤）
        val macro1 = repository.addMacro(
            Macro(
                name = "组合宏",
                stepsJson = MacroStep.codec.encodeToString(
                    listOf(MacroStep(deviceA, buttonA, 300L), MacroStep(deviceB, buttonB, 500L)),
                ),
            ),
        )
        // 宏2：只引用 A（清理后应为空步骤列表）
        val macro2 = repository.addMacro(
            Macro(name = "A专属宏", stepsJson = MacroStep.codec.encodeToString(listOf(MacroStep(deviceA, buttonA, 300L)))),
        )
        // 宏3：不引用 A（清理后应原样保留）
        val macro3 = repository.addMacro(
            Macro(name = "B专属宏", stepsJson = MacroStep.codec.encodeToString(listOf(MacroStep(deviceB, buttonB, 300L)))),
        )

        // 删除设备 A
        val deviceAEntity = repository.devices.first().first { it.id == deviceA }
        repository.deleteDevice(deviceAEntity)

        // 宏1：仅剩 B 步骤
        val steps1 = MacroStep.codec.decodeFromString<List<MacroStep>>(repository.getMacro(macro1)!!.stepsJson)
        assertEquals(listOf(MacroStep(deviceB, buttonB, 500L)), steps1)
        // 宏2：空列表
        val steps2 = MacroStep.codec.decodeFromString<List<MacroStep>>(repository.getMacro(macro2)!!.stepsJson)
        assertTrue(steps2.isEmpty())
        // 宏3：原样
        val steps3 = MacroStep.codec.decodeFromString<List<MacroStep>>(repository.getMacro(macro3)!!.stepsJson)
        assertEquals(listOf(MacroStep(deviceB, buttonB, 300L)), steps3)

        // 设备 A 及其按键已删除，设备 B 及按键仍在
        assertNull(repository.devices.first().firstOrNull { it.id == deviceA })
        assertNotNull(repository.devices.first().firstOrNull { it.id == deviceB })
        assertTrue(repository.getButtons(deviceA).isEmpty())
        assertEquals(1, repository.getButtons(deviceB).size)
    }

    @Test
    fun 删除设备_无引用宏的设备_宏原样保留() = runBlocking {
        val deviceId = repository.addDevice(newDevice("电视"))
        val buttonId = repository.addButton(newButton(deviceId, "POWER", "电源", null))
        val steps = listOf(MacroStep(deviceId, buttonId, 300L))
        val macroId = repository.addMacro(Macro(name = "宏", stepsJson = MacroStep.codec.encodeToString(steps)))

        val deviceA = repository.addDevice(newDevice("无关设备"))
        repository.deleteDevice(repository.devices.first().first { it.id == deviceA })

        // 引用原设备步骤的宏不受影响
        val saved = repository.getMacro(macroId)!!
        assertEquals(steps, MacroStep.codec.decodeFromString<List<MacroStep>>(saved.stepsJson))
    }

    // ---------- 工具 ----------

    private fun newDevice(name: String, type: DeviceType = DeviceType.TV, sortOrder: Int = 0) = Device(
        name = name,
        type = type,
        brand = "测试品牌",
        codeSource = CodeSource.CUSTOM,
        codeRef = "test-code-ref",
        sortOrder = sortOrder,
    )

    private fun newButton(deviceId: Long, keyId: String, label: String, action: ButtonAction?): RemoteButton =
        RemoteButton(
            deviceId = deviceId,
            keyId = keyId,
            label = label,
            actionJson = action?.toJson() ?: ButtonAction.SendRaw(38000, listOf(9000, 4500)).toJson(),
        )
}
