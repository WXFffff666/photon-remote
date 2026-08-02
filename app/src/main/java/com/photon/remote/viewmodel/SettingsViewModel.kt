package com.photon.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.PhotonApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel（计划 §5.9 / Todo 36）。
 *
 * 五个设置项全部持久化到 SettingsStore（DataStore）：
 * 主题（system/light/dark/black）、强调色（ARGB Int）、震动开关、
 * 发射路径（auto/builtin/usb/audio）、音频模式（1LED/2LED）。
 *
 * 主题/强调色由 MainActivity 收集 DataStore 流驱动 PhotonTheme，**即时生效**；
 * 震动开关当前仅持久化（RemoteKey 的按压震动尚未接线，见设置页注释）。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    /** 手动 DI 容器（依赖统一从容器获取，AndroidViewModel 模式） */
    private val container get() = (getApplication<PhotonApplication>()).container
    private val settings get() = container.settingsStore

    // ---------- 设置流（UI collectAsState 使用） ----------

    /** 主题模式：system=跟随系统 / light=浅色 / dark=深色 / black=纯黑 */
    val themeMode: Flow<String> = settings.themeMode

    /** 强调色（ARGB Int，0 = 跟随系统/动态取色） */
    val accentColor: Flow<Int> = settings.accentColor

    /** 震动反馈开关 */
    val haptic: Flow<Boolean> = settings.haptic

    /** 发射路径：auto=自动 / builtin=内置 / usb=USB 外设 / audio=音频转红外 */
    val transmitterPath: Flow<String> = settings.transmitterPath

    /** 音频模式：1LED（mono）/ 2LED（stereo 反相） */
    val audioMode: Flow<String> = settings.audioMode

    // ---------- 写操作 ----------

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setAccentColor(argb: Int) {
        viewModelScope.launch { settings.setAccentColor(argb) }
    }

    fun setHaptic(enabled: Boolean) {
        viewModelScope.launch { settings.setHaptic(enabled) }
    }

    fun setTransmitterPath(path: String) {
        viewModelScope.launch { settings.setTransmitterPath(path) }
    }

    fun setAudioMode(mode: String) {
        viewModelScope.launch { settings.setAudioMode(mode) }
    }

    // ---------- 关于 ----------

    /** 应用版本名（PackageManager 读取，避免依赖 BuildConfig 开关） */
    val versionName: String by lazy {
        runCatching {
            val app = getApplication<Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }
}
