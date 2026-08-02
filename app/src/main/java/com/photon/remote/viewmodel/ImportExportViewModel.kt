package com.photon.remote.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.PhotonApplication
import com.photon.remote.codebase.importer.FlipperIrParser
import com.photon.remote.codebase.importer.ImportResult
import com.photon.remote.codebase.importer.JsonBackup
import com.photon.remote.codebase.importer.LircConfParser
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.MacroStep
import com.photon.remote.data.model.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 导入/导出结果状态（Todo 34 页面状态机）：
 * - [Idle] 空闲；[Done] 导入完成（摘要 + 跳过明细）；[Failed] 整体失败（中文原因）；
 * - [ConfirmOverwrite] JSON 备份解析成功，等待用户确认"全量替换"。
 */
sealed interface ImportState {
    data object Idle : ImportState
    data class Done(val title: String, val summary: String, val skipped: List<String>) : ImportState
    data class Failed(val title: String, val reason: String) : ImportState
    data class ConfirmOverwrite(val title: String, val summary: String, val skipped: List<String>) : ImportState
}

/**
 * 导入导出 ViewModel（计划 §4.5 / §5.9 / Todo 34）。
 *
 * 三个导入入口 + JSON 导出：
 * - Flipper .ir：FlipperIrParser.parse → 创建临时设备（DeviceType.OTHER + CodeSource.FLIPPER）
 *   + 按键（SendRaw / SendProtocol，ButtonAction.toJson()）；
 * - LIRC .conf：LircConfParser.parse → 临时设备（CodeSource.LIRC）+ 按键（keyId 已由解析器映射）；
 * - JSON 备份：JsonBackup.import（逐记录校验，跳过清单）→ 用户确认后**全量替换**；
 *   替换时设备/按键/宏的自增 id 会变化，宏步骤引用的 deviceId/buttonId 必须重映射（保持引用一致）；
 * - 导出：JsonBackup.export 全量序列化（devices + buttons + macros），由页面写文件。
 *
 * 文件选择（OpenDocument/CreateDocument）与 Uri 读写在页面层完成，本类只收内容字符串。
 */
class ImportExportViewModel(application: Application) : AndroidViewModel(application) {

    /** 手动 DI 容器（依赖统一从容器获取，AndroidViewModel 模式） */
    private val container get() = (getApplication<PhotonApplication>()).container
    private val repository get() = container.repository

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** 导入/导出进行中（页面禁用重复点击） */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 等待覆盖确认的备份结果（confirmOverwrite 使用） */
    private var pendingImport: ImportResult? = null

    // ---------- Uri 辅助（页面选择文件后调用） ----------

