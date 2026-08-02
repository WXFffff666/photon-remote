package com.photon.remote.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowLeft
import androidx.compose.material.icons.rounded.ArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.ir.core.PressKind

/**
 * 方向键区（计划 §5.5 / Todo 30）：上/下/左/右 圆形方向键 + 中心 OK 大键。
 * 按键从设备按键集按 keyId 查找，缺哪个渲染哪个（码库可能不含某键）。
 */
@Composable
fun DpadKeypad(
    buttons: List<RemoteButton>,
    failedId: Long?,
    onSend: (RemoteButton, PressKind) -> Unit,
) {
    val up = buttons.firstOrNull { it.keyId == "UP" }
    val down = buttons.firstOrNull { it.keyId == "DOWN" }
    val left = buttons.firstOrNull { it.keyId == "LEFT" }
    val right = buttons.firstOrNull { it.keyId == "RIGHT" }
    val ok = buttons.firstOrNull { it.keyId == "OK" }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 第一行：上
        Row {
            Spacer(Modifier.width(72.dp))
            up?.let {
                RemoteKey(
                    icon = Icons.Rounded.ArrowUpward,
                    size = 64.dp,
                    shape = ButtonShape.CIRCLE,
                    sendFailed = failedId == it.id,
                    repeatIntervalMs = 250,
                    onSend = { press -> onSend(it, press) },
                )
            }
            Spacer(Modifier.width(72.dp))
        }
        Spacer(Modifier.size(8.dp))
        // 第二行：左 / OK / 右
        Row(verticalAlignment = Alignment.CenterVertically) {
            left?.let {
                RemoteKey(
                    icon = Icons.Rounded.ArrowLeft,
                    size = 64.dp,
                    shape = ButtonShape.CIRCLE,
                    sendFailed = failedId == it.id,
                    onSend = { press -> onSend(it, press) },
                )
            }
            Spacer(Modifier.size(8.dp))
            ok?.let {
                RemoteKey(
                    label = "OK",
                    size = 72.dp,
                    shape = ButtonShape.CIRCLE,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    sendFailed = failedId == it.id,
                    onSend = { press -> onSend(it, press) },
                )
            }
            Spacer(Modifier.size(8.dp))
            right?.let {
                RemoteKey(
                    icon = Icons.Rounded.ArrowRight,
                    size = 64.dp,
                    shape = ButtonShape.CIRCLE,
                    sendFailed = failedId == it.id,
                    onSend = { press -> onSend(it, press) },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        // 第三行：下
        Row {
            Spacer(Modifier.width(72.dp))
            down?.let {
                RemoteKey(
                    icon = Icons.Rounded.ArrowDownward,
                    size = 64.dp,
                    shape = ButtonShape.CIRCLE,
                    sendFailed = failedId == it.id,
                    onSend = { press -> onSend(it, press) },
                )
            }
            Spacer(Modifier.width(72.dp))
        }
    }
}
