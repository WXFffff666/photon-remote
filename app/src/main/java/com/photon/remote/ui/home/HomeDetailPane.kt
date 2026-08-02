package com.photon.remote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.model.DeviceType
import com.photon.remote.ui.ac.AcPanelScreen
import com.photon.remote.ui.remote.RemoteScreen
import com.photon.remote.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * 平板双栏首页（计划 §5.10 / Todo 37）：ListDetailPaneScaffold。
 *
 * 左列表 = HomeScreen 复用（单击设备回调选中，不跳页）；右详情 = 选中设备的
 * 内嵌遥控器 / 空调面板。仅在 Expanded（≥840dp 宽）分支下使用：
 *   - 遥控器容器居中 maxWidth 640dp，方向键 72dp；
 *   - 列表点击 → navigateTo(Detail) 切换右栏（双栏同时可见，无跳页）。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeDetailPane(
    onAddClick: () -> Unit,
    onOpenRemote: (Device) -> Unit,
    onEditLayout: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: HomeViewModel = viewModel { HomeViewModel(app.container.repository) }
    val devices by viewModel.devices.collectAsState()

    // 双栏导航器（List/Detail 两栏；Expanded 下同时可见）
    val navigator = rememberListDetailPaneScaffoldNavigator(
        scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()),
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
    )
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            // 左列表：复用 HomeScreen（onDeviceSelected 非空 = ListDetail 模式，单击选中不跳页）
            HomeScreen(
                onDeviceClick = { device -> onOpenRemote(device) },   // 折叠兜底：仍可全屏打开
                onAddClick = onAddClick,
                onDeviceSelected = { device ->
                    selectedId = device.id
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                },
            )
        },
        detailPane = {
            val device = devices.firstOrNull { it.id == selectedId }
            if (device == null) {
                // 未选中设备：空状态引导
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "从左侧选择一台设备开始遥控",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (device.type == DeviceType.AC) {
                // 空调设备：内嵌空调面板（不跳页）
                AcPanelScreen(deviceId = device.id, onBack = {})
            } else {
                // 通用遥控器：640dp 居中容器 + 72dp 方向键 + 隐藏返回键
                RemoteScreen(
                    deviceId = device.id,
                    onBack = {},
                    showBack = false,
                    maxContentWidth = 640.dp,
                    onEditLayout = { onEditLayout(device.id) },
                )
            }
        },
    )
}
