package com.photon.remote.data.repository

import com.photon.remote.data.local.ButtonDao
import com.photon.remote.data.local.DeviceDao
import com.photon.remote.data.local.MacroDao
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.MacroStep
import kotlinx.coroutines.flow.Flow

/**
 * 设备仓储（计划 §1 data/repository/DeviceRepository.kt，Todo 8）。
 *
 * 设备 + 按键 + 宏的统一入口：CRUD、排序、收藏、以及删除设备时的级联清理
 * （RemoteButton 由外键 CASCADE 删除；宏步骤按 §2.2 宏清理规则从 stepsJson 移除）。
 */
class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val buttonDao: ButtonDao,
    private val macroDao: MacroDao,
) {

    /** 设备列表（按 sortOrder 升序） */
    val devices: Flow<List<Device>> = deviceDao.observeDevices()

    /** 宏列表（按 sortOrder 升序） */
    val macros: Flow<List<Macro>> = macroDao.observeMacros()

    // ---------- 设备 CRUD ----------

    /** 新增设备，返回自增 id */
    suspend fun addDevice(device: Device): Long = deviceDao.insertDevice(device)

    /** 整体更新设备（名称/型号/布局等字段） */
    suspend fun updateDevice(device: Device) = deviceDao.updateDevice(device)

    /** 重命名设备 */
    suspend fun renameDevice(deviceId: Long, name: String) =
        deviceDao.renameDevice(deviceId, name)

    /**
     * 删除设备并级联清理：
     * 1) 该设备全部按键由外键 CASCADE 自动删除；
     * 2) 从所有宏的 stepsJson 中移除引用该 deviceId 的步骤（§2.2 宏清理规则）。
     */
    suspend fun deleteDevice(device: Device) {
        // 先清理宏（此时设备尚在，只改 stepsJson 不涉及外键），再删设备触发按键级联
        macroDao.getMacros().forEach { macro ->
            cleanupMacroSteps(macro, device.id)?.let { macroDao.updateMacro(it) }
        }
        deviceDao.deleteDevice(device)
    }

    // ---------- 排序 / 收藏 ----------

    /** 直接设置设备的 sortOrder 值 */
    suspend fun sortDevice(deviceId: Long, sortOrder: Int) =
        deviceDao.updateSortOrder(deviceId, sortOrder)

    /**
     * 移动设备到列表指定位置（0..n-1）：以当前 sortOrder 顺序重排全部设备，
     * 保证 sortOrder 恒为 0..n-1 连续整数。
     */
    suspend fun moveDevice(deviceId: Long, toIndex: Int) {
        val all = deviceDao.getAllDevices()
        val target = all.firstOrNull { it.id == deviceId } ?: return   // 设备不存在则忽略
        if (all.size <= 1) return
        val rest = all.filter { it.id != deviceId }
        val clampedIndex = toIndex.coerceIn(0, rest.size)
        val reordered = rest.toMutableList().apply { add(clampedIndex, target) }
        reordered.forEachIndexed { index, device -> deviceDao.updateSortOrder(device.id, index) }
    }

    /** 收藏切换 */
    suspend fun setFavorite(deviceId: Long, isFavorite: Boolean) =
        deviceDao.setFavorite(deviceId, isFavorite)

    // ---------- 按键 CRUD ----------

    /** 读取某设备全部按键（按布局内顺序） */
    suspend fun getButtons(deviceId: Long): List<RemoteButton> =
        buttonDao.getButtonsForDevice(deviceId)

    /** 新增按键，返回自增 id */
    suspend fun addButton(button: RemoteButton): Long = buttonDao.insertButton(button)

    /** 批量新增按键（默认布局模板生成时使用） */
    suspend fun addButtons(buttons: List<RemoteButton>) {
        buttonDao.insertButtons(buttons)
    }

    /** 更新按键 */
    suspend fun updateButton(button: RemoteButton) = buttonDao.updateButton(button)

    /** 删除按键 */
    suspend fun deleteButton(button: RemoteButton) = buttonDao.deleteButton(button)

    // ---------- 宏 CRUD ----------

    /** 新增宏，返回自增 id */
    suspend fun addMacro(macro: Macro): Long = macroDao.insertMacro(macro)

    /** 更新宏（步骤/名称/图标/排序） */
    suspend fun updateMacro(macro: Macro) = macroDao.updateMacro(macro)

    /** 删除宏 */
    suspend fun deleteMacro(macro: Macro) = macroDao.deleteMacro(macro)

    /** 读取单个宏 */
    suspend fun getMacro(id: Long): Macro? = macroDao.getMacro(id)

    // ---------- 宏清理（§2.2 宏清理规则 2） ----------

    /**
     * 从宏步骤中移除指定设备的全部步骤；若步骤未变化返回 null（避免无谓写库）。
     * stepsJson 损坏时按"无步骤"处理（不阻断删除流程）。
     */
    private fun cleanupMacroSteps(macro: Macro, deviceId: Long): Macro? {
        val steps = parseSteps(macro.stepsJson)
        val filtered = steps.filterNot { it.deviceId == deviceId }
        if (filtered.size == steps.size) return null
        return macro.copy(stepsJson = MacroStep.codec.encodeToString(filtered))
    }

    /** 解析宏步骤 JSON；空串/损坏数据返回空列表 */
    private fun parseSteps(stepsJson: String): List<MacroStep> {
        if (stepsJson.isBlank()) return emptyList()
        return try {
            MacroStep.codec.decodeFromString<List<MacroStep>>(stepsJson)
        } catch (e: Exception) {
            // 损坏的 stepsJson：按无步骤处理，删除设备流程不被单条脏数据阻断
            emptyList()
        }
    }
}
