package com.photon.remote.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.Operator
import com.photon.remote.viewmodel.RemoteViewModel

/**
 * 默认布局主体（计划 §5.5）：STB 服务键行 + 电源/静音 + D-pad +
 * 返回/菜单/输入源 + 音量/频道列 + 数字键盘。拆分自 RemoteScreen 控制单文件行数。
 */
@Composable
fun DefaultRemoteBody(
    device: Device,
    buttons: List<RemoteButton>,
    failedId: Long?,
    viewModel: RemoteViewModel,
    dpadKeySize: Dp,
) {
    // STB 运营商服务键（直播/回看/点播/应用，仅当设备按键含对应键）
    if (device.type == DeviceType.STB && device.operator in STB_SERVICE_OPERATORS) {
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

    // D-pad 方向键区（平板 Expanded 传 72dp，计划 §5.10）
    DpadKeypad(buttons, failedId, viewModel::sendButton, keySize = dpadKeySize)
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
}

/**
 * 自定义布局网格渲染（计划 §5.6 / Todo 32 装配）：
 * 按 layoutJson 的 col/row/colSpan/rowSpan/isRound 在 8×6 网格上绝对定位，
 * 按键动作仍映射到数据库按键（keyId 关联）发送。
 */
@Composable
fun CustomLayoutGrid(
    buttons: List<RemoteButton>,
    keys: List<LayoutKey>,
    failedId: Long?,
    viewModel: RemoteViewModel,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CUSTOM_GRID_COLS.toFloat() / CUSTOM_GRID_ROWS),
    ) {
        val cellW = maxWidth / CUSTOM_GRID_COLS
        val cellH = maxHeight / CUSTOM_GRID_ROWS
        keys.forEach { k ->
            buttons.firstOrNull { it.keyId == k.keyId }?.let { button ->
                RemoteKey(
                    // offset 放最外层：负责网格定位；尺寸由 width/height 指定（span）
                    modifier = Modifier.offset(x = cellW * k.col, y = cellH * k.row),
                    label = k.label,
                    width = cellW * k.colSpan - 2.dp,
                    height = cellH * k.rowSpan - 2.dp,
                    shape = if (k.isRound) ButtonShape.CIRCLE else ButtonShape.ROUNDED,
                    sendFailed = failedId == button.id,
                    repeatIntervalMs = viewModel.repeatIntervalFor(button),
                    onSend = { press -> viewModel.sendButton(button, press) },
                )
            }
        }
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
