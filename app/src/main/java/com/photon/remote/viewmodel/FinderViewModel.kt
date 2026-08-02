package com.photon.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.PhotonApplication
import com.photon.remote.codebase.finder.BruteForceConfig
import com.photon.remote.codebase.finder.IrBruteForcer
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.toJson
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.protocol.ProtocolEncoders
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 暴力找码页面 UI 状态（Todo 35） */
data class FinderUiState(
    val protocol: ProtocolType = ProtocolType.NEC,   // 当前选中的协议
    val prefix: String = "",                          // hex 前缀输入（AA / 0xAABB / AA:BB）
    val candidateCount: Long? = 0L,                   // 候选总数；null = 前缀非法/超限
    val prefixError: String? = null,                  // 前缀校验的中文错误提示
    val running: Boolean = false,                     // 是否正在找码
    val tested: Long = 0L,                            // 已测试候选数
    val total: Long = 0L,                             // 总数（进度条分母）
    val currentHex: String? = null,                   // 当前正在测试的候选码（大写、按位宽补齐）
    val hitHex: String? = null,                       // 命中候选（run 提前返回时）；保存以此优先
    val saveMessage: String? = null,                  // 保存按键结果提示（Toast 用，一次性）
)

/**
 * 暴力找码 ViewModel（计划 §4.4 / §5.9 / Todo 35）。
 *
 * - 协议下拉 = [ProtocolEncoders] 全部协议（排除 RAW：其输入是微秒序列而非 hex 位宽迭代）；
 * - 前缀校验与候选数计算复用 [IrBruteForcer.parsePrefix / candidateCount]，非法前缀给中文提示；
 * - 运行：IrBruteForcer.run 循环，发送经 container 的 IrDispatcher（单线程串行队列）；
 *   发射回调恒返回 false 表示"设备未确认响应"，迭代持续到用户手动停止（本应用无红外收码
 *   硬件，自动命中检测留待接入 IR 接收器时启用——届时 transmit 回调改为返回响应状态，
 *   run() 会在命中时提前返回并填入 [FinderUiState.hitHex]）；
 * - 停止 = 协程取消（IrBruteForcer 内部捕获 CancellationException 正常返回 null）；
 * - 保存：把当前码（命中码优先，否则最后测试的码）作为 SendProtocol 按键写入目标设备。
 */
class FinderViewModel(application: Application) : AndroidViewModel(application) {

    /** 手动 DI 容器（依赖统一从容器获取，AndroidViewModel 模式） */
    private val container get() = (getApplication<PhotonApplication>()).container
    private val repository get() = container.repository
    private val dispatcher get() = container.irDispatcher

    /** 找码支持的协议列表（排除 RAW）；UI 下拉与候选数展示共用 */
    val protocols: List<ProtocolType> =
        ProtocolEncoders.all.keys.filter { it != ProtocolType.RAW }

    /** 协议 → 位宽（与各编码器 hex 位宽一致，见计划 §3.2 表与各编码器 padStart 位宽） */
    private val bitWidths: Map<ProtocolType, Int> = mapOf(
        ProtocolType.NEC to 32,
        ProtocolType.NECX1 to 32,
        ProtocolType.NECX2 to 32,
        ProtocolType.RC5 to 16,
        ProtocolType.RC6 to 16,
        ProtocolType.SONY12 to 12,
        ProtocolType.SONY15 to 15,
        ProtocolType.SONY20 to 20,
        ProtocolType.SAMSUNG32 to 32,
        ProtocolType.SHARP to 13,
        ProtocolType.JVC to 16,
        ProtocolType.KASEIKYO to 48,
        ProtocolType.PIONEER to 32,
    )

    /** 设备列表（保存按键时的目标设备选择） */
    val devices: Flow<List<Device>> = repository.devices

    private val _state = MutableStateFlow(FinderUiState())
    val state: StateFlow<FinderUiState> = _state.asStateFlow()

    /** 当前找码任务（stop() 取消用） */
    private var findJob: Job? = null

    // ---------- 输入 ----------

    /** 切换协议（运行中忽略） */
    fun selectProtocol(protocol: ProtocolType) {
        if (_state.value.running) return
        _state.value = _state.value.copy(protocol = protocol)
        refreshCandidateInfo()
    }

    /** 更新前缀输入并重算候选数 */
    fun setPrefix(prefix: String) {
        if (_state.value.running) return
        _state.value = _state.value.copy(prefix = prefix)
        refreshCandidateInfo()
    }

    /** 根据当前协议+前缀重算候选数；非法前缀给出中文错误（候选数置 null） */
    private fun refreshCandidateInfo() {
        val st = _state.value
        try {
            val count = IrBruteForcer.candidateCount(configOf(st))
            _state.value = st.copy(candidateCount = count, prefixError = null)
        } catch (e: IllegalArgumentException) {
            _state.value = st.copy(candidateCount = null, prefixError = e.message)
        }
    }

    // ---------- 运行 / 停止 ----------

    /** 开始找码：校验前缀 → 启动迭代协程 */
    fun start() {
        val st = _state.value
        if (st.running) return
        val config = try {
            configOf(st)
        } catch (e: IllegalArgumentException) {
            _state.value = st.copy(prefixError = e.message)
            return
        }
        _state.value = st.copy(
            running = true,
            tested = 0,
            total = st.candidateCount ?: 0L,
            currentHex = null,
            hitHex = null,
            prefixError = null,
        )
        findJob = viewModelScope.launch {
            val hit = IrBruteForcer.run(
                config = config,
                // 发送回调：经 IrDispatcher 串行发送；恒 false 使迭代持续（见类注释）
                transmit = { pattern ->
                    dispatcher.send(pattern)
                    false
                },
                onTested = { hex, tested, total ->
                    _state.value = _state.value.copy(currentHex = hex, tested = tested, total = total)
                },
            )
            _state.value = _state.value.copy(running = false, hitHex = hit)
        }
    }

    /** 停止找码（取消迭代协程，保留当前码供保存） */
    fun stop() {
        findJob?.cancel()
        findJob = null
        _state.value = _state.value.copy(running = false)
    }

    // ---------- 保存 ----------

    /** 把当前码保存为指定设备的按键（SendProtocol）；返回结果提示文案 */
    fun saveCurrentAsButton(deviceId: Long) {
        val st = _state.value
        val hex = st.hitHex ?: st.currentHex ?: return
        viewModelScope.launch {
            try {
                val order = repository.getButtons(deviceId).size
                repository.addButton(
                    RemoteButton(
                        deviceId = deviceId,
                        keyId = "CUSTOM_$hex",
                        label = "找码 $hex",
                        actionJson = ButtonAction.SendProtocol(st.protocol, hex).toJson(),
                        order = order,
                    ),
                )
                _state.value = _state.value.copy(saveMessage = "已保存按键：${st.protocol.name} $hex")
            } catch (e: Exception) {
                _state.value = _state.value.copy(saveMessage = "保存失败：${e.message}")
            }
        }
    }

    /** 消费一次性提示（Toast 展示后调用） */
    fun consumeSaveMessage() {
        _state.value = _state.value.copy(saveMessage = null)
    }

    // ---------- 工具 ----------

    /** 由当前状态构造迭代配置（前缀非法时抛中文 IllegalArgumentException） */
    private fun configOf(st: FinderUiState): BruteForceConfig = BruteForceConfig(
        protocol = st.protocol,
        prefixHex = st.prefix,
        bitWidth = bitWidths[st.protocol] ?: 32,
    )

    override fun onCleared() {
        findJob?.cancel()
        super.onCleared()
    }
}
