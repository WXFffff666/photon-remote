package com.photon.remote.ui.ac

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.model.ACStatusData
import com.photon.remote.ui.remote.RemoteKey
import com.photon.remote.viewmodel.AcPanelViewModel

/**
 * 空调控制面板（计划 §5.7 / Todo 31）。
 *
 * - 大温度数字 + −/+ 大圆按钮（每按 1℃，按 getTemperatureRange 钳制）；
 * - 模式分段按钮：制冷/制热/自动/送风/除湿（getACSupportedMode 过滤）；
 * - 风速：自动/低/中/高（getACSupportedWindSpeed）；扫风开关（getACSupportedSwing）；
 * - 醒目电源开关；状态经 ACStatusCache 持久化；按键走 CodeResolver.resolve()（规则 a）；
 * - 非 IREXT（irdb）空调：提示降级 + 通用按键直发。
 */
@Composable
fun AcPanelScreen(
    deviceId: Long,
    onBack: () -> Unit,
) {
    // ViewModel：依赖从手动 DI 容器（PhotonApplication.container）获取
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: AcPanelViewModel = viewModel(key = "ac-$deviceId") {
        AcPanelViewModel(
            deviceId = deviceId,
            repository = app.container.repository,
            codeResolver = app.container.codeResolver,
            dispatcher = app.container.irDispatcher,
            binaryStore = app.container.binaryStore,
            transmitter = app.container.transmitterManager,
            acStatusCache = app.container.acStatusCache,
            encoders = app.container.encoders,
        )
    }
    val device by viewModel.device.collectAsState()
    val status by viewModel.status.collectAsState()
    val tempRange by viewModel.tempRange.collectAsState()
    val supportedModes by viewModel.supportedModes.collectAsState()
    val supportedWind by viewModel.supportedWindSpeed.collectAsState()
    val supportedSwing by viewModel.supportedSwing.collectAsState()
    val isIrext by viewModel.isIrext.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val sendFailed by viewModel.sendFailed.collectAsState()
    val genericButtons by viewModel.genericButtons.collectAsState()

    val d = device
    if (d == null || loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部：返回 + 设备名
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                d.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // 开机状态徽标
            val st = status ?: ACStatusData()
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (st.acPower == 1) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    if (st.acPower == 1) "运行中" else "已关机",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (st.acPower == 1) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!isIrext) {
            // 非 IREXT 空调：无状态机，降级为通用按键直发
            Text(
                "该空调使用 CSV 码库，暂不支持状态面板，可用下方按键控制",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                genericButtons.take(4).forEach { button ->
                    RemoteKey(
                        label = button.label,
                        size = 64.dp,
                        shape = button.shape,
                        sendFailed = sendFailed,
                        repeatIntervalMs = viewModel.repeatIntervalFor(button),
                        onSend = { press -> viewModel.sendGenericButton(button, press) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "电源键试试吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val st = status ?: return@Column

        // 温度显示 + −/+ 按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 温度 -
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(72.dp).clickable(onClick = viewModel::tempDown),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Remove, contentDescription = "降低温度", modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${st.acTemp}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (st.acPower == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "°",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    "目标温度 ${tempRange.first}~${tempRange.last}℃",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(24.dp))
            // 温度 +
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(72.dp).clickable(onClick = viewModel::tempUp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = "升高温度", modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // 模式分段按钮（制冷/制热/自动/送风/除湿，过滤不支持项）
        Text("模式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODE_LABELS.forEachIndexed { index, label ->
                if (supportedModes.getOrElse(index) { false }) {
                    FilterChip(
                        selected = st.acMode == index,
                        onClick = { viewModel.setMode(index) },
                        label = { Text(label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 风速：自动/低/中/高（过滤不支持项）
        Text("风速", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WIND_SPEED_LABELS.forEachIndexed { index, label ->
                if (supportedWind.getOrElse(index) { false }) {
                    FilterChip(
                        selected = st.acWindSpeed == index,
                        onClick = { viewModel.setWindSpeed(index) },
                        label = { Text(label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 扫风开关
        if (supportedSwing.getOrElse(0) { false }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("扫风", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = st.changeWindDir == 1,
                    onCheckedChange = { viewModel.toggleSwing() },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // 醒目电源开关
        Button(
            onClick = viewModel::togglePower,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (st.acPower == 1) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            },
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (st.acPower == 1) "关机" else "开机")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 模式标签（下标与 ACStatusData.acMode 一致：0制冷 1制热 2自动 3送风 4除湿） */
private val MODE_LABELS = listOf("制冷", "制热", "自动", "送风", "除湿")

/** 风速标签（下标与 acWindSpeed 一致：0自动 1低 2中 3高） */
private val WIND_SPEED_LABELS = listOf("自动", "低", "中", "高")
