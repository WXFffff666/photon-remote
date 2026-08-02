package com.photon.remote.ui.macro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.viewmodel.MacroSummary
import com.photon.remote.viewmodel.MacroViewModel
import kotlinx.coroutines.delay

/**
 * 宏列表页（计划 §5.9 / Todo 33）。
 *
 * - 宏卡片：名称 + 步骤摘要（每步"设备名 → 按键 label"，步骤间" → "连接）；
 * - 播放（执行宏）/ 停止（执行中）/ 删除 三个操作，点击卡片进入编辑；
 * - 执行中的宏卡片高亮（主色描边 + primaryContainer 底色）并显示当前步骤；
 * - 执行完成/失败结果横幅 2.5 秒自动消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroListScreen(
    onCreateClick: () -> Unit,
    onEditClick: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: MacroViewModel =
        viewModel(key = "macro", factory = app.container.macroViewModelFactory)
    val summaries by viewModel.summaries.collectAsState()
    val execState by viewModel.execState.collectAsState()
    val executingId by viewModel.executingMacroId.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("宏") },
                actions = {
                    IconButton(onClick = onCreateClick) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建宏")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 执行结果横幅（完成/失败，2.5 秒自动消失）
            when (execState) {
                is MacroExecState.Done ->
                    StatusBanner(execState.hint, success = true, onDismiss = viewModel::clearExecState)
                is MacroExecState.Failed ->
                    StatusBanner(execState.hint, success = false, onDismiss = viewModel::clearExecState)
                else -> {}
            }
            if (summaries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无宏，点击右上角 + 新建",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(summaries, key = { it.macro.id }) { summary ->
                        MacroCard(
                            summary = summary,
                            isExecuting = executingId == summary.macro.id,
                            execState = execState,
                            onEdit = { onEditClick(summary.macro.id) },
                            onPlay = { viewModel.executeMacro(summary.macro) },
                            onStop = viewModel::stop,
                            onDelete = { viewModel.deleteMacro(summary.macro) },
                        )
                    }
                }
            }
        }
    }
}

/** 宏卡片：名称 + 步骤摘要 + 播放/停止/删除操作；执行中高亮 */
@Composable
private fun MacroCard(
    summary: MacroSummary,
    isExecuting: Boolean,
    execState: MacroExecState,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        border = if (isExecuting) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isExecuting) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.macro.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isExecuting) {
                    Text(
                        execState.hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                summary.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (isExecuting) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Rounded.Stop, contentDescription = "停止执行")
                    }
                } else {
                    IconButton(onClick = onPlay, enabled = execState !is MacroExecState.Running) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "执行宏")
                    }
                }
                IconButton(onClick = onDelete, enabled = !isExecuting) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除宏",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 执行结果横幅（完成/失败），2.5 秒后自动清除 */
@Composable
private fun StatusBanner(text: String, success: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500)
        onDismiss()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (success) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
