package com.photon.remote.ir.core

/**
 * 按键按压语义：区分"新按压"与"长按重复"。
 *
 * 用于 RC5/RC6 翻转位与 NEC/JVC 重复帧（计划 §3.2）。
 */
enum class PressKind { NEW_PRESS, REPEAT }

/**
 * 协议编码器统一接口（计划 §3.2）。
 *
 * 每个协议一个实现类（位于 ir/protocol 目录、以 Encoder.kt 结尾），输入 hex 码串输出完整波形
 * （含前导 / 帧尾 / 补零 / 重复帧逻辑）。
 */
interface IrProtocolEncoder {
    /** 本编码器对应的协议类型 */
    val protocol: ProtocolType

    /**
     * 长按重复发码的默认间隔（毫秒）；null = 用全局默认 250ms。
     * 协议级覆盖：NEC/NECx1/NECx2=110（标准短重复帧 9000/2250+562）、JVC=110（短爆发重复帧）；其余 null→250。
     * RemoteKey 长按循环取值：encoder.repeatIntervalMs ?: 250
     */
    val repeatIntervalMs: Int? get() = null

    /**
     * 输入：hex 码串（16 进制，不带 0x），输出完整 IRPattern（含前导/帧尾/补零/重复帧逻辑）。
     * 非法字符抛 [IllegalArgumentException]（中文错误信息）。
     */
    fun encode(hex: String, press: PressKind = PressKind.NEW_PRESS): IRPattern
}
