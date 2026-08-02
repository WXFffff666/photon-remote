package com.photon.remote.ui.adddevice.steps

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Hvac
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.viewmodel.BrandOption
import com.photon.remote.viewmodel.CodeOption

/** 设备类型 → Material 图标（计划 §2.1 icon 键的静态映射，防 R8 裁剪） */
fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.TV -> Icons.Rounded.Tv
    DeviceType.STB -> Icons.Rounded.Router
    DeviceType.AC -> Icons.Rounded.AcUnit
    DeviceType.FAN -> Icons.Rounded.Air
    DeviceType.PROJECTOR -> Icons.Rounded.Slideshow
    DeviceType.AUDIO -> Icons.Rounded.Speaker
    DeviceType.PURIFIER -> Icons.Rounded.Hvac
    DeviceType.OTHER -> Icons.Rounded.DevicesOther
}

/** 步骤 1：设备类型（8 种大图标网格，单点选中） */
@Composable
fun TypeStep(selected: DeviceType?, onSelect: (DeviceType) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        items(DeviceType.entries) { type ->
            val isSelected = type == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(type) },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        type.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(type.label, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** 步骤 2：品牌（搜索框 + 品牌列表；IREXT ∪ irdb，中英并显 + 跨源英文名归一去重） */
@Composable
fun BrandStep(
    brands: List<BrandOption>,
    selectedName: String?,
    onSelect: (BrandOption) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // FIX-4：搜索同时匹配 displayName（如搜 "pioneer"/"先锋" 都能命中 "先锋 Pioneer"）
    val filtered = brands.filter {
        query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索品牌") },
            leadingIcon = { Icon(Icons.Rounded.ManageSearch, null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // FIX-3：列表已按"有码组"过滤，空态引导换类型
                Text("已为你筛选有码组的品牌，试试其他类型", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.name }) { brand ->
                    val isSelected = brand.name == selectedName
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(brand) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                // FIX-4：显示中英并显名（如"先锋 Pioneer"）
                                brand.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 码源角标（IREXT / irdb）
                            Text(
                                if (brand.source == CodeSource.IREXT) "IREXT" else "irdb",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 步骤 3：型号/码组列表（IREXT remotes ∪ irdb 码组，按名称排序） */
@Composable
fun ModelStep(
    models: List<CodeOption>,
    selectedRef: String?,
    onSelect: (CodeOption) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("该品牌暂无匹配的码组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    // 4b：无匹配入口（暴力找码 UI 后续 Todo 35 接入，先文字提示）
                    Text(
                        "未找到？可尝试「暴力找码」或「手动添加按键」（即将推出）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.codeRef }) { code ->
                    val isSelected = code.codeRef == selectedRef
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) },
                    ) {
                        ListItem(
                            headlineContent = {
                                // FIX-6：显示友好名（IREXT"型号 N"/irdb CSV 型号名）
                                Text(code.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Text(
                                    if (code.source == CodeSource.IREXT) "IREXT" else "irdb",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
            // 4b：无匹配入口提示（暴力找码 UI 后续 Todo 35 接入）
            Text(
                "没有匹配的型号？试试「暴力找码」（后续版本提供）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
