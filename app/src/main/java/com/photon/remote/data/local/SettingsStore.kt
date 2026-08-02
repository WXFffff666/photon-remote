package com.photon.remote.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photon.remote.data.model.ACStatusData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 实例名（进程内单例委托） */
val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置存储（计划 §1 data/local/SettingsStore.kt，基于 DataStore Preferences）。
 *
 * 主题 / 强调色 / 震动 / 发射路径 / 音频模式 + 每设备空调 AC 状态。
 * AC 状态持久化格式：key "ac_status_{deviceId}"，值为 6 个 Int 原语逗号拼接
 * （acPower,acMode,acTemp,acWindSpeed,acWindDir,changeWindDir），与 ACStatusData 互转；
 * TODO（后续 Todo 14/15 接 irext）：由 AppContainer 的 ACStatusCache 水合/回写，并与 irext ACStatus 互转。
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    // ---------- 主题模式：system=跟随系统 / light=浅色 / dark=深色 / black=纯黑 ----------

    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME_MODE] ?: DEFAULT_THEME_MODE }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    // ---------- 强调色（ARGB Int，0 = 跟随系统/动态取色） ----------

    val accentColor: Flow<Int> = dataStore.data.map { it[KEY_ACCENT_COLOR] ?: DEFAULT_ACCENT_COLOR }

    suspend fun setAccentColor(color: Int) {
        dataStore.edit { it[KEY_ACCENT_COLOR] = color }
    }

    // ---------- 震动反馈 ----------

    val haptic: Flow<Boolean> = dataStore.data.map { it[KEY_HAPTIC] ?: DEFAULT_HAPTIC }

    suspend fun setHaptic(enabled: Boolean) {
        dataStore.edit { it[KEY_HAPTIC] = enabled }
    }

    // ---------- 发射路径：auto=自动路由 / builtin=内置 / usb=USB 外设 / audio=音频转红外 ----------

    val transmitterPath: Flow<String> =
        dataStore.data.map { it[KEY_TRANSMITTER_PATH] ?: DEFAULT_TRANSMITTER_PATH }

    suspend fun setTransmitterPath(path: String) {
        dataStore.edit { it[KEY_TRANSMITTER_PATH] = path }
    }

    // ---------- 音频模式：1LED（mono）/ 2LED（stereo 反相） ----------

    val audioMode: Flow<String> = dataStore.data.map { it[KEY_AUDIO_MODE] ?: DEFAULT_AUDIO_MODE }

    suspend fun setAudioMode(mode: String) {
        dataStore.edit { it[KEY_AUDIO_MODE] = mode }
    }

    // ---------- 每设备空调 AC 状态（6 个 Int 原语） ----------

    /** 读取指定设备的 AC 状态；无历史状态时返回 null */
    fun acStatus(deviceId: Long): Flow<ACStatusData?> =
        dataStore.data.map { prefs ->
            prefs[acStatusKey(deviceId)]?.let { ACStatusData.fromStorageString(it) }
        }

    /** 保存指定设备的 AC 状态（覆盖式） */
    suspend fun setAcStatus(deviceId: Long, status: ACStatusData) {
        dataStore.edit { prefs ->
            prefs[acStatusKey(deviceId)] = status.toStorageString()
        }
    }

    companion object {
        private val KEY_THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR: Preferences.Key<Int> = intPreferencesKey("accent_color")
        private val KEY_HAPTIC: Preferences.Key<Boolean> = booleanPreferencesKey("haptic")
        private val KEY_TRANSMITTER_PATH: Preferences.Key<String> = stringPreferencesKey("transmitter_path")
        private val KEY_AUDIO_MODE: Preferences.Key<String> = stringPreferencesKey("audio_mode")
        private const val AC_STATUS_KEY_PREFIX = "ac_status_"

        private const val DEFAULT_THEME_MODE = "system"
        private const val DEFAULT_ACCENT_COLOR = 0
        private const val DEFAULT_HAPTIC = true
        private const val DEFAULT_TRANSMITTER_PATH = "auto"
        private const val DEFAULT_AUDIO_MODE = "1LED"

        /** 每设备 AC 状态存储 key："ac_status_{deviceId}" */
        fun acStatusKey(deviceId: Long): Preferences.Key<String> =
            stringPreferencesKey("$AC_STATUS_KEY_PREFIX$deviceId")
    }
}
