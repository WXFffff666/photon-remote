package com.photon.remote.codebase

import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.action
import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.ACStatusHelper
import com.photon.remote.ir.irext.IrextDecoder
import net.irext.decode.sdk.bean.ACStatus

/**
 * 码解析统一入口（计划 §4.3 / Todo 21）：按钮动作 → IRPattern 的唯一入口。
 *
 * 【IREXT open/close 归属规则】（resolve/resolveOneShot 均为 suspend，每个调用作为
 * IrDispatcher 的单个原子队列任务执行——open/decode/close/restore 序列不会被页面按键
 * 或其他 one-shot 交错；所有 JNI 调用均 try/catch，绝不崩溃）：
 *  a) 页面路径（遥控器/空调面板激活期间）：页面 ViewModel（RemoteViewModel /
 *     AcPanelViewModel）进入时 open 一次、退出时 close；按键走 [resolve]，只 decode，
 *     不 open/close。
 *  b) 一次性路径（宏 / 暴力找码 / 导入测试）：走 [resolveOneShot]，open→decode→close
 *     自包含。
 *  c) IrextDecoder.open() 幂等守卫：若已 open 则先 close 再 open（防止重复
 *     openBinary 泄漏原生状态）。
 *  d) 【状态恢复，无条件执行】[resolveOneShot] 执行前记录 prev = currentOpenRef；
 *     结束后若 prev != null 且 prev != 目标，则用 irextStore.load(prev) + open() 重新
 *     打开 prev——**无论本次 open 成功与否都必须恢复**，防止失败路径销毁页面会话
 *     导致返回后按键静默失败。
 *
 * 与计划 §4.3 的适配（签名差异见下方标注）：
 *  - IrextDecoder.open 实际签名为 open(refName, category, subCate, bytes)（骨架
 *    IrextDecoder 为 4 参），故 [IrextBinaryRef] 必须携带 category/subCate 并完整传入；
 *  - 默认 AC 状态用 [ACStatusHelper.defaultAppStatus()]（应用层语义正确默认值），
 *    而非裸 ACStatus()（那是原生层默认，power=关、temp=8℃ 索引，语义错乱）。
 */
class CodeResolver(
    private val irextStore: IrextBinaryStore,           // load() 返回 IrextBinaryRef（bytes + binaryName + category + subCate）
    private val irextDecoder: IrextDecoder,
    private val irdbParser: IrdbCsvParser,              // 预留：irdb 码组经 SendProtocol 路径使用
    private val encoders: Map<ProtocolType, IrProtocolEncoder>,
    /** 当前 AC 状态回调（应用层语义 ACStatus；由 AppContainer 的 ACStatusCache 提供，非 suspend） */
    private val currentAcStatus: (deviceId: Long) -> ACStatus?,
) {

    /**
     * 页面路径：假定目标 binary 已由页面 ViewModel open，直接 decode。
     * 返回 null = 码无效 / 未 open / 解码失败（绝不抛异常）。
     */
    suspend fun resolve(
        device: Device,
        button: RemoteButton,
        press: PressKind = PressKind.NEW_PRESS,
    ): IRPattern? {
        return when (val action = button.action()) {
            is ButtonAction.SendRaw -> IRPattern(action.frequency, action.intervals.toIntArray())
            is ButtonAction.SendProtocol -> encoders[action.protocol]?.encode(action.hex, press)
            is ButtonAction.IrextKey -> {
                // 规则 a：页面会话未 open 时放弃发送（守卫，避免解码污染会话状态）
                if (!irextDecoder.isOpen) return null
                irextDecoder.decode(action.keyCode, acStatusFor(device))
            }
        }
    }

    /**
     * 一次性路径：open→decode→close 自包含；遵守规则 (d)：**无论 open 成败**
     * 都恢复先前的 open 会话（防止失败路径销毁页面会话）。
     */
    suspend fun resolveOneShot(
        device: Device,
        button: RemoteButton,
        press: PressKind = PressKind.NEW_PRESS,
    ): IRPattern? {
        return when (val action = button.action()) {
            is ButtonAction.SendRaw -> resolve(device, button, press)
            is ButtonAction.SendProtocol -> resolve(device, button, press)
            is ButtonAction.IrextKey -> {
                val ref = irextStore.load(device.codeRef) ?: return null
                val prev = irextDecoder.currentOpenRef
                if (prev != null && prev == ref.binaryName) {
                    // 目标正是已 open 的会话：直接 decode，不 close（同设备快速路径）
                    irextDecoder.decode(action.keyCode, acStatusFor(device))
                } else {
                    // 幂等守卫（规则 c）：IrextDecoder.open 内部已保证"已 open 先 close"
                    val opened = irextDecoder.open(
                        ref.binaryName, ref.category, ref.subCate, ref.bytes,
                    )
                    val result = if (opened) {
                        irextDecoder.decode(action.keyCode, acStatusFor(device))
                            .also { irextDecoder.close() }
                    } else null
                    // 规则 d：无条件恢复（open 失败也必须恢复，见类注释）
                    if (prev != null && prev != ref.binaryName) {
                        irextStore.load(prev)?.let {
                            irextDecoder.open(it.binaryName, it.category, it.subCate, it.bytes)
                        }
                    }
                    result
                }
            }
        }
    }

    /**
     * 取当前 AC 状态：回调无记录时用应用层默认状态
     * （[ACStatusHelper.defaultAppStatus()]，非 AC 设备同样用默认状态）。
     */
    private fun acStatusFor(device: Device): ACStatus =
        currentAcStatus(device.id) ?: ACStatusHelper.defaultAppStatus()
}
