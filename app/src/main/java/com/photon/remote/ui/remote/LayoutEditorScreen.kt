package com.photon.remote.ui.remote

// 自定义遥控器布局编辑器（计划 §5.6）：
// 6×8 网格画布，长按拖拽移动按键、右下角拖柄调整大小、圆形↔圆角切换、按键抽屉添加、保存/恢复默认。
// 本文件为独立编辑器界面，仅通过 onSave 回调把结果交给调用方（RemoteScreen 接入由装配阶段完成）。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 编辑画布网格：8 列 × 6 行 */
private const val GRID_COLS = 8
private const val GRID_ROWS = 6

// 可编辑按键的 UI 模型（与 RemoteButton 字段对应，但独立定义避免依赖实体）
data class EditableKey(
    val id: Long,
    val keyId: String,
    val label: String,
    val icon: String? = null,
    val col: Int = 0,
    val row: Int = 0,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val isRound: Boolean = false,
)

@Composable
fun LayoutEditorScreen(
    deviceName: String,
    initialKeys: List<EditableKey>,
    availableKeys: List<EditableKey>,   // "更多按键"抽屉可用键
    onSave: (List<EditableKey>) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val keys = remember { initialKeys.toMutableStateList() }
    var pendingDelete by remember { mutableStateOf<EditableKey?>(null) }

    fun updateKey(updated: EditableKey) {
        val index = keys.indexOfFirst { it.id == updated.id }
        if (index >= 0) keys[index] = updated
    }

    // 判断某个网格单元是否已被任一按键占据
    fun isOccupied(col: Int, row: Int): Boolean {
        keys.forEach { k ->
            val inCol = col >= k.col && col < k.col + k.colSpan
            val inRow = row >= k.row && row < k.row + k.rowSpan
            if (inCol && inRow) return true
        }
        return false
    }

    // 把抽屉里的键添加到第一个空位（span 按网格边界裁剪）
    fun addKey(template: EditableKey) {
        for (r in 0 until GRID_ROWS) {
            for (c in 0 until GRID_COLS) {
                val cs = template.colSpan.coerceIn(1, GRID_COLS - c)
                val rs = template.rowSpan.coerceIn(1, GRID_ROWS - r)
                if (cs < 1 || rs < 1) continue
                var free = true
                for (dr in 0 until rs) {
                    for (dc in 0 until cs) {
                        if (isOccupied(c + dc, r + dr)) { free = false; break }
                    }
                    if (!free) break
                }
                if (free) {
                    keys.add(template.copy(col = c, row = r, colSpan = cs, rowSpan = rs))
                    return
                }
            }
        }
        // 无空位：忽略本次添加
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // 顶部栏：设备名 + 恢复默认 + 保存 + 关闭
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset) { Text("恢复默认") }
                Button(onClick = { onSave(keys.toList()) }) { Text("保存") }
                TextButton(onClick = onClose) { Text("关闭") }
            }

            Spacer(Modifier.height(12.dp))

            // 6×8 网格画布
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val cellW = maxWidth / GRID_COLS
                    val cellH = maxHeight / GRID_ROWS

                    // 网格线
                    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    Canvas(Modifier.fillMaxSize()) {
                        val cw = size.width / GRID_COLS
                        val ch = size.height / GRID_ROWS
                        for (i in 1 until GRID_COLS) {
                            drawLine(lineColor, Offset(i * cw, 0f), Offset(i * cw, size.height), strokeWidth = 1f)
                        }
                        for (j in 1 until GRID_ROWS) {
                            drawLine(lineColor, Offset(0f, j * ch), Offset(size.width, j * ch), strokeWidth = 1f)
                        }
                    }

                    // 按键
                    keys.forEach { k ->
                        EditorKey(
                            keyId = k.id,
                            keys = keys,
                            cellW = cellW,
                            cellH = cellH,
                            onKeyUpdated = ::updateKey,
                            onRequestDelete = { pendingDelete = it },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 底部"更多按键"抽屉
            Column {
                Text("更多按键", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(availableKeys.filter { a -> keys.none { it.id == a.id } }, key = { it.id }) { ak ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .width(76.dp)
                                .clickable { addKey(ak) },
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    ak.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "点击添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除按键") },
            text = { Text("确定删除按键“${target.label}”吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        keys.removeAll { it.id == target.id }
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 单个可编辑按键：拖拽移动、右下角拖柄改大小、点击右上角切换圆形/圆角、长按删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorKey(
    keyId: Long,
    keys: List<EditableKey>,
    cellW: Dp,
    cellH: Dp,
    onKeyUpdated: (EditableKey) -> Unit,
    onRequestDelete: (EditableKey) -> Unit,
) {
    val key = keys.first { it.id == keyId }
    var dragCells by remember(keyId) { mutableStateOf(Offset.Zero) }
    val shape = if (key.isRound) CircleShape else RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    ((key.col + dragCells.x) * cellW.value * density).roundToInt(),
                    ((key.row + dragCells.y) * cellH.value * density).roundToInt(),
                )
            }
            .width((cellW * key.colSpan).coerceAtLeast(20.dp))
            .height((cellH * key.rowSpan).coerceAtLeast(20.dp))
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary)
            // 长按拖拽移动，松手吸附到最近网格
            .pointerInput(keyId, cellW, cellH) {
                detectDragGestures(
                    onDragStart = { dragCells = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        val cw = cellW.value * density
                        val ch = cellH.value * density
                        dragCells += Offset(amount.x / cw, amount.y / ch)
                    },
                    onDragEnd = {
                        val cur = keys.first { it.id == keyId }
                        val newCol = (cur.col + dragCells.x).roundToInt().coerceIn(0, GRID_COLS - cur.colSpan)
                        val newRow = (cur.row + dragCells.y).roundToInt().coerceIn(0, GRID_ROWS - cur.rowSpan)
                        if (newCol != cur.col || newRow != cur.row) {
                            onKeyUpdated(cur.copy(col = newCol, row = newRow))
                        }
                        dragCells = Offset.Zero
                    },
                )
            }
            // 长按删除（弹确认框）
            .combinedClickable(
                onClick = {},
                onLongClick = { onRequestDelete(keys.first { it.id == keyId }) },
            ),
    ) {
        Text(
            text = key.label,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )

        // 右上角形状切换按钮：圆形↔圆角
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .clickable {
                    val cur = keys.first { it.id == keyId }
                    onKeyUpdated(cur.copy(isRound = !cur.isRound))
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (key.isRound) "▭" else "◯",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 右下角拖柄：拖动调整 colSpan / rowSpan
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(keyId, cellW, cellH) {
                    var acc = Offset.Zero
                    var startColSpan = 1
                    var startRowSpan = 1
                    detectDragGestures(
                        onDragStart = {
                            val cur = keys.first { it.id == keyId }
                            startColSpan = cur.colSpan
                            startRowSpan = cur.rowSpan
                            acc = Offset.Zero
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val cur = keys.first { it.id == keyId }
                            val cw = cellW.value * density
                            val ch = cellH.value * density
                            acc += Offset(amount.x / cw, amount.y / ch)
                            val newColSpan = (startColSpan + acc.x).roundToInt().coerceIn(1, GRID_COLS - cur.col)
                            val newRowSpan = (startRowSpan + acc.y).roundToInt().coerceIn(1, GRID_ROWS - cur.row)
                            if (newColSpan != cur.colSpan || newRowSpan != cur.rowSpan) {
                                onKeyUpdated(cur.copy(colSpan = newColSpan, rowSpan = newRowSpan))
                            }
                        },
                    )
                },
        )
    }
}
