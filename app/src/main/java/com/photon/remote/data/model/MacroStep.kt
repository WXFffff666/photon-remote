package com.photon.remote.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 宏步骤（计划 §2.2 非表数据类）。
 *
 * 宏的步骤列表以 JSON 字符串（stepsJson）存储在 Macro 表；
 * 执行宏（Todo 33）时按序引用 deviceId + buttonId 发送。
 */
@Serializable
data class MacroStep(
    val deviceId: Long,
    val buttonId: Long,
    val delayMs: Long = 300L,   // 上一步发送后的等待间隔（毫秒）
) {
    companion object {
        /** 宏步骤 JSON 编解码器（宏清理 / 备份导入 / 宏执行共用） */
        val codec: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
