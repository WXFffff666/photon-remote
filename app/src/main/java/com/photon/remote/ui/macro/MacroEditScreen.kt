package com.photon.remote.ui.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.MacroStep
import com.photon.remote.viewmodel.MacroViewModel
import kotlinx.coroutines.flow.first

/**
 * 宏编辑页（计划 §5.9 / Todo 33），新建/编辑复用。
 *
 * - 名称输入 + 步骤列表（每条：设备选择 → 按键选择 → 发送后间隔调整 → 删除）；
 * - "添加步骤"默认选中第一个设备；设备/按键均为 ExposedDropdownMenu 下拉选择；
 * - 保存：新增或写回既有宏（existingId > 0 为编辑），写库成功后返回列表页；
 * - 全部步骤有效（设备 + 按键均已选择）且名称非空才可保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditScreen(
    macroId: Long?,      // null / <=0 = 新建
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: MacroViewModel =
        viewModel(key = "macro", factory = app.container.macroViewModelFactory)
    val devices by viewModel.devices.collectAsState(initial = emptyList())

    var name by rememberSaveable { mutableStateOf("") }
    var steps by remember { mutableStateOf(listOf<StepDraft>()) }
    // 设备 → 按键缓存（步骤设备变化时懒加载）
    val buttonsCache = remember { mutableStateMapOf<Long, List<RemoteButton>>() }

    // 编辑模式：载入既有宏的名称与步骤
    LaunchedEffect(macroId) {
        if (macroId == null || macroId <= 0) return@LaunchedEffect
        val m = viewModel.macros.first().firstOrNull { it.id == macroId } ?: return@LaunchedEffect
        name = m.name
        steps = MacroViewModel.parseSteps(m.stepsJson)
            .map { StepDraft(it.deviceId, it.buttonId, it.delayMs) }
    }

    // 步骤引用的设备按键懒加载（新增/切换设备后自动补齐）
    LaunchedEffect(steps) {
        steps.map { it.deviceId }.distinct().forEach { deviceId ->
            if (deviceId > 0 && !buttonsCache.containsKey(deviceId)) {
                buttonsCache[deviceId] = viewModel.buttonsOf(deviceId)
            }
        }
    }

    val canSave = name.isNotBlank() && steps.all { it.deviceId > 0 && it.buttonId > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (macroId == null || macroId <= 0) "新建宏" else "编辑宏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.saveMacro(
                                name = name,
                                steps = steps.map { MacroStep(it.deviceId, it.buttonId, it.delayMs) },
                                existingId = macroId,
                                onSaved = onBack,
                            )
                        },
                        enabled = canSave,
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("宏名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (devices.isEmpty()) {
                Text(
                    "暂无设备，请先到首页添加设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 步骤列表
            steps.forEachIndexed { index, step ->
                StepCard(
                    index = index,
                    step = step,
                    devices = devices,
                    buttons = buttonsCache[step.deviceId] ?: emptyList(),
                    onDeviceSelect = { deviceId ->
                        steps = steps.mapIndexed { i, s ->
                            if (i == index) s.copy(deviceId = deviceId, buttonId = 0L) else s
                        }
                    },
                    onButtonSelect = { buttonId ->
                        steps = steps.mapIndexed { i, s ->
                            if (i == index) s.copy(buttonId = buttonId) else s
                        }
                    },
                    onDelayChange = { ms ->
                        steps = steps.mapIndexed { i, s ->
                            if (i == index) s.copy(delayMs = ms) else s
                        }
                    },
                    onRemove = {
                        steps = steps.toMutableList().also { it.removeAt(index) }
                    },
                )
            }

            // 添加步骤（默认选中第一个设备，按键待选）
            OutlinedButton(
                onClick = {
                    val deviceId = devices.firstOrNull()?.id ?: 0L
                    steps = steps + StepDraft(deviceId = deviceId, buttonId = 0L, delayMs = 300L)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加步骤")
            }
        }
    }
}

/** 单个步骤编辑卡片：设备下拉 → 按键下拉 → 发送后间隔滑杆 → 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepCard(
    index: Int,
    step: StepDraft,
    devices: List<Device>,
    buttons: List<RemoteButton>,
    onDeviceSelect: (Long) -> Unit,
    onButtonSelect: (Long) -> Unit,
    onDelayChange: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${index + 1} 步",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除此步骤",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 设备选择（下拉）
            DropdownField(
                label = "设备",
                selectedLabel = devices.firstOrNull { it.id == step.deviceId }
                    ?.let { "${it.name}（${it.type.label}）" } ?: "选择设备",
                enabled = devices.isNotEmpty(),
                emptyText = "暂无设备，请先到首页添加",
                items = devices.map { it.id to "${it.name}（${it.type.label}）" },
                onSelect = onDeviceSelect,
                modifier = Modifier.fillMaxWidth(),
            )
            // 按键选择（下拉，设备未选时禁用）
            DropdownField(
                label = "按键",
                selectedLabel = buttons.firstOrNull { it.id == step.buttonId }?.label
                    ?: "选择按键",
                enabled = step.deviceId > 0,
                emptyText = if (step.deviceId > 0) "该设备暂无按键" else "请先选择设备",
                items = buttons.map { it.id to it.label },
                onSelect = onButtonSelect,
                modifier = Modifier.fillMaxWidth(),
            )
            // 发送后间隔调整（100..3000ms，步进 100ms）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发送后间隔", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${step.delayMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Slider(
                value = step.delayMs.coerceIn(100, 3000).toFloat(),
                onValueChange = { value ->
                    onDelayChange(
                        ((value.toInt() + 50) / 100 * 100).toLong().coerceIn(100, 3000)
                    )
                },
                valueRange = 100f..3000f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 通用下拉选择框（设备 / 按键共用）：items 为 (id, 显示文本) 列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    selectedLabel: String,
    enabled: Boolean,
    emptyText: String,
    items: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            label = { Text(label) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (items.isEmpty()) {
                DropdownMenuItem(text = { Text(emptyText) }, onClick = { expanded = false })
            } else {
                items.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 编辑中的步骤草稿（对应 MacroStep，未落库） */
private data class StepDraft(
    val deviceId: Long,
    val buttonId: Long,
    val delayMs: Long,
)
