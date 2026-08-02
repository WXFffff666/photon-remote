package com.photon.remote.ui.remote

import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义布局持久化编解码（计划 §5.6 / Todo 32 装配）。
 *
 * 布局以「简单 JSON 数组」形式存于 Device.layoutJson：
 *   [{"keyId":"POWER","label":"电源","col":0,"row":0,"colSpan":2,"rowSpan":2,"isRound":true}, ...]
 * 保存时 Device.layoutId 置为 "custom_json"；layoutJson 为空 = 走默认布局模板。
 */

/** 自定义布局网格：8 列 × 6 行（与 LayoutEditor 画布一致） */
const val CUSTOM_GRID_COLS = 8
const val CUSTOM_GRID_ROWS = 6

/** 布局 JSON 中的单个按键（与编辑器 EditableKey 字段对应） */
@Serializable
data class LayoutKey(
    val keyId: String,
    val label: String,
    val col: Int = 0,
    val row: Int = 0,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val isRound: Boolean = false,
)

/** 布局 JSON 编解码实例（忽略未知字段，向前兼容） */
private val LayoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 编辑器按键列表 → 布局 JSON 字符串 */
fun encodeLayout(keys: List<EditableKey>): String =
    LayoutJson.encodeToString(keys.map { it.toLayoutKey() })

/** 布局 JSON 字符串 → 布局按键列表；非法数据返回 null（调用方走默认布局兜底） */
fun decodeLayout(json: String): List<LayoutKey>? = try {
    LayoutJson.decodeFromString<List<LayoutKey>>(json)
} catch (e: Exception) {
    null
}

/** 编辑器按键 → 布局键（持久化字段） */
fun EditableKey.toLayoutKey() = LayoutKey(
    keyId = keyId,
    label = label,
    col = col,
    row = row,
    colSpan = colSpan,
    rowSpan = rowSpan,
    isRound = isRound,
)

/** 数据库按键 → 编辑器按键（isRound ← shape 圆形） */
fun RemoteButton.toEditableKey() = EditableKey(
    id = id,
    keyId = keyId,
    label = label,
    icon = icon,
    col = col,
    row = row,
    colSpan = colSpan,
    rowSpan = rowSpan,
    isRound = shape == ButtonShape.CIRCLE,
)
