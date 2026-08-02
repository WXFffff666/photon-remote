package com.photon.remote.data.local

import androidx.room.TypeConverter
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.Operator

/**
 * Room 类型转换器（计划 §2 / Todo 5）。
 *
 * 枚举 ↔ 字符串（存枚举名）。actionJson 本身已是 String，无需转换器。
 * 未知枚举值（历史数据损坏）回退到安全默认值，避免应用崩溃。
 */
class Converters {

    // ---------- DeviceType ----------

    @TypeConverter
    fun deviceTypeToString(value: DeviceType): String = value.name

    @TypeConverter
    fun stringToDeviceType(value: String): DeviceType =
        DeviceType.entries.firstOrNull { it.name == value } ?: DeviceType.OTHER   // 兜底"其他"

    // ---------- Operator（可空） ----------

    @TypeConverter
    fun operatorToString(value: Operator?): String? = value?.name

    @TypeConverter
    fun stringToOperator(value: String?): Operator? =
        value?.let { Operator.entries.firstOrNull { op -> op.name == it } }

    // ---------- CodeSource ----------

    @TypeConverter
    fun codeSourceToString(value: CodeSource): String = value.name

    @TypeConverter
    fun stringToCodeSource(value: String): CodeSource =
        CodeSource.entries.firstOrNull { it.name == value } ?: CodeSource.CUSTOM  // 兜底自定义

    // ---------- ButtonShape ----------

    @TypeConverter
    fun buttonShapeToString(value: ButtonShape): String = value.name

    @TypeConverter
    fun stringToButtonShape(value: String): ButtonShape =
        ButtonShape.entries.firstOrNull { it.name == value } ?: ButtonShape.ROUNDED  // 兜底圆角矩形
}
