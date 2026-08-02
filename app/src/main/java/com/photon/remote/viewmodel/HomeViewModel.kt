package com.photon.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（计划 §5.3 / Todo 26）。
 *
 * 设备列表 = Repository.devices（Flow）+ 搜索词模糊过滤（品牌/型号/名称），
 * 以及重命名 / 删除 / 收藏切换 / 排序（移动到指定位置）操作。
 */
class HomeViewModel(private val repository: DeviceRepository) : ViewModel() {

    /** 搜索词（品牌/型号/设备名模糊过滤） */
    private val searchQuery = MutableStateFlow("")

    /**
     * 过滤后的设备列表（按 sortOrder 升序，来自 Repository.devices）。
     * 搜索词为空时返回全部设备。
     */
    val devices: StateFlow<List<Device>> =
        combine(repository.devices, searchQuery) { list, query ->
            if (query.isBlank()) list
            else list.filter { it.matches(query) }
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

    /** 收藏切换 */
    fun toggleFavorite(device: Device) {
        viewModelScope.launch { repository.setFavorite(device.id, !device.isFavorite) }
    }

    /** 移动到列表指定位置（0..n-1，排序持久化） */
    fun moveDevice(deviceId: Long, toIndex: Int) {
        viewModelScope.launch { repository.moveDevice(deviceId, toIndex) }
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
