package com.photon.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.PhotonApplication
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.MacroStep
import com.photon.remote.ui.macro.MacroExecState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 宏 ViewModel（计划 §5.9 / Todo 33）。
 *
 * 依赖统一从手动 DI 容器（PhotonApplication.container）获取（AndroidViewModel 模式）：
 * DeviceRepository（宏 CRUD + 设备/按键查询）、CodeResolver（resolveOneShot 一次性路径）、
 * IrDispatcher + TransmitterManager（串行发射）。
 *
 * 执行流程（executeMacro）：一次性加载设备/按键映射 → 逐步骤
 * 设备+按键解析 → resolveOneShot（open→decode→close 自包含）→ transmit →
 * 按步骤 delayMs 等待；每步前置 Running(stepIndex) 供 UI 高亮；
 * 引用的设备/按键已删除 → Failed（中文原因）；发送失败跳过继续；stop() 取消执行。
 */
class MacroViewModel(application: Application) : AndroidViewModel(application) {

    /** 手动 DI 容器（依赖统一从容器获取） */
    private val container get() = (getApplication<PhotonApplication>()).container
    private val repository get() = container.repository
    private val codeResolver get() = container.codeResolver
    private val dispatcher get() = container.irDispatcher
    private val transmitter get() = container.transmitterManager

    /** 宏列表（Room Flow，按 sortOrder 升序） */
    val macros: Flow<List<Macro>> = repository.macros

    /** 设备列表（编辑页设备下拉用） */
    val devices: Flow<List<Device>> = repository.devices

    /** 宏摘要列表（名称 + 步骤摘要文本，列表卡片渲染用） */
    private val _summaries = MutableStateFlow<List<MacroSummary>>(emptyList())
    val summaries: StateFlow<List<MacroSummary>> = _summaries.asStateFlow()

    /** 宏执行状态（Idle / Running / Done / Failed） */
    private val _execState = MutableStateFlow<MacroExecState>(MacroExecState.Idle)
    val execState: StateFlow<MacroExecState> = _execState.asStateFlow()

    /** 正在执行的宏 id（列表页执行中卡片高亮用；空闲为 null） */
    private val _executingMacroId = MutableStateFlow<Long?>(null)
    val executingMacroId: StateFlow<Long?> = _executingMacroId.asStateFlow()

    /** 当前执行任务（stop() 取消用） */
    private var execJob: Job? = null

    init {
        // 宏列表 → 摘要（每步"设备名 → 按键 label"，步骤间以" → "连接）
        viewModelScope.launch {
            repository.macros.collect { macros ->
                val devices = repository.devices.first()
                val nameOf = devices.associate { it.id to it.name }
                val buttonOf = devices.associate { d ->
                    d.id to runCatching { repository.getButtons(d.id) }
                        .getOrDefault(emptyList())
                        .associate { it.id to it.label }
                }
                _summaries.value = macros.map { m ->
                    val steps = parseSteps(m.stepsJson)
                    val text = if (steps.isEmpty()) "空宏（无步骤）"
                    else steps.joinToString(" → ") { s ->
                        val d = nameOf[s.deviceId] ?: "已删除设备"
                        val b = buttonOf[s.deviceId]?.get(s.buttonId) ?: "已删除按键"
                        "$d → $b"
                    }
                    MacroSummary(m, steps, text)
                }
            }
        }
    }

    // ---------- 宏 CRUD ----------

    /** 删除宏 */
    fun deleteMacro(macro: Macro) {
        viewModelScope.launch { repository.deleteMacro(macro) }
    }

    /**
     * 新增/编辑宏：existingId > 0 为编辑（保留 id/sortOrder/createdAt），否则新建；
     * 写库成功后回调 onSaved（编辑页据此返回列表页）。
     */
    fun saveMacro(
        name: String,
        steps: List<MacroStep>,
        existingId: Long? = null,
        onSaved: () -> Unit = {},
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val json = MacroStep.codec.encodeToString(steps)
            if (existingId != null && existingId > 0) {
                repository.getMacro(existingId)?.let {
                    repository.updateMacro(it.copy(name = trimmed, stepsJson = json))
                }
            } else {
                repository.addMacro(Macro(name = trimmed, stepsJson = json))
            }
            onSaved()
        }
    }

    /** 读取某设备全部按键（编辑页按键下拉用） */
    suspend fun buttonsOf(deviceId: Long): List<RemoteButton> = repository.getButtons(deviceId)

    // ---------- 执行 ----------

    /**
     * 执行宏：逐步骤 设备/按键解析 → CodeResolver.resolveOneShot（一次性
     * open→decode→close 自包含）→ IrDispatcher 串行 transmit → 按步骤 delayMs 等待。
     * 当前步骤通过 Running(stepIndex) 高亮；步骤引用的设备/按键已删除 → Failed；
     * 发送失败（设备无响应等）跳过继续；全部完成 → Done。已有宏执行中时忽略本次调用。
     */
    fun executeMacro(macro: Macro) {
        if (execJob?.isActive == true) return   // 已有宏执行中，忽略
        val steps = parseSteps(macro.stepsJson)
        if (steps.isEmpty()) {
            _execState.value = MacroExecState.Failed("该宏没有可执行的步骤")
            return
        }
        _executingMacroId.value = macro.id
        _execState.value = MacroExecState.Running(0)
        execJob = viewModelScope.launch {
            try {
                // 一次性加载设备与按键映射（避免每步查库）
                val devices = repository.devices.first().associateBy { it.id }
                val buttons = devices.keys.associateWith { deviceId ->
                    runCatching { repository.getButtons(deviceId) }
                        .getOrDefault(emptyList())
                        .associateBy { it.id }
                }
                steps.forEachIndexed { index, step ->
                    _execState.value = MacroExecState.Running(index)
                    val device = devices[step.deviceId]
                    val button = device?.let { buttons[it.id]?.get(step.buttonId) }
                    if (device == null || button == null) {
                        _execState.value =
                            MacroExecState.Failed("第 ${index + 1} 步引用的设备或按键已删除")
                        return@launch
                    }
                    // resolveOneShot + transmit 作为单个原子队列任务，与页面按键互不交错
                    dispatcher.onQueue {
                        codeResolver.resolveOneShot(device, button)
                            ?.let { transmitter.transmit(it) } ?: false
                    }
                    // 步骤延迟：上一步发送后的等待间隔（最后一步无需等待）
                    if (index < steps.lastIndex && step.delayMs > 0) delay(step.delayMs)
                }
                _execState.value = MacroExecState.Done
            } finally {
                _executingMacroId.value = null
            }
        }
    }

    /** 停止执行（取消当前执行任务，回到空闲） */
    fun stop() {
        execJob?.cancel()
        execJob = null
        _execState.value = MacroExecState.Idle
        _executingMacroId.value = null
    }

    /** 清除完成/失败提示（列表页结果横幅自动消失用） */
    fun clearExecState() {
        if (_execState.value is MacroExecState.Done ||
            _execState.value is MacroExecState.Failed
        ) {
            _execState.value = MacroExecState.Idle
        }
    }

    override fun onCleared() {
        execJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** 解析宏步骤 JSON（空串/损坏数据 → 空列表，不抛异常） */
        fun parseSteps(stepsJson: String): List<MacroStep> {
            if (stepsJson.isBlank()) return emptyList()
            return try {
                MacroStep.codec.decodeFromString<List<MacroStep>>(stepsJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** 宏摘要（列表卡片数据：宏 + 解析后的步骤 + 摘要文本） */
data class MacroSummary(
    val macro: Macro,
    val steps: List<MacroStep>,
    val summary: String,
)
