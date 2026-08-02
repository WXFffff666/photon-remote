package com.photon.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（计划 §5.3 / Todo 26 + Todo 38 收藏排序增强）。
 *
 * 设备列表 = Repository.devices（Flow）+ 搜索词模糊过滤（品牌/型号/名称）
 * + 收藏置顶排序（isFavorite DESC，组内 sortOrder 升序）；
 * 操作：重命名 / 删除 / 收藏切换 / 移动排序（顶部/上移/下移/底部）。
 */
class HomeViewModel(private val repository: DeviceRepository) : ViewModel() {

    /** 搜索词（品牌/型号/设备名模糊过滤） */
    private val searchQuery = MutableStateFlow("")

    /**
     * 过滤后的设备列表：收藏置顶（isFavorite DESC），组内按 sortOrder 升序
     * （Todo 38：收藏分组显示；排序经 Repository.moveDevice 持久化，重启保留）。
     */
    val devices: StateFlow<List<Device>> =
        combine(repository.devices, searchQuery) { list, query ->
            val filtered = if (query.isBlank()) list else list.filter { it.matches(query) }
            filtered.sortedWith(compareByDescending<Device> { it.isFavorite }.thenBy { it.sortOrder })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 更新搜索词 */
    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    /** 重命名设备 */
    fun renameDevice(deviceId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameDevice(deviceId, name.trim()) }
    }

    /** 删除设备（级联清理按键与宏步骤，见 DeviceRepository） */
    fun deleteDevice(device: Device) {
        viewModelScope.launch { repository.deleteDevice(device) }
    }

    /** 收藏切换（卡片星标 / 长按菜单，Todo 38 收藏置顶联动） */
    fun toggleFavorite(device: Device) {
        viewModelScope.launch { repository.setFavorite(device.id, !device.isFavorite) }
    }

    /** 移动到列表指定位置（0..n-1，排序持久化） */
    fun moveDevice(deviceId: Long, toIndex: Int) {
        viewModelScope.launch { repository.moveDevice(deviceId, toIndex) }
    }

    // ---------- 移动排序快捷操作（Todo 38：移到顶部/上移/下移/移到底部） ----------

    /** 移到列表顶部 */
    fun moveToTop(deviceId: Long) = moveDevice(deviceId, 0)

    /** 上移一位（按底层 sortOrder 顺序） */
    fun moveUp(deviceId: Long) {
        viewModelScope.launch {
            val list = repository.devices.first()
            val index = list.indexOfFirst { it.id == deviceId }
            if (index > 0) repository.moveDevice(deviceId, index - 1)
        }
    }

    /** 下移一位（按底层 sortOrder 顺序） */
    fun moveDown(deviceId: Long) {
        viewModelScope.launch {
            val list = repository.devices.first()
            val index = list.indexOfFirst { it.id == deviceId }
            if (index in 0 until list.lastIndex) repository.moveDevice(deviceId, index + 1)
        }
    }

    /** 移到列表末尾 */
    fun moveToBottom(deviceId: Long) {
        viewModelScope.launch {
            val list = repository.devices.first()
            if (list.size > 1) repository.moveDevice(deviceId, list.lastIndex)
        }
    }
}

/** 搜索匹配：设备名 / 品牌 / 型号（忽略大小写）任一命中即匹配 */
private fun Device.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return name.lowercase().contains(q) ||
        brand.lowercase().contains(q) ||
        (model?.lowercase()?.contains(q) == true)
}
