package com.photon.remote.codebase.importer

import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.MacroStep
import com.photon.remote.data.model.Operator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// =====================================================================
// JSON 备份：导出 devices + buttons + macros 全量；
// 导入逐记录校验、非法记录跳过（不中断）、全量替换。
//
// Device / RemoteButton / Macro 是 Room 实体（无 @Serializable 注解），
// 直接序列化会污染实体，故此处定义独立的 @Serializable 备份副本类
// （BackupDevice / BackupButton / BackupMacro）+ 互转函数。
// 枚举（DeviceType / CodeSource / Operator / ButtonShape）由
// kotlinx.serialization 原生按枚举名序列化，无需注解。
// =====================================================================

/** 设备备份副本：字段与 Room 实体 Device 一一对应 */
@Serializable
data class BackupDevice(
    val id: Long = 0,
    val name: String,
    val type: DeviceType,
    val brand: String,
    val region: String? = null,
    val city: String? = null,
    val operator: Operator? = null,
    val model: String? = null,
    val codeSource: CodeSource,
    val codeRef: String,
    val layoutId: String = "default",
    val layoutJson: String? = null,
    val colorSeed: Long = 0L,
    val sortOrder: Int = 0,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 按键备份副本：字段与 Room 实体 RemoteButton 一一对应 */
@Serializable
data class BackupButton(
    val id: Long = 0,
    val deviceId: Long,
    val keyId: String,
    val label: String,
    val icon: String? = null,
    val actionJson: String,
    val order: Int = 0,
    val col: Int = 0,
    val row: Int = 0,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val shape: ButtonShape = ButtonShape.ROUNDED,
    val textOnly: Boolean = false,
)

/** 宏备份副本：字段与 Room 实体 Macro 一一对应（stepsJson 仍为 MacroStep 序列化字符串） */
@Serializable
data class BackupMacro(
    val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val stepsJson: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 备份文件根结构（schemaVersion=1 为当前唯一受支持版本） */
@Serializable
data class BackupData(
    val schemaVersion: Int = 1,
    val devices: List<BackupDevice> = emptyList(),
    val buttons: List<BackupButton> = emptyList(),
    val macros: List<BackupMacro> = emptyList(),
)

/** 导入结果：合法记录 + 被跳过的记录原因（中文，含设备名 / 按键标识 / 宏名上下文） */
data class ImportResult(
    val devices: List<Device>,
    val buttons: List<RemoteButton>,
    val macros: List<Macro>,
    val skipped: List<String>,
)

object JsonBackup {

    /** 备份专用 JSON：忽略未知字段（新旧版本兼容）、编码默认值（字段完整） */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 导出全量备份：实体 → 备份副本 → JSON 字符串。
     */
    fun export(devices: List<Device>, buttons: List<RemoteButton>, macros: List<Macro>): String =
        json.encodeToString(
            BackupData.serializer(),
            BackupData(
                schemaVersion = 1,
                devices = devices.map { it.toBackup() },
                buttons = buttons.map { it.toBackup() },
                macros = macros.map { it.toBackup() },
            ),
        )

    /**
     * 导入备份：整包解析 → schemaVersion 校验 → 逐条解码校验。
     *
     * - schemaVersion 不支持（缺失或 != 1）时抛 IllegalArgumentException（中文原因），不产生部分结果；
     * - 单条记录解码失败仅跳过该条（原因入 skipped），其余记录照常保留；
     * - 按键引用不存在的设备（孤儿按键）跳过；
     * - 宏步骤中 deviceId 失效仅记录 skipped，宏整体保留。
     */
    fun import(content: String): ImportResult {
        // 1) 整包解析为 JsonObject（不做整体解码，以便逐条校验）
        val root = try {
            json.parseToJsonElement(content) as? JsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("备份 JSON 解析失败：${e.message}", e)
        } ?: throw IllegalArgumentException("备份 JSON 根节点必须是对象")

        // 2) schemaVersion 校验（== 1 才支持）
        val version = root.string("schemaVersion")?.toIntOrNull()
        if (version == null) {
            throw IllegalArgumentException("备份缺少 schemaVersion 字段，无法识别备份格式")
        }
        if (version != 1) {
            throw IllegalArgumentException("不支持的备份版本 v$version（当前仅支持 v1）")
        }

        val skipped = mutableListOf<String>()

        // 3) 设备：逐条解码，非法记录跳过
        val devices = decodeAll(root["devices"], BackupDevice.serializer(), "设备", { it.string("name") }, skipped)
            .map { it.toEntity() }
        val deviceIds = devices.map { it.id }.toSet()

        // 4) 按键：逐条解码 + 孤儿校验（deviceId 必须存在于备份设备中）
        val buttons = decodeAll(root["buttons"], BackupButton.serializer(), "按键", { it.string("keyId") ?: it.string("label") }, skipped)
            .filter { b ->
                if (b.deviceId in deviceIds) true
                else {
                    skipped += "按键「${b.label}」(deviceId=${b.deviceId}) 引用的设备不在备份中，已跳过"
                    false
                }
            }
            .map { it.toEntity() }

        // 5) 宏：逐条解码；stepsJson 内失效步骤记录 skipped，宏本身保留
        val macros = decodeAll(root["macros"], BackupMacro.serializer(), "宏", { it.string("name") }, skipped)
            .map { it.toEntity() }
        macros.forEach { m ->
            val steps = try {
                MacroStep.codec.decodeFromString<List<MacroStep>>(m.stepsJson)
            } catch (e: Exception) {
                skipped += "宏「${m.name}」步骤列表解析失败，宏已保留：${e.message}"
                null
            }
            steps?.filter { it.deviceId !in deviceIds }?.forEach { s ->
                skipped += "宏「${m.name}」步骤(deviceId=${s.deviceId}, buttonId=${s.buttonId})引用的设备不在备份中，宏已保留"
            }
        }

        return ImportResult(devices = devices, buttons = buttons, macros = macros, skipped = skipped)
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 逐条解码 JSON 数组：每条记录单独 try 解码，失败记录原因入 skipped 且不中断。
     * [kind] 记录类型中文名（用于错误信息）；[nameOf] 从原始 JSON 对象提取展示名（失败时回退序号）。
     */
    private fun <T> decodeAll(
        elements: JsonElement?,
        serializer: KSerializer<T>,
        kind: String,
        nameOf: (JsonObject) -> String?,
        skipped: MutableList<String>,
    ): List<T> {
        val array = elements as? JsonArray ?: return emptyList()
        val result = mutableListOf<T>()
        array.forEachIndexed { index, element ->
            try {
                result += json.decodeFromJsonElement(serializer, element)
            } catch (e: Exception) {
                val name = (element as? JsonObject)?.let(nameOf) ?: "#${index + 1}"
                skipped += "$kind「$name」记录非法，已跳过：${e.message}"
            }
        }
        return result
    }

    /** 从 JSON 对象读取字符串字段（缺省 / 非字符串返回 null） */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------------------------
    // 实体 ↔ 备份副本互转
    // ------------------------------------------------------------------

    private fun Device.toBackup() = BackupDevice(
        id = id, name = name, type = type, brand = brand, region = region, city = city,
        operator = operator, model = model, codeSource = codeSource, codeRef = codeRef,
        layoutId = layoutId, layoutJson = layoutJson, colorSeed = colorSeed,
        sortOrder = sortOrder, isFavorite = isFavorite, createdAt = createdAt,
    )

    private fun BackupDevice.toEntity() = Device(
        id = id, name = name, type = type, brand = brand, region = region, city = city,
        operator = operator, model = model, codeSource = codeSource, codeRef = codeRef,
        layoutId = layoutId, layoutJson = layoutJson, colorSeed = colorSeed,
        sortOrder = sortOrder, isFavorite = isFavorite, createdAt = createdAt,
    )

    private fun RemoteButton.toBackup() = BackupButton(
        id = id, deviceId = deviceId, keyId = keyId, label = label, icon = icon,
        actionJson = actionJson, order = order, col = col, row = row,
        colSpan = colSpan, rowSpan = rowSpan, shape = shape, textOnly = textOnly,
    )

    private fun BackupButton.toEntity() = RemoteButton(
        id = id, deviceId = deviceId, keyId = keyId, label = label, icon = icon,
        actionJson = actionJson, order = order, col = col, row = row,
        colSpan = colSpan, rowSpan = rowSpan, shape = shape, textOnly = textOnly,
    )

    private fun Macro.toBackup() = BackupMacro(
        id = id, name = name, icon = icon, stepsJson = stepsJson,
        sortOrder = sortOrder, createdAt = createdAt,
    )

    private fun BackupMacro.toEntity() = Macro(
        id = id, name = name, icon = icon, stepsJson = stepsJson,
        sortOrder = sortOrder, createdAt = createdAt,
    )
}
