package com.photon.remote.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.KeyboardTab
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.ir.core.PressKind

/**
 * 数字键盘区（计划 §5.5 / Todo 30）：默认折叠为一行"123"按钮，
 * 点击展开 0-9 + 收起（AnimatedVisibility 动画）。按键从设备按键集按
 * keyId（NUM_0..NUM_9）查找，缺哪个渲染哪个。
 */
@Composable
fun NumpadSection(
    buttons: List<RemoteButton>,
    failedId: Long?,
    onSend: (RemoteButton, PressKind) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // 折叠/展开切换按钮
        Surface(
            shape = RoundedCornerShape(KEY_CORNER_RADIUS),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .width(NUMPAD_TOGGLE_WIDTH)
                .height(48.dp)
                .clickable { expanded = !expanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    if (expanded) Icons.Rounded.KeyboardTab else Icons.Rounded.Dialpad,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(if (expanded) "收起" else "123", style = MaterialTheme.typography.titleSmall)
            }
        }

        // 数字键盘展开内容（0-9 + 收起）
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                rowsOf(buttons, listOf("NUM_1", "NUM_2", "NUM_3"), failedId, onSend)
                Spacer(Modifier.size(8.dp))
                rowsOf(buttons, listOf("NUM_4", "NUM_5", "NUM_6"), failedId, onSend)
                Spacer(Modifier.size(8.dp))
                rowsOf(buttons, listOf("NUM_7", "NUM_8", "NUM_9"), failedId, onSend)
                Spacer(Modifier.size(8.dp))
                rowsOf(buttons, listOf("NUM_0"), failedId, onSend, trailingCollapse = { expanded = false })
            }
        }
    }
}

/** 一行数字键（3 列布局；可选尾部"收起"键） */
@Composable
private fun rowsOf(
    buttons: List<RemoteButton>,
    keyIds: List<String>,
    failedId: Long?,
    onSend: (RemoteButton, PressKind) -> Unit,
    trailingCollapse: (() -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keyIds.forEach { keyId ->
            buttons.firstOrNull { it.keyId == keyId }?.let { button ->
                RemoteKey(
                    label = button.label,
                    size = 64.dp,
                    shape = ButtonShape.ROUNDED,
                    sendFailed = failedId == button.id,
                    onSend = { press -> onSend(button, press) },
                )
            }
        }
        trailingCollapse?.let { collapse ->
            Spacer(Modifier.size(8.dp))
            RemoteKey(
                label = "收起",
                size = 64.dp,
                shape = ButtonShape.ROUNDED,
                onSend = { collapse() },
            )
        }
    }
}

/** 折叠态切换按钮宽度（略窄于数字键 3 列总宽） */
private val NUMPAD_TOGGLE_WIDTH = 120.dp
