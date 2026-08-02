package com.photon.remote.ir.transmitter

import com.photon.remote.data.local.SettingsStore
import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.flow.first

/**
 * 发射路径管理器（计划 §3.4 + 决策 D8）。
 *
 * 路由优先级：USB → 内置 → 音频（auto 推荐模式）；手动指定时优先指定路径，
 * 指定路径不可用时**降级**尝试其余路径（防止用户选了 USB 但 dongle 未插导致发送全部失败）。
 *
 * 路径取值与 SettingsStore.transmitterPath 一致：auto / builtin / usb / audio（默认 auto）。
 * 本类只负责"路由"，串行化由 [IrDispatcher] 承担（AppContainer 中注入 `dispatcher = IrDispatcher(manager::transmit)`，
 * UI 一律调用 dispatcher.send()）。
 *
 * 无任何可用发射器时 UI 禁用发送并提示（[hasAnyTransmitter] == false）。
 */
class TransmitterManager(
    private val consumerIr: IRTransmitter,   // 内置红外
    private val usbIr: IRTransmitter,        // USB 外设
    private val audioIr: IRTransmitter,      // 音频转红外
    private val settingsStore: SettingsStore // 发射路径持久化（复用 Todo 7 的 transmitterPath 字段）
) {

    /** 当前发射路径设置（auto / builtin / usb / audio） */
    suspend fun currentPath(): String = settingsStore.transmitterPath.first()

    /** 设置发射路径（auto / builtin / usb / audio），持久化到 DataStore */
    suspend fun setPath(path: String) = settingsStore.setTransmitterPath(path)

    /**
     * 按当前路径设置尝试发送；全部不可用 / 全部失败时返回 false。
     * 由 IrDispatcher 在后台队列中调用（本方法为 suspend，可直接注入队列）。
     */
    suspend fun transmit(pattern: IRPattern): Boolean {
        val chain = when (settingsStore.transmitterPath.first()) {
            "usb" -> listOf(usbIr, consumerIr, audioIr)      // 手动 USB：优先 USB，不可用降级
            "builtin" -> listOf(consumerIr, usbIr, audioIr)  // 手动内置：优先内置，降级
            "audio" -> listOf(audioIr, usbIr, consumerIr)    // 手动音频：优先音频，降级
            else -> listOf(usbIr, consumerIr, audioIr)       // auto（默认）：USB→内置→音频（D8）
        }
        for (t in chain) {
            if (t.isAvailable && t.transmit(pattern)) return true
        }
        return false
    }

    /** 任一发射器可用（供 UI 判断是否禁用发送按钮） */
    fun hasAnyTransmitter(): Boolean =
        consumerIr.isAvailable || usbIr.isAvailable || audioIr.isAvailable
}
