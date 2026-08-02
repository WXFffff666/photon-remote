package com.photon.remote.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.codebase.update.UpdateMode
import com.photon.remote.viewmodel.UpdateViewModel

/**
 * 设置页「码库更新」区（计划 Todo 50）。
 *
 * - 当前版本：内置版本（assets，不可变）+ 本地版本（filesDir 缓存；未更新时同内置）；
 * - 「检查更新」→ 进度提示 → 发现新版本弹确认框（更新方式由 CodebaseUpdater
 *   自动选择：全量/增量）→ 确认后下载 + SHA-256 校验 + 合并 + 应用；
 * - 成功「更新完成」，失败展示中文原因（旧版本已保留，不影响离线使用）；
 * - 数据源说明：优先 IREXT 官方源，GitHub Release 为镜像（国内网络提示）。
 */
@Composable
fun UpdateSection() {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: UpdateViewModel = viewModel(factory = app.container.updateViewModelFactory)
    val state by viewModel.state.collectAsState()

    // ---------- 当前版本 ----------
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "当前版本",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            buildString {
                append("内置 v${state.builtinVersion}")
                if (state.localVersion.isNotEmpty() && state.localVersion != state.builtinVersion) {
                    append(" · 本地已更新 v${state.localVersion}")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // ---------- 检查更新按钮 / 检查进度 ----------
    if (state.checking) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                "正在检查更新…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        Button(
            onClick = viewModel::checkForUpdate,
            enabled = !state.applying,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(if (state.applying) "正在更新…" else "检查更新")
        }
    }

    // ---------- 下载/应用进度 ----------
    if (state.applying) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                if (state.progress < 1f) "正在下载并校验数据包（SHA-256）…" else "正在应用更新…",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    // ---------- 结果消息 ----------
    state.message?.let { msg ->
        Text(
            msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.messageIsError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // ---------- 数据源说明 ----------
    Text(
        "数据源：优先 IREXT 官方源（github.com/irext），本 App 通过 GitHub Release 镜像" +
            "（github.com/WXFffff666/photon-remote）托管数据包；国内网络访问 GitHub 可能较慢，" +
            "更新失败不影响内置码库离线使用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    // ---------- 发现新版本确认框 ----------
    state.available?.let { avail ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAvailable,
            title = { Text("发现新版本 v${avail.version}") },
            text = {
                Text(
                    buildString {
                        append(
                            "更新方式：${if (avail.mode == UpdateMode.FULL) "全量更新（替换整个码库）"
                            else "增量更新（仅下载变更部分）"}。",
                        )
                        if (avail.changelog.isNotBlank()) append("\n\n更新说明：${avail.changelog}")
                        append("\n\n更新过程自动校验文件完整性，失败会回滚并保留旧版本。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUpdate) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAvailable) { Text("暂不") }
            },
        )
    }
}
