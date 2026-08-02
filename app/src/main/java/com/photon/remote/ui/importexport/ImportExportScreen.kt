package com.photon.remote.ui.importexport

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.viewmodel.ImportExportViewModel
import com.photon.remote.viewmodel.ImportState
import kotlinx.coroutines.launch

/** 导入文件类型（选择文件后按此分发到对应解析入口） */
private enum class ImportKind { FLIPPER, LIRC, JSON }

/**
 * 导入导出页（计划 §5.9 / Todo 34）。
 *
 * 三个入口卡片：导入 Flipper .ir / 导入 LIRC .conf / 导入导出 JSON 备份；
 * 文件选择用 [ActivityResultContracts.OpenDocument]（mime 任意类型），导出用
 * [ActivityResultContracts.CreateDocument]（application/json）。
 * 导入结果以 AlertDialog 展示（成功摘要 + 跳过/失败明细）；JSON 备份导入
 * 需先确认"全量替换"再执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen() {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: ImportExportViewModel =
        viewModel(factory = app.container.importExportViewModelFactory)
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 本次选择文件对应的导入类型（选择回调里按它分发）
    var pendingKind by remember { mutableStateOf(ImportKind.FLIPPER) }

    // 导入文件选择器：mime */*，读取内容字符串后交给 ViewModel
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = viewModel.readText(uri)
            val name = viewModel.displayNameOf(uri)
            if (content == null) {
                Toast.makeText(context, "无法读取所选文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (pendingKind) {
                ImportKind.FLIPPER -> viewModel.importFlipper(content, name)
                ImportKind.LIRC -> viewModel.importLirc(content, name)
                ImportKind.JSON -> viewModel.importJson(content)
            }
        }
    }

    // 导出文件选择器：JSON 备份写入所选位置
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportJson()
            if (json == null) {
                Toast.makeText(context, "导出失败：读取本地数据出错", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ok = try {
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) {
                false
            }
            Toast.makeText(
                context,
                if (ok) "备份已导出" else "导出失败：无法写入所选文件",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("导入导出") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ImportCard(
                    title = "导入 Flipper .ir",
                    subtitle = "Flipper 红外信号文件（raw / parsed 两种类型）",
                    icon = Icons.Rounded.ImportExport,
                    enabled = !busy,
                    onClick = {
                        pendingKind = ImportKind.FLIPPER
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
            }
            item {
                ImportCard(
                    title = "导入 LIRC .conf",
                    subtitle = "LIRC 码库配置文件（KEY_xxx 0xHEX）",
                    icon = Icons.AutoMirrored.Rounded.NoteAdd,
                    enabled = !busy,
                    onClick = {
                        pendingKind = ImportKind.LIRC
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
            }
            item {
                ImportCard(
                    title = "导入 JSON 备份",
                    subtitle = "全量替换当前设备 / 按键 / 宏（覆盖前需确认）",
                    icon = Icons.Rounded.FileDownload,
                    enabled = !busy,
                    onClick = {
                        pendingKind = ImportKind.JSON
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
            }
            item {
                ImportCard(
                    title = "导出 JSON 备份",
                    subtitle = "将全部设备 / 按键 / 宏导出为一个 JSON 文件",
                    icon = Icons.Rounded.FileUpload,
                    enabled = !busy,
                    onClick = { exportPicker.launch("photon-remote-backup.json") },
                )
            }
            item {
                Text(
                    "提示：导入 Flipper / LIRC 会以「其他」类型创建新设备，可在首页重命名；" +
                        "JSON 备份导入为全量替换，现有数据将被覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // 导入结果对话框：失败 / 待确认覆盖 / 完成
    when (val s = state) {
        is ImportState.Failed -> ResultDialog(
            title = s.title,
            summary = s.reason,
            skipped = emptyList(),
            confirmText = "关闭",
            onConfirm = viewModel::dismiss,
        )
        is ImportState.ConfirmOverwrite -> ResultDialog(
            title = s.title,
            summary = s.summary,
            skipped = s.skipped,
            confirmText = "覆盖导入",
            confirmIsDanger = true,
            onConfirm = viewModel::confirmOverwrite,
            onDismiss = viewModel::cancelOverwrite,
        )
        is ImportState.Done -> ResultDialog(
            title = s.title,
            summary = s.summary,
            skipped = s.skipped,
            confirmText = "完成",
            onConfirm = viewModel::dismiss,
        )
        else -> {}
    }
}

/** 导入/导出结果对话框：摘要 + 跳过/失败明细（可滚动），明细为空时隐藏 */
@Composable
private fun ResultDialog(
    title: String,
    summary: String,
    skipped: List<String>,
    confirmText: String,
    confirmIsDanger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = onConfirm,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                if (skipped.isNotEmpty()) {
                    Text(
                        "跳过/失败明细（${skipped.size} 条）：",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    skipped.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (confirmIsDanger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = if (skipped.isNotEmpty() || confirmIsDanger) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else null,
    )
}

/** 入口卡片：图标 + 标题 + 副标题，整卡可点击 */
@Composable
private fun ImportCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
