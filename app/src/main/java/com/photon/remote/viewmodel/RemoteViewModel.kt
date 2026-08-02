package com.photon.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrextBinaryStore
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.action
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.IrextDecoder
import com.photon.remote.ir.transmitter.IrDispatcher
import com.photon.remote.ir.transmitter.TransmitterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 遥控器页 ViewModel（计划 §5.5 / Todo 30）。
 *
 * IREXT 会话规则（CodeResolver §4.3 规则 a）：进入页面 open 一次、退出（onCleared）
 * close 一次；按键只走 resolve() 解码，不做 open/close。
 * 发送链路：resolve() → TransmitterManager.transmit，整段作为 IrDispatcher
 * 单个原子队列任务（防止会话交换窗口被其他任务交错）。
 *
 * 长按连发间隔：SendProtocol 按键取 encoder.repeatIntervalMs（NEC/JVC=110ms），
 * 其余（SendRaw / IrextKey）用全局默认 250ms。
 */
class RemoteViewModel(
    private val deviceId: Long,
    private val repository: DeviceRepository,
    private val codeResolver: CodeResolver,
    private val dispatcher: IrDispatcher,
    private val binaryStore: IrextBinaryStore,
    private val transmitter: TransmitterManager,
    private val encoders: Map<ProtocolType, IrProtocolEncoder>,
) : ViewModel() {

    /** 当前设备（加载完成后非 null） */
    private val _device = MutableStateFlow<Device?>(null)
    val device: StateFlow<Device?> = _device.asStateFlow()

    /** 设备全部按键（按布局顺序） */
    private val _buttons = MutableStateFlow<List<RemoteButton>>(emptyList())
    val buttons: StateFlow<List<RemoteButton>> = _buttons.asStateFlow()

    /** 最近发送失败的按键 id（UI 红色提示，1 秒后自动清除） */
    private val _failedButtonId = MutableStateFlow<Long?>(null)
    val failedButtonId: StateFlow<Long?> = _failedButtonId.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** 加载设备 + 按键 + 打开 IREXT 会话（规则 a） */
    private suspend fun load() {
        // 从设备列表流中取目标设备（DeviceRepository 未暴露单设备查询，读现有 API）
        val d = repository.devices.first().firstOrNull { it.id == deviceId } ?: return
        _device.value = d
        _buttons.value = repository.getButtons(deviceId)
        // 规则 a：IREXT 设备进入页面打开会话；x86 无 so 时静默跳过
        if (d.codeSource == CodeSource.IREXT && IrextDecoder.isAvailable) {
            val ref = binaryStore.load(d.codeRef) ?: return
            dispatcher.onQueue {
                IrextDecoder.open(ref.binaryName, ref.category, ref.subCate, ref.bytes)
            }
        }
    }

    /**
     * 发送按键（单击 NEW_PRESS / 长按连发 REPEAT）。
     * 失败时记录 failedButtonId（红色提示），1 秒后自动清除。
     */
    fun sendButton(button: RemoteButton, press: PressKind) {
        viewModelScope.launch {
            val ok = sendSync(button, press)
            if (!ok) {
                _failedButtonId.value = button.id
                delay(1000)
                if (_failedButtonId.value == button.id) _failedButtonId.value = null
            }
        }
    }

    /** 收藏切换（顶部星标） */
    fun toggleFavorite() {
        val d = _device.value ?: return
        viewModelScope.launch { repository.setFavorite(deviceId, !d.isFavorite) }
    }

    /** 长按连发间隔：SendProtocol 按协议覆盖，其余 250ms 全局默认 */
    fun repeatIntervalFor(button: RemoteButton): Int {
        return try {
            when (val action = button.action()) {
                is ButtonAction.SendProtocol -> encoders[action.protocol]?.repeatIntervalMs ?: 250
                else -> 250
            }
        } catch (e: Exception) {
            250   // 动作数据损坏：用默认间隔
        }
    }

    /** 规则 a：退出页面关闭 IREXT 会话 */
    override fun onCleared() {
        viewModelScope.launch { dispatcher.onQueue { IrextDecoder.close() } }
    }

    /** 整段原子任务：resolve + transmit（resolve 已假定会话 open，见规则 a） */
    private suspend fun sendSync(button: RemoteButton, press: PressKind): Boolean {
        val d = _device.value ?: return false
        return dispatcher.onQueue {
            codeResolver.resolve(d, button, press)?.let { transmitter.transmit(it) } ?: false
        }
    }
}
