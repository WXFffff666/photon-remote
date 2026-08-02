package com.photon.remote.data.model

/**
 * 空调状态等价数据类（6 个 Int 原语）。
 *
 * TODO（后续 Todo 14/15 接入 irext 时）：与 irext 的 ACStatus 包装类互转——
 * acPower(0关1开) acMode(0制冷1制热2自动3送风4除湿) acTemp(按模式动态钳制，典型 16..30)
 * acWindSpeed(0自动1低2中3高) acWindDir(0/1) changeWindDir(扫风切换标记)。
 *
 * 持久化：SettingsStore 按 deviceId 存 6 个 Int 原语（逗号拼接字符串），
 * 与 ACStatus 的互转函数在此等价实现。
 */
data class ACStatusData(
    val acPower: Int = 0,
    val acMode: Int = 0,
    val acTemp: Int = 26,
    val acWindSpeed: Int = 0,
    val acWindDir: Int = 0,
    val changeWindDir: Int = 0,
) {
    /**
     * 序列化为存储字符串：逗号拼接的 6 个 Int（"acPower,acMode,acTemp,acWindSpeed,acWindDir,changeWindDir"）。
     */
    fun toStorageString(): String =
        listOf(acPower, acMode, acTemp, acWindSpeed, acWindDir, changeWindDir).joinToString(",")

    companion object {
        /**
         * 从存储字符串解析；字段数不足 6 或含非数字时返回 null（视为无历史状态）。
         */
        fun fromStorageString(raw: String): ACStatusData? {
            val parts = raw.split(',').map { it.trim() }
            if (parts.size != 6) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return ACStatusData(
                acPower = values[0],
                acMode = values[1],
                acTemp = values[2],
                acWindSpeed = values[3],
                acWindDir = values[4],
                changeWindDir = values[5],
            )
        }
    }
}
