package com.photon.remote.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.photon.remote.data.local.entity.Device

/**
 * 首页长按菜单配套对话框（计划 §5.3 / Todo 26）：
 * 重命名 / 删除确认 / 排序（输入目标位置）。拆分自 HomeScreen 以控制单文件行数。
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

/** 排序对话框：输入目标位置（0..maxIndex），确认后移动设备 */
@Composable
fun SortDialog(device: Device, maxIndex: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember(device.id) { mutableStateOf("") }
    val index = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序") },
        text = {
            Column {
                Text("把「${device.name}」移动到列表位置（0 到 $maxIndex）：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = index != null && index in 0..maxIndex, onClick = { index?.let(onConfirm) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
