package com.photon.remote.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 宏实体（计划 §2.2，表 macros）。
 *
 * stepsJson 为 List<MacroStep> 的 JSON 序列化（见 data/model/MacroStep.kt）。
 */
@Entity(tableName = "macros")
data class Macro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val stepsJson: String,                  // List<MacroStep> 序列化
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
