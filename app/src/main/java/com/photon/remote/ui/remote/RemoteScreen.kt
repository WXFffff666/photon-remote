package com.photon.remote.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.viewmodel.RemoteViewModel

/**
 * 遥控器主页面（计划 §5.5 / Todo 30 + Todo 32 装配收尾）。
 *
 *  - Device.layoutJson 非空 → 按自定义布局网格渲染（LayoutEditor 产出，
 *    col/row/colSpan/rowSpan/圆形，见 CustomLayoutGrid）；
 *  - layoutJson 为空 → 默认布局模板（电源/D-pad/音量频道列/数字键盘）。
 *
 * 顶部：返回（平板详情模式可隐藏）+ 设备名 + 编辑布局（进 LayoutEditor）+
 * 收藏星标。遥控器容器按 [maxContentWidth] 居中限宽
 * （Compact 480dp / 平板 Expanded 640dp，计划 §5.10）。
 */
@Composable
fun RemoteScreen(
    deviceId: Long,
    onBack: () -> Unit,
    showBack: Boolean = true,
    maxContentWidth: Dp = 480.dp,
    onEditLayout: (() -> Unit)? = null,
) {
    // ViewModel：依赖从手动 DI 容器（PhotonApplication.container）获取
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: RemoteViewModel = viewModel(key = "remote-$deviceId") {
        RemoteViewModel(
            deviceId = deviceId,
            repository = app.container.repository,
            codeResolver = app.container.codeResolver,
            dispatcher = app.container.irDispatcher,
            binaryStore = app.container.binaryStore,
            transmitter = app.container.transmitterManager,
            encoders = app.container.encoders,
        )
    }
    val device by viewModel.device.collectAsState()
    val buttons by viewModel.buttons.collectAsState()
    val failedId by viewModel.failedButtonId.collectAsState()

    val d = device
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 自定义布局（layoutJson 非空时按网格渲染；解析失败回退默认布局）
    val customKeys = remember(d.layoutJson) { d.layoutJson?.let { decodeLayout(it) } }
    // 平板宽容器（640dp）方向键加大到 72dp（计划 §5.10）
    val dpadKeySize = if (maxContentWidth >= 600.dp) 72.dp else 64.dp

    // 遥控器容器：居中限宽（手机 480dp / 平板详情 640dp，不随屏宽拉伸）
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部：返回 + 设备名 + 编辑布局 + 收藏
            RemoteTopBar(
                device = d,
                showBack = showBack,
                onBack = onBack,
                onEditLayout = onEditLayout,
                onToggleFavorite = viewModel::toggleFavorite,
            )
            Spacer(Modifier.height(16.dp))

            if (customKeys != null) {
                // 自定义布局：网格渲染
                CustomLayoutGrid(
                    buttons = buttons,
                    keys = customKeys,
                    failedId = failedId,
                    viewModel = viewModel,
                )
            } else {
                // 默认布局模板
                DefaultRemoteBody(
                    device = d,
                    buttons = buttons,
                    failedId = failedId,
                    viewModel = viewModel,
                    dpadKeySize = dpadKeySize,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 顶部栏：返回（可选）+ 设备名/品牌 + 编辑布局 + 收藏星标 */
@Composable
private fun RemoteTopBar(
    device: Device,
    showBack: Boolean,
    onBack: () -> Unit,
    onEditLayout: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(device.brand, device.model).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onEditLayout != null) {
            IconButton(onClick = onEditLayout) {
                Icon(Icons.Rounded.Tune, contentDescription = "编辑布局")
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (device.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "收藏",
                tint = if (device.isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
