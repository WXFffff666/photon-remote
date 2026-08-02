package com.photon.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrextBinaryStore
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ACStatusData
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.action
import com.photon.remote.data.model.toJson
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.di.ACStatusCache
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.ACStatusHelper
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
 * 空调面板 ViewModel（计划 §5.7 / Todo 31）。
 *
 * - 状态：ACStatusData（应用层语义 6 字段），经 AppContainer 内 ACStatusCache
 *   内存缓存读写（启动自 SettingsStore 水合、变更回写，重启恢复）；
 * - 能力：温度范围 / 模式 / 风速 / 扫风 从已打开的 IREXT 码组动态查询
 *   （getTemperatureRange / getACSupportedMode / getACSupportedWindSpeed / getACSupportedSwing），
 *   全部经 IrDispatcher 串行（JNI 单例共享原生状态）；
 * - 发送：每次操作先更新状态 → 构造对应 AC 功能键（IrextKey）→
 *   CodeResolver.resolve()（规则 a 页面路径，acStatusFor 自动取当前状态）→ transmit，
 *   整段作为 IrDispatcher 单个原子队列任务；
 * - IREXT 会话（规则 a）：进入 open 一次、onCleared close；
 * - 非 IREXT（irdb AC）无状态机：仅提供通用按键直发（面板降级提示）。
 */
