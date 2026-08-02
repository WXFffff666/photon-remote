package com.photon.remote.ir.transmitter

import com.photon.remote.ir.core.IRPattern

/**
 * 红外发射抽象（计划 §3.1）。
 *
 * 三种实现：
 *  - [ConsumerIrTransmitter]：内置红外（ConsumerIrManager）
 *  - [UsbIrTransmitter]：USB 红外外设（§3.4）
 *  - [AudioIrTransmitter]：音频转红外（§3.4，无红外手机也能用）
 *
 * 所有实现都是**同步**发送：会阻塞到整帧发完（内置红外 NEC 补零后最长 108.8ms），
 * 必须经 [IrDispatcher] 在后台队列中调用，严禁在主线程直接调用。
 */
interface IRTransmitter {
    /** 用户可见名："内置红外" / "USB 发射器" / "音频转红外" */
    val displayName: String

    /** 硬件可用性（USB 需已授权连接；内置需 hasIrEmitter） */
    val isAvailable: Boolean

    /** 同步发送，返回是否成功 */
    fun transmit(pattern: IRPattern): Boolean
}
