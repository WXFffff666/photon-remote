package com.photon.remote.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.viewmodel.HomeViewModel

/**
 * 首页设备列表（计划 §5.3 / Todo 26）。
 *
 * 顶部"我的遥控器"标题 + 搜索框（品牌/型号模糊过滤）；设备卡片 2 列网格
 * （首字母色块 + 设备名 + 品牌/型号副标题 + 收藏星标）；空状态引导；
 * 长按卡片弹出菜单（收藏切换 / 重命名 / 排序 / 删除）；FAB 进入添加向导。
 */
@Composable
fun HomeScreen(
    onDeviceClick: (Device) -> Unit,
    onAddClick: () -> Unit,
) {
    // ViewModel：依赖从手动 DI 容器（PhotonApplication.container）获取
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: HomeViewModel = viewModel { HomeViewModel(app.container.repository) }
    val devices by viewModel.devices.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var menuDeviceId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<Device?>(null) }
    var deleteTarget by remember { mutableStateOf<Device?>(null) }
    var sortTarget by remember { mutableStateOf<Device?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Rounded.Add, contentDescription = "添加") },
                text = { Text("添加遥控器") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            // 顶部标题 + 搜索框
            Text("我的遥控器", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchText,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索品牌 / 型号") },
                leadingIcon = { Icon(Icons.Rounded.ManageSearch, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.height(16.dp))

            if (devices.isEmpty()) {
                // 区分"完全没有设备"与"搜索无结果"两种空态
                if (searchText.isBlank()) EmptyState(onAddClick)
                else SearchEmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            menuExpanded = menuDeviceId == device.id,
                            onMenuDismiss = { menuDeviceId = null },
                            onClick = { onDeviceClick(device) },
                            onLongClick = { menuDeviceId = device.id },
                            onFavorite = { viewModel.toggleFavorite(device) },
                            onRename = { menuDeviceId = null; renameTarget = device },
                            onSort = { menuDeviceId = null; sortTarget = device },
                            onDelete = { menuDeviceId = null; deleteTarget = device },
                        )
                    }
                }
            }
        }
    }

    // 长按菜单对话框（重命名 / 排序 / 删除确认）
    renameTarget?.let { device -> RenameDialog(device, onDismiss = { renameTarget = null }, onConfirm = { viewModel.renameDevice(device.id, it); renameTarget = null }) }
    deleteTarget?.let { device -> DeleteConfirmDialog(device, onDismiss = { deleteTarget = null }, onConfirm = { viewModel.deleteDevice(device); deleteTarget = null }) }
    sortTarget?.let { device -> SortDialog(device, maxIndex = (devices.size - 1).coerceAtLeast(0), onDismiss = { sortTarget = null }, onConfirm = { index -> viewModel.moveDevice(device.id, index); sortTarget = null }) }
}
/**
 * 设备卡片：首字母色块（colorSeed 取色）+ 设备名 + 品牌/型号副标题 + 收藏星标。
 * 单击进入遥控器/空调页；长按弹出操作菜单。
 */
@Composable
private fun DeviceCard(
    device: Device,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onSort: () -> Unit,
    onDelete: () -> Unit,
) {
    val seedColor = remember(device.colorSeed) { Color(device.colorSeed) }
    Box {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 首字母大圆角色块（colorSeed 取色）
                    Box(
                        modifier = Modifier.size(44.dp).background(seedColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            device.name.firstOrNull()?.toString() ?: "?",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 收藏星标
                    IconButton(onClick = onFavorite) {
                        Icon(
                            if (device.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (device.isFavorite) "取消收藏" else "收藏",
                            tint = if (device.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(device.brand, device.model).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 长按菜单：收藏切换 / 重命名 / 排序 / 删除
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(
                text = { Text(if (device.isFavorite) "取消收藏" else "收藏") },
                leadingIcon = { Icon(if (device.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) },
                onClick = onFavorite,
            )
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = onRename,
            )
            DropdownMenuItem(
                text = { Text("排序") },
                leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                onClick = onSort,
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
            )
        }
    }
}

/** 空状态：无设备时引导添加第一个遥控器 */
@Composable
private fun EmptyState(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Tv,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有遥控器", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "添加电视、机顶盒、空调等设备，\n把它们装进手机里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onAddClick) { Text("＋ 添加第一个遥控器") }
    }
}

/** 搜索无结果空态 */
@Composable
private fun SearchEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.ManageSearch,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("未找到匹配的设备", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "换个品牌或型号关键词试试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
