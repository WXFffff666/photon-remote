// com.photon.remote.MainActivity.kt —— 单 Activity，Compose 入口（计划 §1 / §6.6）
package com.photon.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import com.photon.remote.ui.navigation.PhotonNavHost
import com.photon.remote.ui.theme.AccentSeed
import com.photon.remote.ui.theme.AccentSeeds
import com.photon.remote.ui.theme.PhotonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 桌面快捷方式直达（Todo 39）：intent 携带 deviceId → 启动后直接进入对应遥控器/空调页
        val launchDeviceId = intent?.getLongExtra(EXTRA_DEVICE_ID, -1L)?.takeIf { it > 0 }
        setContent {
            // 主题设置即时生效（Todo 36）：DataStore 流（主题模式 / 强调色）驱动 PhotonTheme，
            // 设置页保存后本组合立即重组，无需重启应用。
            val container = (application as PhotonApplication).container
            val themeMode by container.settingsStore.themeMode.collectAsState(initial = "system")
            val accentArgb by container.settingsStore.accentColor.collectAsState(initial = 0)

            // 主题模式 → 深色开关：light=浅色；dark/black=深色；system=跟随系统
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark", "black" -> true
                else -> isSystemInDarkTheme()
            }
            // 强调色：0 = 默认种子（Android 12+ 动态取色忽略种子，低版本用种子生成配色）
            val accentSeed = AccentSeeds.firstOrNull { it.toArgb() == accentArgb } ?: AccentSeed

            PhotonTheme(
                darkTheme = darkTheme,
                pureBlackAmoled = themeMode == "black",
                accentSeed = accentSeed,
            ) {
                // 自适应导航骨架（NavigationSuiteScaffold + NavHost：
                // home / addDevice / remote/{deviceId} / acpanel/{deviceId} / layoutEditor/{deviceId} /
                // macro / importExport / finder / settings）
                PhotonNavHost(initialDeviceId = launchDeviceId)
            }
        }
    }

    companion object {
        /** 桌面快捷方式直达参数（Todo 39）：deviceId，MainActivity 据此直接导航 */
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
