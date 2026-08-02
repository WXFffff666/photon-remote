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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photon.remote.IrTestScreen

/**
 * 自适应导航骨架（计划 §5.2）。
 *
 * NavigationSuiteScaffold 按窗口宽度自动切换：Compact/Medium = 底部导航栏，
 * Expanded（平板横屏）= 左侧 NavigationRail（布局类型由 calculateFromAdaptiveInfo 计算）。
 * NavHost 当前只有 Home 空路由（显示第一步测试屏），后续 Todo 26+ 逐个补齐页面。
 *
 * 自适应 API 均为实验性，按计划标注三注解：
 * ExperimentalMaterial3Api / ExperimentalMaterial3AdaptiveApi / ExperimentalMaterial3WindowSizeClassApi
 *
 * 注意（相对计划 §5.2 的两处 API 落实）：
 *  - material3 1.3.x 起 NavigationSuiteScaffold 已从 androidx.compose.material3 迁移到
 *    material3-adaptive-navigation-suite 构件（androidx.compose.material3.adaptive.navigationsuite 包），
 *    布局类型参数化（NavigationSuiteType），需显式传入由 WindowAdaptiveInfo 计算的结果；
 *  - material3-window-size-class 1.3.x 的 API 是 calculateWindowSizeClass(activity)，
 *    不存在 currentWindowSizeClass()；ListDetailPaneScaffold 双栏在 Todo 37 实现。
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

    // 窗口尺寸分类（Compact/Medium/Expanded）：当前骨架阶段暂未使用，
    // Todo 37 实现平板双栏（ListDetailPaneScaffold）时以它分支
    @Suppress("UNUSED_VARIABLE")
    val windowSizeClass = calculateWindowSizeClass(activity)

    // 自适应信息（窗口尺寸 + 折叠屏姿态）：驱动导航套件的布局类型（底部栏 / 侧边栏）。
    // 注意：adaptive 1.1.0 的 API 是 @Composable currentWindowAdaptiveInfo()，
    // 不存在 calculateWindowAdaptiveInfo(activity)（那是其它版本/平台的函数）
    val adaptiveInfo = currentWindowAdaptiveInfo()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            // 导航项（遥控器/宏/设置 + 添加设备 FAB）待对应页面完成后加入（Todo 26+）
        },
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    ) {
        NavHost(navController = navController, startDestination = "home") {
            // Home 空路由：当前显示第一步测试屏（红外检测 + NEC 测试码），Todo 26 替换为设备列表
            composable("home") { IrTestScreen() }
        }
    }
}
