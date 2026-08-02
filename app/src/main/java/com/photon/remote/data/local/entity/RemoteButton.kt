package com.photon.remote.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photon.remote.data.model.ButtonShape

/**
 * 遥控器按键实体（计划 §2.2，表 remote_buttons）。
 *
 * 外键级联：删除设备时，其全部按键随 CASCADE 自动删除。
 * 注意：`order` 为 SQLite 关键字，查询中的排序需用反引号转义。
 */
@Entity(
    tableName = "remote_buttons",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId")],
)
data class RemoteButton(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val keyId: String,                      // 语义键：POWER / CH_UP / CH_DOWN / VOL_UP / VOL_DOWN / MUTE / NUM_0..9 / OK / UP / DOWN / LEFT / RIGHT / BACK / MENU / INPUT / CUSTOM_<n>
    val label: String,                      // 显示文字
    val icon: String? = null,               // 图标键名，取自静态白名单 IconMap（防 R8 裁剪，见计划 §5.5/Todo 40）
    val actionJson: String,                 // ButtonAction 序列化（见 data/model/ButtonAction.kt）
    val order: Int = 0,                     // 布局内排序
    val col: Int = 0, val row: Int = 0,     // 自定义布局网格坐标（LayoutEditor）
    val colSpan: Int = 1, val rowSpan: Int = 1,
    val shape: ButtonShape = ButtonShape.ROUNDED,   // 圆形/圆角矩形
    val textOnly: Boolean = false,          // 文字模式（辅助阅读）
)
