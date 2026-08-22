package com.photon.remote.ui.navigation

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photon.remote.PhotonApplication
import com.photon.remote.data.model.DeviceType
import com.photon.remote.ui.ac.AcPanelScreen
import com.photon.remote.ui.adddevice.AddDeviceScreen
import com.photon.remote.ui.finder.IrFinderScreen
import com.photon.remote.ui.home.HomeDetailPane
import com.photon.remote.ui.home.HomeScreen
import com.photon.remote.ui.importexport.ImportExportScreen
import com.photon.remote.ui.macro.MacroEditScreen
import com.photon.remote.ui.macro.MacroListScreen
import com.photon.remote.ui.remote.LayoutEditorRoute
import com.photon.remote.ui.remote.RemoteScreen
import com.photon.remote.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

/**
 * 自适应导航（计划 §5.2 / Todo 26-31 + 34-37 + 39 收尾）。
 *
 * 路由表：
 *   home                    首页（Compact 全屏列表；Expanded 平板 ListDetail 双栏）
 *   addDevice               添加设备向导（全屏）
 *   remote/{deviceId}       遥控器页（deviceId = LongType）
 *   acpanel/{deviceId}      空调面板（deviceId = LongType）
 *   layoutEditor/{deviceId} 自定义布局编辑器（Todo 32 装配）
 *   macro / macroEdit/{id?} 宏列表 / 宏编辑（id 缺省 = 新建）
 *   importExport / finder / settings
 *
 * NavigationSuiteScaffold 按窗口宽度自动切换：Compact/Medium = 底部导航栏，
 * Expanded（平板横屏）= 左侧 NavigationRail；导航项：遥控器 / 宏 / 设置。
 * 平板（≥840dp 宽）Home 用 ListDetailPaneScaffold：左列表 + 右侧内嵌遥控器
 * （选中即显示不跳页，Todo 37）。
 * [initialDeviceId]：桌面快捷方式直达（Todo 39，MainActivity intent extra deviceId）。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3WindowSizeClassApi::class,
)
@Composable
fun PhotonNavHost(initialDeviceId: Long? = null) {
    val activity = LocalActivity.current ?: return
    val app = LocalContext.current.applicationContext as PhotonApplication
    val navController = rememberNavController()

    // 窗口尺寸分类（Todo 37）：Expanded（≥840dp 宽）→ Home 双栏；Compact/Medium → 底部导航 + 全屏页
    val windowSizeClass = calculateWindowSizeClass(activity)
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    // 自适应信息（窗口尺寸 + 折叠屏姿态）：驱动导航套件的布局类型（底部栏 / 侧边栏）
    val adaptiveInfo = currentWindowAdaptiveInfo()

    // 当前路由（底部导航高亮判断）
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 桌面快捷方式直达（Todo 39）：intent 携带 deviceId → 启动后直接进入对应遥控器/空调页
    LaunchedEffect(initialDeviceId) {
        if (initialDeviceId != null) {
            val device = app.container.repository.devices.first().firstOrNull { it.id == initialDeviceId }
            if (device != null) {
                navController.navigate(
                    if (device.type == DeviceType.AC) "acpanel/${device.id}" else "remote/${device.id}",
                ) { launchSingleTop = true }
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            // 遥控器（首页 / 遥控器 / 空调面板 / 布局编辑器都归属本 Tab）
            item(
                icon = { Icon(Icons.Rounded.Tv, contentDescription = "遥控器") },
                label = { Text("遥控器") },
                selected = currentRoute == "home" ||
                    currentRoute?.startsWith("remote/") == true ||
                    currentRoute?.startsWith("acpanel/") == true ||
                    currentRoute?.startsWith("layoutEditor/") == true,
                onClick = { navController.navigateToTab("home") },
            )
            // 宏
            item(
                icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = "宏") },
                label = { Text("宏") },
                selected = currentRoute == "macro" || currentRoute?.startsWith("macroEdit") == true,
                onClick = { navController.navigateToTab("macro") },
            )
            // 设置
            item(
                icon = { Icon(Icons.Rounded.Settings, contentDescription = "设置") },
                label = { Text("设置") },
                selected = currentRoute == "settings",
                onClick = { navController.navigateToTab("settings") },
            )
        },
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo),
    ) {
        NavHost(navController = navController, startDestination = "home") {
            // 首页：Compact 全屏设备列表；Expanded（平板）ListDetail 双栏（Todo 37）
            composable("home") {
                if (isExpanded) {
                    HomeDetailPane(
                        onAddClick = { navController.navigate("addDevice") },
                        onOpenRemote = { device ->
                            navController.navigate(
                                if (device.type == DeviceType.AC) "acpanel/${device.id}"
                                else "remote/${device.id}",
                            )
                        },
                        onEditLayout = { id -> navController.navigate("layoutEditor/$id") },
                    )
                } else {
                    HomeScreen(
                        onDeviceClick = { device ->
                            navController.navigate(
                                if (device.type == DeviceType.AC) "acpanel/${device.id}"
                                else "remote/${device.id}",
                            )
                        },
                        onAddClick = { navController.navigate("addDevice") },
                    )
                }
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

            // 自定义布局编辑器（Todo 32 装配：RemoteScreen 编辑按钮 → 全屏导航进入）
            composable(
                route = "layoutEditor/{deviceId}",
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                LayoutEditorRoute(
                    deviceId = entry.arguments?.getLong("deviceId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            // 宏列表（底部导航项）
            composable("macro") {
                MacroListScreen(
                    onCreateClick = { navController.navigate("macroEdit") },
                    onEditClick = { id -> navController.navigate("macroEdit/$id") },
                )
            }

            // 宏编辑（新建 id 缺省；编辑 id 为宏 id，LongType 可选参数）
            composable(
                route = "macroEdit/{id?}",
                arguments = listOf(navArgument("id") {
                    type = NavType.LongType
                    defaultValue = -1L
                }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1L
                MacroEditScreen(
                    macroId = id.takeIf { it > 0 },
                    onBack = { navController.popBackStack() },
                )
            }

            // 导入导出：Flipper .ir / LIRC .conf / JSON 备份（Todo 34）
            composable("importExport") {
                ImportExportScreen()
            }

            // 暴力找码：协议 + 前缀迭代发码（Todo 35）
            composable("finder") {
                IrFinderScreen()
            }

            // 设置：主题/强调色/发射路径/音频/关于（Todo 36）
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}

/** 底部导航切换：回到目标 Tab（栈内单实例 + 状态保存恢复） */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
