package com.photon.remote.ui.macro

/**
 * 宏执行状态（计划 §5.9 / Todo 33）。
 *
 *  - Idle        空闲（未执行）
 *  - Running     执行中（stepIndex = 当前步骤下标，列表页高亮当前卡片用）
 *  - Done        全部步骤执行完成
 *  - Failed      失败（message 为中文原因，如步骤引用的设备/按键已删除）
 */
sealed interface MacroExecState {
    data object Idle : MacroExecState
    data class Running(val stepIndex: Int) : MacroExecState
    data object Done : MacroExecState
    data class Failed(val message: String) : MacroExecState
}

/** 中文提示文案（列表页状态徽标 / 结果横幅展示用） */
val MacroExecState.hint: String
    get() = when (this) {
        MacroExecState.Idle -> "空闲"
        is MacroExecState.Running -> "执行中（第 ${stepIndex + 1} 步）"
        MacroExecState.Done -> "执行完成"
        is MacroExecState.Failed -> message
    }
