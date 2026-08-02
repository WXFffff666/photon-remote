package com.photon.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.PhotonApplication
import com.photon.remote.codebase.update.UpdateMode
import com.photon.remote.codebase.update.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 码库更新 ViewModel（计划 Todo 50，设置页「码库更新」区）。
 *
 * 流程：检查更新（[checkForUpdate]）→ 发现新版本弹确认框（[available]）→
 * 确认后应用（[confirmUpdate]，全量/增量由 CodebaseUpdater 自动选择，下载中
 * 汇报进度）→ 成功/失败均以中文消息展示。更新成功后 CodebaseUpdater 内部已
 * 调用 IrextIndexLoader.reload() + IrextBinaryStore.clearCache()，向导/遥控器
 * 等查询方下次访问自动使用新缓存（列表无需额外刷新）。
 *
 * 无网络 / 更新失败 → [UpdateUiState.message] 展示可读原因，内置码库不受影响。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    /** 手动 DI 容器（依赖统一从容器获取，AndroidViewModel 模式） */
    private val container get() = (getApplication<PhotonApplication>()).container
    private val updater get() = container.codebaseUpdater

    /** UI 状态（设置页「码库更新」区 collectAsState 使用） */
    data class UpdateUiState(
        /** 内置 assets 码库版本（不可变，展示用） */
        val builtinVersion: String = "",
        /** 当前生效的本地码库版本（有缓存 = 更新版本；无缓存 = 内置版本） */
        val localVersion: String = "",
        /** 正在检查更新 */
        val checking: Boolean = false,
        /** 正在下载/校验/应用 */
        val applying: Boolean = false,
        /** 下载进度 0f~1f（应用阶段恒为 1f） */
        val progress: Float = 0f,
        /** 发现新版本（非 null 时弹确认对话框） */
        val available: UpdateResult.Available? = null,
        /** 最近一次操作结果消息（成功/失败中文文案） */
        val message: String? = null,
        /** message 是否为错误（影响文案颜色） */
        val messageIsError: Boolean = false,
    )

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        // 展示内置版本 + 当前生效版本（后台线程计算，不阻塞 UI）
        viewModelScope.launch {
            val builtin = updater.builtinVersion()
            val local = updater.localVersion()
            _state.update { it.copy(builtinVersion = builtin, localVersion = local) }
        }
    }

    /** 检查更新：有新版本 → 弹确认框；无新版本/失败 → 结果消息 */
    fun checkForUpdate() {
        val s = _state.value
        if (s.checking || s.applying) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, message = null) }
            when (val result = updater.checkForUpdate()) {
                is UpdateResult.Available -> {
                    _state.update { it.copy(checking = false, available = result, message = null) }
                }
                UpdateResult.UpToDate -> {
                    _state.update {
                        it.copy(
                            checking = false,
                            message = "已是最新版本（码库 v${it.localVersion}）",
                            messageIsError = false,
                        )
                    }
                }
                is UpdateResult.Failed -> {
                    _state.update { it.copy(checking = false, message = result.reason, messageIsError = true) }
                }
                is UpdateResult.Succeeded -> { /* 检查阶段不可能返回 Succeeded */ }
            }
        }
    }

    /** 确认更新：按 Available 携带的自动选择方式应用（下载 + 校验 + 合并 + 回滚保障） */
    fun confirmUpdate() {
        val avail = _state.value.available ?: return
        viewModelScope.launch {
            _state.update { it.copy(available = null, applying = true, progress = 0f, message = null) }
            val result = updater.downloadAndApply(avail.mode) { p ->
                _state.update { it.copy(progress = p) }
            }
            _state.update { s ->
                when (result) {
                    is UpdateResult.Succeeded -> s.copy(
                        applying = false,
                        progress = 1f,
                        localVersion = result.version,
                        message = "更新完成：码库已更新至 v${result.version}" +
                            "（${if (result.mode == UpdateMode.FULL) "全量更新" else "增量更新"}）",
                        messageIsError = false,
                    )
                    is UpdateResult.Failed -> s.copy(
                        applying = false,
                        message = result.reason,
                        messageIsError = true,
                    )
                    else -> s.copy(applying = false)
                }
            }
        }
    }

    /** 关闭「发现新版本」确认框（暂不更新） */
    fun dismissAvailable() {
        _state.update { it.copy(available = null) }
    }
}
