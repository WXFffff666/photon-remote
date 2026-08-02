package com.photon.remote.ui.navigation

import android.app.Activity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photon.remote.data.model.DeviceType
import com.photon.remote.ui.ac.AcPanelScreen
import com.photon.remote.ui.adddevice.AddDeviceScreen
import com.photon.remote.ui.home.HomeScreen
import com.photon.remote.ui.remote.RemoteScreen

/**
 * 自适应导航（计划 §5.2 / Todo 26-31）。
 *
 * 路由表：
 *   home                   首页设备列表
 *   addDevice              添加设备向导（全屏）
 *   remote/{deviceId}      遥控器页（deviceId = LongType）
 *   acpanel/{deviceId}     空调面板（deviceId = LongType）
 *
 * 后续 UI worker 接入的路由（占位注释）：macro / importExport / finder / settings。
 * NavigationSuiteScaffold 按窗口宽度自动切换：Compact/Medium = 底部导航栏，
 * Expanded（平板横屏）= 左侧 NavigationRail；底部导航项随对应页面一并接入。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3WindowSizeClassApi::class
)
@Composable
fun PhotonNavHost() {
    val activity = LocalContext.current as? Activity ?: return
    val navController = rememberNavController()

    // 窗口尺寸分类（Compact/Medium/Expanded）：Todo 37 实现平板双栏（ListDetailPaneScaffold）时以它分支
    @Suppress("UNUSED_VARIABLE")
    val windowSizeClass = calculateWindowSizeClass(activity)

    // 自适应信息（窗口尺寸 + 折叠屏姿态）：驱动导航套件的布局类型（底部栏 / 侧边栏）
    val adaptiveInfo = currentWindowAdaptiveInfo()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            // TODO(后续 UI worker 接入)：底部导航项（遥控器/宏/设置 + 添加设备 FAB）
        },
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    ) {
        NavHost(navController = navController, startDestination = "home") {
            // 首页：设备列表（点击卡片 → 遥控器/空调面板；FAB → 添加向导）
            composable("home") {
                HomeScreen(
                    onDeviceClick = { device ->
                        navController.navigate(
                            if (device.type == DeviceType.AC) "acpanel/${device.id}"
                            else "remote/${device.id}"
                        )
                    },
                    onAddClick = { navController.navigate("addDevice") },
                )
            }

            // 添加设备向导（分步 HorizontalPager，保存成功/关闭即返回首页）
            composable("addDevice") {
                AddDeviceScreen(onFinished = { navController.popBackStack() })
            }

            // 遥控器页（deviceId 为 Long 类型参数）
            composable(
                route = "remote/{deviceId}",
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                RemoteScreen(
                    deviceId = entry.arguments?.getLong("deviceId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            // 空调面板（deviceId 为 Long 类型参数）
            composable(
                route = "acpanel/{deviceId}",
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                AcPanelScreen(
                    deviceId = entry.arguments?.getLong("deviceId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            // TODO(后续 UI worker 接入)：macro（宏列表+编辑）/ importExport（导入导出）/
            // finder（暴力找码）/ settings（设置）路由，由对应页面 worker 追加
        }
    }
}
