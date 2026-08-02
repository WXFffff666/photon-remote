package com.photon.remote.ui.adddevice.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photon.remote.viewmodel.AddDeviceViewModel

/**
 * 添加向导步骤 2.5：机顶盒 省→市→运营商 三级选择器（计划 §5.4 / Todo 27-28）。
 *
 * 每级从 IREXT 索引查询（getAreas / getCities / getOperators），选中后自动联动
 * 下一级列表；选完运营商后由底部"下一步"进入型号页。拆分自 AddDeviceSteps.kt
 * 以控制单文件行数。
 */
@Composable
fun StbAreaStep(viewModel: AddDeviceViewModel) {
    val provinces by viewModel.provinces.collectAsState()
    val cities by viewModel.cities.collectAsState()
    val operators by viewModel.operators.collectAsState()
    val selectedProvince by viewModel.selectedProvince.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val selectedOperator by viewModel.selectedOperator.collectAsState()

    Row(Modifier.fillMaxSize().padding(12.dp)) {
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
