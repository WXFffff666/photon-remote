package com.photon.remote.ir.transmitter

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Vibrator
import com.photon.remote.ir.core.IRPattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * 内置红外发射器单元测试（计划 §3.1 / Todo 10 验收，无真机环境的验证方式）。
 *
 * ConsumerIrManager / Vibrator / Context 为 Android 框架类，本地 JVM 单测用 mockito mock
 * （android.jar 桩类可正常 mock；Build.VERSION.SDK_INT 单测环境为 0 → 走 deprecated vibrate(long) 分支）。
 */
class ConsumerIrTransmitterTest {

    private val pattern = IRPattern(38_000, intArrayOf(9000, 4500, 562, 1687, 562, 562, 562, 1687, 562, 90000))

    @Test
    fun `无 ConsumerIrManager 时不可用且发送失败`() {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.CONSUMER_IR_SERVICE)).thenReturn(null)

        val tx = ConsumerIrTransmitter(context, mock(Vibrator::class.java))
        assertFalse(tx.isAvailable)
        assertFalse(tx.transmit(pattern))
    }

    @Test
    fun `有红外时发送成功并震动反馈`() {
        val context = mock(Context::class.java)
        val manager = mock(ConsumerIrManager::class.java)
        `when`(manager.hasIrEmitter()).thenReturn(true)
        `when`(context.getSystemService(Context.CONSUMER_IR_SERVICE)).thenReturn(manager)
        val vibrator = mock(Vibrator::class.java)

        val tx = ConsumerIrTransmitter(context, vibrator)
        assertTrue(tx.isAvailable)
        assertTrue(tx.transmit(pattern))
        verify(manager).transmit(anyInt(), any())   // int[] 无内容相等，用匹配器
        // 单测环境 SDK_INT=0 → API<26 分支：deprecated vibrate(long)
        verify(vibrator).vibrate(30L)
    }

    @Test
    fun `无发射器时发送失败且不震动`() {
        val context = mock(Context::class.java)
        val manager = mock(ConsumerIrManager::class.java)
        `when`(manager.hasIrEmitter()).thenReturn(false)
        `when`(context.getSystemService(Context.CONSUMER_IR_SERVICE)).thenReturn(manager)
        val vibrator = mock(Vibrator::class.java)

        val tx = ConsumerIrTransmitter(context, vibrator)
        assertFalse(tx.isAvailable)
        assertFalse(tx.transmit(pattern))
        verifyNoInteractions(vibrator)
    }

    @Test
    fun `transmit 抛异常时返回 false`() {
        val context = mock(Context::class.java)
        val manager = mock(ConsumerIrManager::class.java)
        `when`(manager.hasIrEmitter()).thenReturn(true)
        doThrow(IllegalStateException("传输失败")).`when`(manager).transmit(anyInt(), any())
        `when`(context.getSystemService(Context.CONSUMER_IR_SERVICE)).thenReturn(manager)

        val tx = ConsumerIrTransmitter(context, mock(Vibrator::class.java))
        assertTrue(tx.isAvailable)
        assertFalse(tx.transmit(pattern))
    }
}
