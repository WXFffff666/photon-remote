package com.photon.remote.ui.adddevice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.ui.remote.RemoteKey
import com.photon.remote.viewmodel.AddDeviceViewModel

/**
 * 添加向导步骤 4：测试遥控器（计划 §5.4 步骤 4 / 4b）。
 *
 * 大电源测试键 + 音量+ 测试键（resolveOneShot 发送，换码组无需保存）；
 * 命名输入 + 保存（写入 Device + 默认按键集）。
 * 无匹配降级入口（暴力找码）为文字提示，UI 后续 Todo 35 接入。
 */
@Composable
fun TestRemoteStep(viewModel: AddDeviceViewModel) {
    val deviceName by viewModel.deviceName.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val selectedCode by viewModel.selectedCode.collectAsState()
    val selectedBrand by viewModel.selectedBrand.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val snackbarHostState = SnackbarHostState()

    // 测试按键（按当前选中的码组构建，不入库）
    val powerButton = remember(selectedCode?.codeRef) { viewModel.testButton("POWER") }
    val volUpButton = remember(selectedCode?.codeRef) { viewModel.testButton("VOL_UP") }

    // FIX-5：命名输入框预填品牌中文名（无则类型名）——进入测试页且用户未输入时生效
    val defaultName = selectedBrand?.let { brand ->
        if (brand.name.any { it in '\u4e00'..'\u9fff' }) brand.name else brand.displayName
    } ?: selectedType?.label

    // 测试结果提示
    LaunchedEffect(testResult) {
        testResult?.let { snackbarHostState.showSnackbar(it) }
    }
    // 预填默认设备名（仅当用户尚未输入时）
    LaunchedEffect(deviceName, defaultName) {
        if (deviceName.isBlank()) defaultName?.let(viewModel::setDeviceName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SnackbarHost(snackbarHostState)
        Spacer(Modifier.height(8.dp))

        // 当前码组信息（FIX-6：显示友好名"型号 N"/CSV 型号名）
        Text(
            selectedCode?.displayName ?: "未选择码组",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "按下电源键，看设备是否有反应",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // 大电源测试键（红色醒目；无电源键时提示不可用）
        if (powerButton != null) {
            RemoteKey(
                icon = Icons.Rounded.PowerSettingsNew,
                size = 88.dp,
                shape = ButtonShape.CIRCLE,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onSend = { viewModel.testSend(powerButton) },
            )
        } else {
            Text("该码组没有电源键，无法测试", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))

        // 音量+ 测试键
        if (volUpButton != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteKey(
                    icon = Icons.Rounded.VolumeUp,
                    size = 64.dp,
                    onSend = { viewModel.testSend(volUpButton) },
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "音量键也能用吗？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // 命名 + 保存（FIX-5：输入框预填品牌中文名，留空保存仍用品牌名）
        OutlinedTextField(
            value = deviceName,
            onValueChange = viewModel::setDeviceName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("设备名称（可选）") },
            placeholder = { Text("默认：${defaultName ?: "设备"}") },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::saveDevice,
            enabled = !isSaving && selectedCode != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(if (isSaving) "保存中…" else "保存遥控器")
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = viewModel::previousPage,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("换一个码组") }

        // 4b：无匹配降级入口（暴力找码 UI 后续 Todo 35 接入，先文字提示）
        Text(
            "没有效果？试试「暴力找码」功能（后续版本提供）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
}
