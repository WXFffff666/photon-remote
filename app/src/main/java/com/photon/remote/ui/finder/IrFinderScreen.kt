package com.photon.remote.ui.finder

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.viewmodel.FinderViewModel
import com.photon.remote.viewmodel.FinderUiState

/**
 * 暴力找码页（计划 §4.4 / §5.9 / Todo 35）。
 *
 * - 协议下拉（排除 RAW）+ hex 前缀输入（支持 AA / 0xAABB / AA:BB）+ 候选数实时显示；
 * - 开始/停止 + 进度条（已测/总数）+ 当前测试码；
 * - 停止后「保存此码为按键」：选择目标设备 → 添加 SendProtocol(protocol, hex) 按键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrFinderScreen() {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: FinderViewModel = viewModel(factory = app.container.finderViewModelFactory)
    val state by viewModel.state.collectAsState()
    val devices by viewModel.devices.collectAsState(initial = emptyList())
    val context = LocalContext.current

    // 保存设备选择对话框开关（手动保存 或 命中自动弹出 共用）
    var showSaveDialog by remember { mutableStateOf(false) }

    // 保存结果 Toast（一次性）
    LaunchedEffect(state.saveMessage) {
        state.saveMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeSaveMessage()
        }
    }

    // 命中（run 提前返回）时自动弹出保存对话框
    LaunchedEffect(state.hitHex) {
        if (state.hitHex != null) showSaveDialog = true
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("暴力找码") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 协议选择
            ProtocolDropdown(
                options = viewModel.protocols,
                selected = state.protocol,
                enabled = !state.running,
                onSelect = viewModel::selectProtocol,
            )

            Spacer(Modifier.height(12.dp))

            // hex 前缀输入
            OutlinedTextField(
                value = state.prefix,
                onValueChange = viewModel::setPrefix,
                label = { Text("hex 前缀（可选）") },
                placeholder = { Text("AA / 0xAABB / AA:BB") },
                supportingText = { Text("固定前缀、其余位从 0 递增；留空 = 全量迭代") },
                isError = state.prefixError != null,
                enabled = !state.running,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 候选数 / 错误提示
            Spacer(Modifier.height(8.dp))
            val prefixError = state.prefixError
            when {
                prefixError != null -> Text(
                    prefixError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                state.candidateCount != null -> Text(
                    "候选数：${state.candidateCount}（间隔 800ms 逐码发送）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // 开始 / 停止
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.running) {
                    OutlinedButton(
                        onClick = viewModel::stop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                } else {
                    Button(
                        onClick = viewModel::start,
                        enabled = state.candidateCount != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始找码")
                    }
                }
            }

            // 进度（运行中显示）
            if (state.running) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.tested.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "已测 ${state.tested} / 共 ${state.total}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.currentHex?.let { hex ->
                    Text(
                        "当前发送：$hex（设备响应后点「停止」再保存）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 保存当前码为按键（非运行中且已有测试过的码）
            if (!state.running && (state.currentHex != null || state.hitHex != null)) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存此码为按键：${state.hitHex ?: state.currentHex}")
                }
            }
        }
    }

    // 保存对话框：选择目标设备
    if (showSaveDialog) {
        SaveToDeviceDialog(
            devices = devices,
            hex = state.hitHex ?: state.currentHex,
            onSelect = { device ->
                showSaveDialog = false
                viewModel.saveCurrentAsButton(device.id)
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

/** 协议下拉选择框（ExposedDropdownMenuBox，M3 1.3.x 的 MenuAnchorType 写法） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolDropdown(
    options: List<ProtocolType>,
    selected: ProtocolType,
    enabled: Boolean,
    onSelect: (ProtocolType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = "${selected.name}（${bitWidthOf(selected)} 位）",
            onValueChange = {},
            readOnly = true,
            label = { Text("协议") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.name}（${bitWidthOf(option)} 位）") },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 协议位宽（与 FinderViewModel.bitWidths 同表：编码器 hex 位宽 × 4） */
private fun bitWidthOf(protocol: ProtocolType): Int = when (protocol) {
    ProtocolType.NEC, ProtocolType.NECX1, ProtocolType.NECX2 -> 32
    ProtocolType.RC5, ProtocolType.RC6 -> 16
    ProtocolType.SONY12 -> 12
    ProtocolType.SONY15 -> 15
    ProtocolType.SONY20 -> 20
    ProtocolType.SAMSUNG32 -> 32
    ProtocolType.SHARP -> 13
    ProtocolType.JVC -> 16
    ProtocolType.KASEIKYO -> 48
    ProtocolType.PIONEER -> 32
    ProtocolType.RAW -> 32
}

/** 保存目标设备选择对话框（设备列表，点击即保存；无设备时提示先添加） */
@Composable
private fun SaveToDeviceDialog(
    devices: List<Device>,
    hex: String?,
    onSelect: (Device) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存此码为按键") },
        text = {
            if (devices.isEmpty()) {
                Text("暂无设备，请先在首页添加一个遥控器")
            } else {
                Column {
                    Text(
                        "码值：${hex ?: "-"}。选择要添加按键的设备：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(devices, key = { it.id }) { device ->
                            TextButton(
                                onClick = { onSelect(device) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    device.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
