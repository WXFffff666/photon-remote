package com.photon.remote.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.Operator
import com.photon.remote.ir.core.PressKind
import com.photon.remote.viewmodel.RemoteViewModel

/**
 * 遥控器主页面（计划 §5.5 / Todo 30）。
 *
 * 默认布局模板（layoutId 驱动，layoutJson 非空时 Todo 32 LayoutEditor 接入后
 * 走自定义网格渲染，本阶段先支持默认模板）：
 *  - 顶部：设备名 + 收藏星 + 编辑布局（TODO 32）；
 *  - 电源键（大、醒目）+ 静音；
 *  - D-pad 方向键区（上/下/左/右 + OK）+ 返回/菜单/输入源；
 *  - 音量列 VOL+/VOL- + 频道列 CH+/CH-（长按连发）；
 *  - 数字键盘（默认折叠"123"，点击展开）；
 *  - STB 运营商（移动/联通/电信）顶部服务键（直播/回看/点播/应用，码库若含）。
 * 发送：按键 resolve() → IrDispatcher（会话 open/close 由 ViewModel 承担，规则 a）。
 */
@Composable
fun RemoteScreen(
    deviceId: Long,
    onBack: () -> Unit,
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

    // 自定义布局尚未实现（Todo 32 LayoutEditor 接入后启用 layoutJson 网格渲染）
    if (!d.layoutJson.isNullOrBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "自定义布局将在「编辑布局」功能中启用（后续版本提供）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部：返回 + 设备名 + 收藏 + 编辑布局（TODO 32）
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    d.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(d.brand, d.model).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // TODO(32)：编辑布局按钮 → LayoutEditor（自定义布局网格编辑）
            IconButton(onClick = { /* TODO(32) 接入 LayoutEditor */ }) {
                Icon(Icons.Rounded.Tune, contentDescription = "编辑布局")
            }
            IconButton(onClick = viewModel::toggleFavorite) {
                Icon(
                    if (d.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "收藏",
                    tint = if (d.isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // STB 运营商服务键（直播/回看/点播/应用，仅当设备按键含对应键）
        if (d.type == DeviceType.STB && d.operator in STB_SERVICE_OPERATORS) {
            ServiceKeyRow(buttons, failedId, viewModel)
            Spacer(Modifier.height(16.dp))
        }

        // 电源键（大、醒目）+ 静音
        Row(verticalAlignment = Alignment.CenterVertically) {
            buttons.firstOrNull { it.keyId == "POWER" }?.let { power ->
                RemoteKey(
                    icon = Icons.Rounded.PowerSettingsNew,
                    size = 72.dp,
                    shape = ButtonShape.CIRCLE,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    sendFailed = failedId == power.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(power),
                    onSend = { press -> viewModel.sendButton(power, press) },
                )
            }
            Spacer(Modifier.width(20.dp))
            buttons.firstOrNull { it.keyId == "MUTE" }?.let { mute ->
                RemoteKey(
                    icon = Icons.Rounded.VolumeOff,
                    size = 56.dp,
                    shape = ButtonShape.CIRCLE,
                    sendFailed = failedId == mute.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(mute),
                    onSend = { press -> viewModel.sendButton(mute, press) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // D-pad 方向键区
        DpadKeypad(buttons, failedId, viewModel::sendButton)
        Spacer(Modifier.height(20.dp))

        // 返回 / 菜单 / 输入源
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buttons.firstOrNull { it.keyId == "BACK" }?.let { back ->
                RemoteKey(
                    icon = Icons.Rounded.KeyboardReturn,
                    size = 64.dp,
                    sendFailed = failedId == back.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(back),
                    onSend = { press -> viewModel.sendButton(back, press) },
                )
            }
            buttons.firstOrNull { it.keyId == "MENU" }?.let { menu ->
                RemoteKey(
                    icon = Icons.Rounded.Menu,
                    size = 64.dp,
                    sendFailed = failedId == menu.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(menu),
                    onSend = { press -> viewModel.sendButton(menu, press) },
                )
            }
            buttons.firstOrNull { it.keyId == "INPUT" }?.let { input ->
                RemoteKey(
                    icon = Icons.Rounded.SettingsInputHdmi,
                    size = 64.dp,
                    sendFailed = failedId == input.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(input),
                    onSend = { press -> viewModel.sendButton(input, press) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // 音量列 + 频道列（长按连发）
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.firstOrNull { it.keyId == "VOL_UP" }?.let { vUp ->
                    RemoteKey(
                        icon = Icons.Rounded.VolumeUp,
                        size = 64.dp,
                        sendFailed = failedId == vUp.id,
                        repeatIntervalMs = viewModel.repeatIntervalFor(vUp),
                        onSend = { press -> viewModel.sendButton(vUp, press) },
                    )
                }
                buttons.firstOrNull { it.keyId == "VOL_DOWN" }?.let { vDown ->
                    RemoteKey(
                        icon = Icons.Rounded.VolumeDown,
                        size = 64.dp,
                        sendFailed = failedId == vDown.id,
                        repeatIntervalMs = viewModel.repeatIntervalFor(vDown),
                        onSend = { press -> viewModel.sendButton(vDown, press) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.firstOrNull { it.keyId == "CH_UP" }?.let { chUp ->
                    RemoteKey(
                        label = "频道+",
                        size = 64.dp,
                        sendFailed = failedId == chUp.id,
                        repeatIntervalMs = viewModel.repeatIntervalFor(chUp),
                        onSend = { press -> viewModel.sendButton(chUp, press) },
                    )
                }
                buttons.firstOrNull { it.keyId == "CH_DOWN" }?.let { chDown ->
                    RemoteKey(
                        label = "频道-",
                        size = 64.dp,
                        sendFailed = failedId == chDown.id,
                        repeatIntervalMs = viewModel.repeatIntervalFor(chDown),
                        onSend = { press -> viewModel.sendButton(chDown, press) },
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 数字键盘（折叠/展开）
        NumpadSection(buttons, failedId, viewModel::sendButton)
        Spacer(Modifier.height(24.dp))
    }
}

/** STB 服务键行：直播/回看/点播/应用（keyId SERVICE_LIVE 等，码库若含才渲染） */
@Composable
private fun ServiceKeyRow(
    buttons: List<RemoteButton>,
    failedId: Long?,
    viewModel: RemoteViewModel,
) {
    val serviceKeys = listOf(
        "SERVICE_LIVE" to "直播",
        "SERVICE_REPLAY" to "回看",
        "SERVICE_VOD" to "点播",
        "SERVICE_APP" to "应用",
    )
    val present = serviceKeys.mapNotNull { (keyId, label) ->
        buttons.firstOrNull { it.keyId == keyId }?.let { it to label }
    }
    if (present.isEmpty()) return   // 码库不含服务键：整行隐藏
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            present.forEach { (button, label) ->
                RemoteKey(
                    label = label,
                    size = 56.dp,
                    sendFailed = failedId == button.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(button),
                    onSend = { press -> viewModel.sendButton(button, press) },
                )
            }
        }
    }
}

/** 显示服务键的运营商集合（移动/联通/电信，计划 §5.5） */
private val STB_SERVICE_OPERATORS = setOf(Operator.CMCC, Operator.CUCC, Operator.CTCC)
