package com.photon.remote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.photon.remote.data.local.entity.Device

/**
 * 首页长按菜单配套对话框（计划 §5.3 / Todo 26 + Todo 38 排序增强）：
 * 重命名 / 删除确认 / 移动排序（移到顶部、上移、下移、移到底部）。
 * 拆分自 HomeScreen 以控制单文件行数。
 */

/** 重命名对话框 */
@Composable
fun RenameDialog(device: Device, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("设备名称") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除确认对话框 */
@Composable
fun DeleteConfirmDialog(device: Device, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除设备") },
        text = { Text("确定要删除「${device.name}」吗？该设备的全部按键与宏步骤将被一并移除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 移动排序对话框（计划 §5.3 / Todo 38）：移到顶部 / 上移 / 下移 / 移到底部。
 * 对应 HomeViewModel.moveToTop/moveUp/moveDown/moveToBottom → Repository.moveDevice
 * （以底层 sortOrder 顺序定位，排序持久化，重启保留）。
 */
@Composable
fun MoveSortDialog(
    device: Device,
    onDismiss: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToBottom: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动排序") },
        text = {
            Column {
                Text("调整「${device.name}」在列表中的位置：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveSortButton("移到顶部", Icons.Rounded.VerticalAlignTop, onMoveToTop)
                    MoveSortButton("上移", Icons.Rounded.ArrowUpward, onMoveUp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveSortButton("下移", Icons.Rounded.ArrowDownward, onMoveDown)
                    MoveSortButton("移到底部", Icons.Rounded.VerticalAlignBottom, onMoveToBottom)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 移动排序操作按钮（图标 + 文字） */
@Composable
private fun MoveSortButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
