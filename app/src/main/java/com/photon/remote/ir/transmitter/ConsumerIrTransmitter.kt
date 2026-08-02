package com.photon.remote.ir.transmitter

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.photon.remote.ir.core.IRPattern

/**
 * 内置红外发射器（计划 §3.1，P0 核心）。
 *
 * 通过 ConsumerIrManager 发送；发送成功后轻震动反馈（30ms）。
 * 无红外机型（manager == null 或 hasIrEmitter == false）时 isAvailable = false。
 *
 * 注意：ConsumerIrManager.transmit 会阻塞至整帧发完（NEC 补零后最长 108.8ms），
 * 必须经 [IrDispatcher] 在后台队列中调用。
 */
class ConsumerIrTransmitter(
    private val context: Context,
    private val vibrator: Vibrator
) : IRTransmitter {

    // getSystemService(ConsumerIrManager::class.java)：null = 无红外（小米/华为等多数机型支持）
    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override val displayName: String = "内置红外"

    override val isAvailable: Boolean
        get() = manager?.hasIrEmitter() == true

    /** 发送：pattern.intervals 交替 mark/space 微秒，必须以 mark 开头、以 space 结尾（偶数长度） */
    override fun transmit(pattern: IRPattern): Boolean {
        val m = manager ?: return false
        if (!m.hasIrEmitter() || pattern.intervals.isEmpty()) return false
        return try {
            m.transmit(pattern.frequency, pattern.intervals)
            vibrate()   // 触感反馈（P0：轻震动）
            true
        } catch (e: Exception) { false }
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }
}