    /** 读取 Uri 全文为字符串（UTF-8）；失败返回 null（中文原因由页面提示） */
    suspend fun readText(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    /** 查询文件显示名（OpenableColumns.DISPLAY_NAME）；查询失败返回 null */
    suspend fun displayNameOf(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // ---------- Flipper .ir 导入 ----------

    fun importFlipper(content: String, fileDisplayName: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val signals = FlipperIrParser.parse(content)
                if (signals.isEmpty()) {
                    _state.value = ImportState.Failed(
                        "导入 Flipper .ir",
                        "文件中未解析到任何有效信号（需包含 type: raw 或 type: parsed 的信号块）",
                    )
                    return@launch
                }
                val deviceName = baseName(fileDisplayName) ?: "Flipper 导入设备"
                val deviceId = repository.addDevice(
                    Device(
                        name = deviceName,
                        type = DeviceType.OTHER,
                        brand = "Flipper",
                        model = fileDisplayName,
                        codeSource = CodeSource.FLIPPER,
                        codeRef = "flipper:import:${System.currentTimeMillis()}",
                        colorSeed = deviceName.hashCode().toLong(),
                    ),
                )
                val buttons = signals.mapIndexed { index, signal ->
                    RemoteButton(
                        deviceId = deviceId,
                        keyId = keyIdOf(signal.name) ?: "CUSTOM_$index",
                        label = signal.name.ifBlank { "信号 $index" },
                        actionJson = signal.action.toJson(),
                        order = index,
                    )
                }
                repository.addButtons(buttons)
                _state.value = ImportState.Done(
                    "导入 Flipper .ir",
                    "设备「$deviceName」创建成功，共导入 ${buttons.size} 个按键",
                    emptyList(),
                )
            } catch (e: Exception) {
                _state.value = ImportState.Failed("导入 Flipper .ir", e.message ?: "未知错误")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------- LIRC .conf 导入 ----------

    fun importLirc(content: String, fileDisplayName: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val keys = LircConfParser.parse(content)
                if (keys.isEmpty()) {
                    _state.value = ImportState.Failed(
                        "导入 LIRC .conf",
                        "文件中未解析到任何按键（需包含 KEY_xxx 0xHEX 格式的按键行）",
                    )
                    return@launch
                }
                val deviceName = baseName(fileDisplayName) ?: "LIRC 导入设备"
                val deviceId = repository.addDevice(
                    Device(
                        name = deviceName,
                        type = DeviceType.OTHER,
                        brand = "LIRC",
                        model = fileDisplayName,
                        codeSource = CodeSource.LIRC,
                        codeRef = "lirc:import:${System.currentTimeMillis()}",
                        colorSeed = deviceName.hashCode().toLong(),
                    ),
                )
                val buttons = keys.mapIndexed { index, key ->
                    RemoteButton(
                        deviceId = deviceId,
                        keyId = key.keyId,
                        label = key.label,
                        actionJson = key.action.toJson(),
                        order = index,
                    )
                }
                repository.addButtons(buttons)
                _state.value = ImportState.Done(
                    "导入 LIRC .conf",
                    "设备「$deviceName」创建成功，共导入 ${buttons.size} 个按键",
                    emptyList(),
                )
            } catch (e: Exception) {
                _state.value = ImportState.Failed("导入 LIRC .conf", e.message ?: "未知错误")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------- JSON 备份：解析 → 确认覆盖 → 全量替换 ----------

    /** 解析备份内容：成功进入确认覆盖状态（含跳过明细预览），整体失败进入 Failed */
    fun importJson(content: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = JsonBackup.import(content)
                pendingImport = result
                val summary = "成功解析：设备 ${result.devices.size} 台 / 按键 ${result.buttons.size} 个 / 宏 ${result.macros.size} 条" +
                    if (result.skipped.isEmpty()) "（无跳过）" else "；跳过/失败 ${result.skipped.size} 条"
                _state.value = ImportState.ConfirmOverwrite("导入 JSON 备份", summary, result.skipped)
            } catch (e: IllegalArgumentException) {
                _state.value = ImportState.Failed("导入 JSON 备份", e.message ?: "备份解析失败")
            } finally {
                _busy.value = false
            }
        }
    }

    /** 取消覆盖确认（回到空闲，不写库） */
    fun cancelOverwrite() {
        pendingImport = null
        _state.value = ImportState.Idle
    }

    /**
     * 确认覆盖：清空现有设备/宏 → 插入备份数据。
     *
     * 全量替换的引用一致性（计划 §4.5）：备份里的自增 id 与库内新 id 不同，
     * 故设备/按键插入时置 id=0 重新自增，并建立 旧id → 新id 映射；
     * 宏的 stepsJson 按映射重写 deviceId/buttonId，保证宏步骤仍指向正确设备与按键。
     */
    fun confirmOverwrite() {
        val result = pendingImport ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                // 1) 清空现有数据：先删宏（避免设备级联清理反复扫描），再删设备（外键级联按键）
                repository.macros.first().forEach { repository.deleteMacro(it) }
                repository.devices.first().forEach { repository.deleteDevice(it) }

                // 2) 设备：旧 id → 新 id 映射
                val deviceIdMap = mutableMapOf<Long, Long>()
                result.devices.forEach { d ->
                    deviceIdMap[d.id] = repository.addDevice(
                        d.copy(id = 0, createdAt = System.currentTimeMillis()),
                    )
                }

                // 3) 按键：deviceId 重映射，旧按键 id → 新 id 映射
                val buttonIdMap = mutableMapOf<Long, Long>()
                result.buttons.forEach { b ->
                    val newDeviceId = deviceIdMap[b.deviceId] ?: return@forEach
                    buttonIdMap[b.id] = repository.addButton(b.copy(id = 0, deviceId = newDeviceId))
                }

                // 4) 宏：stepsJson 内 deviceId/buttonId 重映射；无法映射的步骤丢弃（保留宏主体）
                result.macros.forEach { m ->
                    val steps = runCatching {
                        MacroStep.codec.decodeFromString<List<MacroStep>>(m.stepsJson)
                    }.getOrDefault(emptyList())
                    val remapped = steps.mapNotNull { s ->
                        val newDevice = deviceIdMap[s.deviceId] ?: return@mapNotNull null
                        val newButton = buttonIdMap[s.buttonId] ?: return@mapNotNull null
                        s.copy(deviceId = newDevice, buttonId = newButton)
                    }
                    repository.addMacro(
                        m.copy(
                            id = 0,
                            stepsJson = MacroStep.codec.encodeToString(remapped),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }

                pendingImport = null
                _state.value = ImportState.Done(
                    "导入 JSON 备份",
                    "已全量替换：设备 ${deviceIdMap.size} 台 / 按键 ${buttonIdMap.size} 个 / 宏 ${result.macros.size} 条",
                    result.skipped,
                )
            } catch (e: Exception) {
                _state.value = ImportState.Failed("导入 JSON 备份", "覆盖导入失败：${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------- JSON 导出 ----------

    /** 导出全量备份 JSON 字符串（页面经 CreateDocument 写文件）；失败返回 null */
    suspend fun exportJson(): String? = try {
        val devices = repository.devices.first()
        val buttons = devices.flatMap { repository.getButtons(it.id) }
        val macros = repository.macros.first()
        JsonBackup.export(devices, buttons, macros)
    } catch (e: Exception) {
        null
    }

    // ---------- UI 状态 ----------

    /** 关闭结果对话框（Done/Failed 回到空闲） */
    fun dismiss() {
        if (_state.value is ImportState.Done || _state.value is ImportState.Failed) {
            _state.value = ImportState.Idle
        }
    }

    /** 文件名去扩展名（"Samsung.ir" → "Samsung"）；空串返回 null */
    private fun baseName(fileName: String?): String? {
        val name = fileName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return name.substringBeforeLast('.', name).ifBlank { null }
    }

    /**
     * Flipper 信号名 → 语义 keyId（与 LircConfParser.mapKeyId 口径一致）：
     * POWER/VOL±/CH±/MUTE/OK/方向键/数字键；未知信号名返回 null（调用方赋 CUSTOM_<n>）。
     */
    private fun keyIdOf(signalName: String): String? {
        val n = signalName.trim().uppercase()
        return when (n) {
            "POWER" -> "POWER"
            "VOL+", "VOL_UP", "VOLUMEUP" -> "VOL_UP"
            "VOL-", "VOL_DOWN", "VOLUMEDOWN" -> "VOL_DOWN"
            "CH+", "CH_UP", "CHANNELUP" -> "CH_UP"
            "CH-", "CH_DOWN", "CHANNELDOWN" -> "CH_DOWN"
            "MUTE" -> "MUTE"
            "OK", "ENTER" -> "OK"
            "UP" -> "UP"
            "DOWN" -> "DOWN"
            "LEFT" -> "LEFT"
            "RIGHT" -> "RIGHT"
            "BACK" -> "BACK"
            "MENU" -> "MENU"
            "INPUT", "SOURCE" -> "INPUT"
            else -> n.takeIf { it.length == 1 && it[0] in '0'..'9' }?.let { "NUM_$it" }
        }
    }
}
