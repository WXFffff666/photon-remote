package com.photon.remote.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.ui.theme.AccentSeeds
import com.photon.remote.viewmodel.SettingsViewModel

/**
 * 设置页（计划 §5.9 / Todo 36）。
 *
 * - 主题：跟随系统 / 浅色 / 深色 / 纯黑（RadioButton 组，保存后**即时生效**——
 *   MainActivity 收集 DataStore 流驱动 PhotonTheme.darkTheme / pureBlackAmoled）；
 * - 强调色：Material 3 基准 8 色色板（点选保存 ARGB，Android 12+ 动态取色时由
 *   PhotonTheme 忽略种子、跟随壁纸；低版本设备即时生效）；
 * - 震动开关：持久化 haptic（按键震动接线留待后续 Todo，当前 RemoteKey 使用
 *   Compose LocalHapticFeedback 无条件震动）；
 * - 发射路径（TransmitterManager 路由联动）+ 音频模式（AudioIrTransmitter 1/2LED）；
 * - 关于：版本号 + irdb 出处声明（含 3 份免费副本条款）+ MIT + IREXT/Flipper IRDB 致谢 + 开源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: SettingsViewModel = viewModel(factory = app.container.settingsViewModelFactory)

    val themeMode by viewModel.themeMode.collectAsState(initial = "system")
    val accentArgb by viewModel.accentColor.collectAsState(initial = 0)
    val haptic by viewModel.haptic.collectAsState(initial = true)
    val transmitterPath by viewModel.transmitterPath.collectAsState(initial = "auto")
    val audioMode by viewModel.audioMode.collectAsState(initial = "1LED")

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 主题 ----------
            item {
                SettingsCard(title = "主题") {
                    RadioGroup(
                        options = listOf(
                            "system" to "跟随系统",
                            "light" to "浅色",
                            "dark" to "深色",
                            "black" to "纯黑（AMOLED）",
                        ),
                        selected = themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                    Text(
                        "主题与强调色保存后即时生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---------- 强调色 ----------
            item {
                SettingsCard(title = "强调色") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccentSeeds.forEach { seed ->
                            AccentSwatch(
                                color = seed,
                                selected = accentArgb == seed.toArgb(),
                                onClick = { viewModel.setAccentColor(seed.toArgb()) },
                            )
                        }
                    }
                    Text(
                        "Android 12+ 默认跟随壁纸动态取色，此处色板在低版本设备生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---------- 震动 ----------
            item {
                SettingsCard(title = "震动反馈") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("按键按压震动", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = haptic,
                            onCheckedChange = viewModel::setHaptic,
                        )
                    }
                }
            }

            // ---------- 发射路径 ----------
            item {
                SettingsCard(title = "发射路径") {
                    RadioGroup(
                        options = listOf(
                            "auto" to "自动（USB → 内置 → 音频）",
                            "builtin" to "内置红外",
                            "usb" to "USB 外设",
                            "audio" to "音频转红外",
                        ),
                        selected = transmitterPath,
                        onSelect = viewModel::setTransmitterPath,
                    )
                }
            }

            // ---------- 音频模式 ----------
            item {
                SettingsCard(title = "音频转红外模式") {
                    RadioGroup(
                        options = listOf(
                            "1LED" to "1LED（单声道）",
                            "2LED" to "2LED（立体声反相）",
                        ),
                        selected = audioMode,
                        onSelect = viewModel::setAudioMode,
                    )
                    Text(
                        "需插入音频转红外 LED 适配器，并将音量开到最大。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---------- 码库更新（Todo 50：全量/增量 + SHA-256 防篡改 + 安全回滚） ----------
            item {
                SettingsCard(title = "码库更新") {
                    UpdateSection()
                }
            }

            // ---------- 关于 ----------
            item {
                SettingsCard(title = "关于") {
                    AboutSection(versionName = viewModel.versionName)
                }
            }
        }
    }
}

// =====================================================================
// 组件拆分（保持页面 ≤300 行）
// =====================================================================

/** 设置分组卡片：标题 + 内容 */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(Modifier.padding(vertical = 8.dp)) { HorizontalDivider() }
            content()
        }
    }
}

/** RadioButton 选项组（整行可点击） */
@Composable
private fun RadioGroup(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** 强调色色块（圆形，选中描边 + 对勾） */
@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(
                BorderStroke(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 关于：版本 + irdb 出处声明 + MIT + IREXT/Flipper 致谢 + 开源地址 */
@Composable
private fun AboutSection(versionName: String) {
    AboutRow("版本", "v$versionName")
    AboutRow("许可证", "MIT License（Copyright © 2026 Photon Remote contributors）")
    AboutRow("开源项目", "photon-remote")
    Text(
        "本应用不含任何 GPL / AGPL 代码或数据（mi_remote_database 未使用）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    // irdb 出处声明（必须保留，计划 §5.9 / D4）
    Text(
        "码库声明",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Text(
        "Contains/accesses irdb by Simon Peter and contributors, used under permission. " +
            "For licensing details, see: https://github.com/probonopd/irdb",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "按 irdb 分发约定，如作者或其授权代表提出请求，本项目将按条款提供 3（三）份免费副本。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    // 致谢
    Text(
        "致谢",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    AboutRow("IREXT", "码库解码基于 IREXT（MIT）：https://github.com/irext/irext-core")
    AboutRow("Flipper IRDB", "导入兼容 Flipper IRDB（MIT）：https://github.com/Lucaslhm/Flipper-IRDB")
}

/** 关于页键值行（值超长省略号） */
@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
