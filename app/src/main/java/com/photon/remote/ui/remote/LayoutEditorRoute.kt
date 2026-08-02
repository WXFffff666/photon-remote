package com.photon.remote.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.viewmodel.DefaultButtonFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 布局编辑器路由（计划 §5.6 / Todo 32 装配收尾）。
 *
 * 进入时把当前设备按键转成 EditableKey 列表交给 LayoutEditorScreen；
 * onSave：批量写回按键布局字段（col/row/colSpan/rowSpan/shape）+ 更新设备
 *   layoutId="custom_json" / layoutJson（简单 JSON 数组）；
 * onReset：删除现有按键并重新生成默认按键集，layoutId="default_<type>" / layoutJson=null。
 * 保存/重置后 RemoteViewModel 跟随仓库流自动刷新（见 RemoteViewModel）。
 */
@Composable
fun LayoutEditorRoute(deviceId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotonApplication
    val repository = app.container.repository
    val irdbParser = app.container.irdbParser
    val scope = rememberCoroutineScope()

    var device by remember { mutableStateOf<Device?>(null) }
    var buttons by remember { mutableStateOf<List<RemoteButton>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(deviceId) {
        val list = repository.devices.first()
        device = list.firstOrNull { it.id == deviceId }
        buttons = repository.getButtons(deviceId)
        loaded = true
    }

    val d = device
    if (!loaded || d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 当前设备全部按键 → 编辑器模型（画布初始键 + "更多按键"抽屉共用同一份）
    val initialKeys = remember(buttons) { buttons.map { it.toEditableKey() } }

    LayoutEditorScreen(
        deviceName = d.name,
        initialKeys = initialKeys,
        availableKeys = initialKeys,
        onSave = { keys ->
            scope.launch {
                // 1) 编辑后的按键批量写回（布局字段）
                val byId = buttons.associateBy { it.id }
                keys.forEach { k ->
                    byId[k.id]?.let { b ->
                        repository.updateButton(
                            b.copy(
                                col = k.col, row = k.row,
                                colSpan = k.colSpan, rowSpan = k.rowSpan,
                                shape = if (k.isRound) ButtonShape.CIRCLE else ButtonShape.ROUNDED,
                            ),
                        )
                    }
                }
                // 2) 设备标记自定义布局
                device?.let {
                    repository.updateDevice(it.copy(layoutId = "custom_json", layoutJson = encodeLayout(keys)))
                }
                onBack()
            }
        },
        onReset = {
            scope.launch {
                val cur = device ?: return@launch
                // 删除现有按键 → 重新生成默认按键集 → 清除自定义布局标记
                buttons.forEach { repository.deleteButton(it) }
                repository.addButtons(DefaultButtonFactory.buttonsFor(cur, irdbParser))
                repository.updateDevice(
                    cur.copy(layoutId = "default_${cur.type.name.lowercase()}", layoutJson = null),
                )
                onBack()
            }
        },
        onClose = onBack,
    )
}