class AcPanelViewModel(
    private val deviceId: Long,
    private val repository: DeviceRepository,
    private val codeResolver: CodeResolver,
    private val dispatcher: IrDispatcher,
    private val binaryStore: IrextBinaryStore,
    private val transmitter: TransmitterManager,
    private val acStatusCache: ACStatusCache,
    private val encoders: Map<ProtocolType, IrProtocolEncoder>,
) : ViewModel() {

    /** 当前设备 */
    private val _device = MutableStateFlow<Device?>(null)
    val device: StateFlow<Device?> = _device.asStateFlow()

    /** 应用层 AC 状态（null = 尚未加载） */
    private val _status = MutableStateFlow<ACStatusData?>(null)
    val status: StateFlow<ACStatusData?> = _status.asStateFlow()

    /** 当前模式温度范围（℃），JNI 查询失败退化为 16..30 */
    private val _tempRange = MutableStateFlow(ACStatusHelper.TEMP_ABSOLUTE_MIN..ACStatusHelper.TEMP_ABSOLUTE_MAX)
    val tempRange: StateFlow<IntRange> = _tempRange.asStateFlow()

    /** 支持的模式（下标 0..4 = 制冷/制热/自动/送风/除湿） */
    private val _supportedModes = MutableStateFlow(BooleanArray(5) { true })
    val supportedModes: StateFlow<BooleanArray> = _supportedModes.asStateFlow()

    /** 当前模式支持的风速（下标 0..3 = 自动/低/中/高） */
    private val _supportedWindSpeed = MutableStateFlow(BooleanArray(4) { true })
    val supportedWindSpeed: StateFlow<BooleanArray> = _supportedWindSpeed.asStateFlow()

    /** 当前模式支持的扫风（下标 0=开 1=关） */
    private val _supportedSwing = MutableStateFlow(BooleanArray(2) { true })
    val supportedSwing: StateFlow<BooleanArray> = _supportedSwing.asStateFlow()

    /** 是否 IREXT 码组（非 IREXT 面板降级为通用按键） */
    private val _isIrext = MutableStateFlow(false)
    val isIrext: StateFlow<Boolean> = _isIrext.asStateFlow()

    /** 通用按键（非 IREXT 降级面板用，来自 Repository 按键集） */
    private val _genericButtons = MutableStateFlow<List<RemoteButton>>(emptyList())
    val genericButtons: StateFlow<List<RemoteButton>> = _genericButtons.asStateFlow()

    /** 加载中 */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 最近发送失败（UI 短暂红色提示） */
    private val _sendFailed = MutableStateFlow(false)
    val sendFailed: StateFlow<Boolean> = _sendFailed.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** 加载设备 + AC 状态水合 + 打开 IREXT 会话（规则 a）+ 查询能力 */
    private suspend fun load() {
        // 从设备列表流中取目标设备（DeviceRepository 未暴露单设备查询，读现有 API）
        val d = repository.devices.first().firstOrNull { it.id == deviceId } ?: return
        _device.value = d
        _genericButtons.value = repository.getButtons(deviceId)
        // 状态水合（ACStatusCache 惰性按设备从 SettingsStore 拉取历史）
        acStatusCache.hydrate(deviceId)
        _status.value = acStatusCache.get(deviceId) ?: ACStatusData()

        val irext = d.codeSource == CodeSource.IREXT && IrextDecoder.isAvailable
        _isIrext.value = irext
        if (irext) {
            // 规则 a：进入页面打开会话
            val ref = binaryStore.load(d.codeRef)
            if (ref != null) {
                dispatcher.onQueue {
                    IrextDecoder.open(ref.binaryName, ref.category, ref.subCate, ref.bytes)
                }
                refreshSupports(_status.value ?: ACStatusData())
            }
        }
        _loading.value = false
    }

    /** 重新查询能力（温度范围/模式/风速/扫风），全部经 IrDispatcher 串行 */
    private suspend fun refreshSupports(st: ACStatusData) {
        val range = dispatcher.onQueue { IrextDecoder.getTemperatureRange(st.acMode) }
        _tempRange.value = ACStatusHelper.toAppTempRange(range)
        _supportedModes.value = dispatcher.onQueue { IrextDecoder.getACSupportedMode() }
        _supportedWindSpeed.value = dispatcher.onQueue { IrextDecoder.getACSupportedWindSpeed(st.acMode) }
        _supportedSwing.value = dispatcher.onQueue { IrextDecoder.getACSupportedSwing(st.acMode) }
        // 模式切换后温度钳制到新模式范围
        _status.value = st.copy(acTemp = st.acTemp.coerceIn(_tempRange.value))
    }

    // ---------- 操作（更新状态 → 缓存回写 → resolve + transmit） ----------

    /** 电源开关（IrextKey POWER） */
    fun togglePower() = act({ it.copy(acPower = 1 - it.acPower) }, IrextDecoder.APP_KEY_POWER)

    /** 温度 +1（钳制到当前模式范围，IrextKey VOL_UP） */
    fun tempUp() = act({ it.copy(acTemp = (it.acTemp + 1).coerceIn(_tempRange.value)) }, IrextDecoder.APP_KEY_VOL_UP)

    /** 温度 -1（钳制到当前模式范围，IrextKey VOL_DOWN） */
    fun tempDown() = act({ it.copy(acTemp = (it.acTemp - 1).coerceIn(_tempRange.value)) }, IrextDecoder.APP_KEY_VOL_DOWN)

    /** 切换模式（IrextKey RIGHT = 模式切换；切换后重新查询风速/扫风支持） */
    fun setMode(mode: Int) {
        val rangeForNewMode = _tempRange.value
        act(
            { it.copy(acMode = mode, acTemp = it.acTemp.coerceIn(rangeForNewMode)) },
            IrextDecoder.APP_KEY_RIGHT,
            refreshAfter = true,
        )
    }

    /** 设置风速（IrextKey UP = 风速） */
    fun setWindSpeed(speed: Int) =
        act({ it.copy(acWindSpeed = speed) }, IrextDecoder.APP_KEY_UP)

    /** 扫风开关（IrextKey OK = 扫风，changeWindDir 翻转） */
    fun toggleSwing() = act({ it.copy(changeWindDir = 1 - it.changeWindDir) }, IrextDecoder.APP_KEY_OK)

    /** 非 IREXT 降级面板：通用按键直发（规则 a 对 SendProtocol/SendRaw 同样成立） */
    fun sendGenericButton(button: RemoteButton, press: PressKind) {
        val d = _device.value ?: return
        viewModelScope.launch {
            val ok = dispatcher.onQueue {
                codeResolver.resolve(d, button, press)?.let { transmitter.transmit(it) } ?: false
            }
            if (!ok) flashFailed()
        }
    }

    /** 长按连发间隔：SendProtocol 按协议覆盖（encoder.repeatIntervalMs），其余 250ms */
    fun repeatIntervalFor(button: RemoteButton): Int {
        return try {
            when (val action = button.action()) {
                is ButtonAction.SendProtocol -> encoders[action.protocol]?.repeatIntervalMs ?: 250
                else -> 250
            }
        } catch (e: Exception) {
            250
        }
    }

    /** 规则 a：退出关闭 IREXT 会话 */
    override fun onCleared() {
        viewModelScope.launch { dispatcher.onQueue { IrextDecoder.close() } }
    }

    // ---------- 内部 ----------

    /**
     * 执行一次 AC 操作：更新状态（内存 + ACStatusCache 回写）→ 构造 AC 功能键 →
     * resolve + transmit（IrDispatcher 原子任务）→ 失败红闪提示。
     */
    private fun act(
        transform: (ACStatusData) -> ACStatusData,
        keyCode: Int,
        refreshAfter: Boolean = false,
    ) {
        val d = _device.value ?: return
        val current = _status.value ?: return
        val next = transform(current)
        _status.value = next
        acStatusCache.set(deviceId, next)   // 同步写内存 + 协程回写 SettingsStore
        viewModelScope.launch {
            val ok = dispatcher.onQueue {
                codeResolver.resolve(d, acButton(keyCode))?.let { transmitter.transmit(it) } ?: false
            }
            if (!ok) flashFailed()
            if (refreshAfter) refreshSupports(next)   // 模式变化 → 重新查询能力
        }
    }

    /** 构造 AC 功能键（临时按键，IrextKey 二进制引用以 Device.codeRef 为准） */
    private fun acButton(keyCode: Int): RemoteButton = RemoteButton(
        deviceId = deviceId,
        keyId = "AC_$keyCode",
        label = "空调功能",
        actionJson = ButtonAction.IrextKey(keyCode, "").toJson(),
    )

    /** 失败红闪提示（1 秒自动清除） */
    private suspend fun flashFailed() {
        _sendFailed.value = true
        delay(1000)
        _sendFailed.value = false
    }
}
