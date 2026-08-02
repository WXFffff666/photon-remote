package com.photon.remote.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.Operator

/**
 * 设备实体（计划 §2.2，表 devices）。
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                       // 用户命名（默认"电视"）
    val type: DeviceType,
    val brand: String,                      // 品牌名
    val region: String? = null,             // 省份（仅 STB）
    val city: String? = null,               // 城市（仅 STB）
    val operator: Operator? = null,         // 运营商（仅 STB）
    val model: String? = null,              // 型号/码组名（用户可见）
    val codeSource: CodeSource,
    val codeRef: String,                    // 码库引用：IREXT bin 文件名 / irdb CSV 相对路径 / 导入文件内容标识
    val layoutId: String = "default",       // 布局模板：default_tv / default_stb / default_ac / custom_json
    val layoutJson: String? = null,         // 自定义布局（LayoutEditor 产出，见计划 §5.6）
    val colorSeed: Long = 0L,               // 卡片取色种子（品牌哈希）
    val sortOrder: Int = 0,
    val isFavorite: Boolean = false,        // 收藏（计划 Todo 38 收藏置顶）
    val createdAt: Long = System.currentTimeMillis(),
)
