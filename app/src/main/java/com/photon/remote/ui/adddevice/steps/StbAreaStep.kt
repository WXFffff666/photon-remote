package com.photon.remote.ui.adddevice.steps

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.photon.remote.viewmodel.AddDeviceViewModel
import com.photon.remote.viewmodel.LocatingState

/**
 * 添加向导步骤 2.5：机顶盒 省→市→运营商 三级选择器（计划 §5.4 / Todo 27-28）。
 *
 * 每级从 IREXT 索引查询（getAreas / getCities / getOperators），选中后自动联动
 * 下一级列表；选完运营商后由底部"下一步"进入型号页。拆分自 AddDeviceSteps.kt
 * 以控制单文件行数。
 *
 * Todo 49 追加：顶部"📍 使用定位"按钮 —— 运行时请求定位权限（ACCESS_FINE_LOCATION）
 * → LocationResolver 解析省市 → AreaNameMatcher 匹配 irext 地区 → 自动预填省/市
 * （高亮），用户继续手动选运营商；定位中/失败/权限被拒均有状态提示，失败降级为手动选择。
 */
@Composable
fun StbAreaStep(viewModel: AddDeviceViewModel) {
    val provinces by viewModel.provinces.collectAsState()
    val cities by viewModel.cities.collectAsState()
    val operators by viewModel.operators.collectAsState()
    val selectedProvince by viewModel.selectedProvince.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val selectedOperator by viewModel.selectedOperator.collectAsState()
    val locatingState by viewModel.locatingState.collectAsState()

    // 运行时定位权限请求（Todo 49）：授权 → 定位匹配；拒绝 → 提示手动选择
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.locateProvinceCity()
        else viewModel.locatePermissionDenied()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部定位行：使用定位按钮 + 状态提示
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.locateProvinceCity()
                    else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                enabled = locatingState != LocatingState.Locating,
            ) {
                if (locatingState == LocatingState.Locating) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("📍 使用定位", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(10.dp))
            LocateStatusText(locatingState, Modifier.weight(1f))
        }
        // 省→市→运营商 三级选择器（原布局）
        Row(Modifier.fillMaxWidth().weight(1f)) {
        // 省份列
        SelectionColumn(
            title = "省份",
            options = provinces,
            selected = selectedProvince,
            onSelect = viewModel::selectProvince,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // 城市列（选中省后激活）
        SelectionColumn(
            title = "城市",
            options = cities,
            selected = selectedCity,
            onSelect = viewModel::selectCity,
            enabled = selectedProvince != null,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // 运营商列（选中城市后激活）
        Column(Modifier.weight(1.3f)) {
            Text(
                "运营商",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(operators, key = { it.operator }) { op ->
                    val isSelected = op.operator == selectedOperator?.operator
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selectedCity != null) { viewModel.selectOperator(op) },
                    ) {
                        Text(
                            op.operator,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/** 定位状态提示文案（Todo 49）：Idle 引导 / Locating 转圈文案 / Found 定位结果 / Failed 失败原因 */
@Composable
private fun LocateStatusText(state: LocatingState, modifier: Modifier = Modifier) {
    val (text, color) = when (state) {
        LocatingState.Idle -> "自动匹配你所在的省市" to MaterialTheme.colorScheme.onSurfaceVariant
        LocatingState.Locating -> "定位中…" to MaterialTheme.colorScheme.onSurfaceVariant
        is LocatingState.Found -> {
            val msg = if (state.city != null) "已定位：${state.province} ${state.city}（可手动修改）"
            else "已定位：${state.province}（城市未匹配，请手动选择）"
            msg to MaterialTheme.colorScheme.primary
        }
        is LocatingState.Failed -> state.reason to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 2.dp),
    )
}

/** 通用单列选择器（省份/城市共用） */
@Composable
private fun SelectionColumn(
    title: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options, key = { it }) { option ->
                val isSelected = option == selected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSelect(option) },
                ) {
                    Text(
                        option,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
