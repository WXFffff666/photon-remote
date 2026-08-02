package com.photon.remote.ir.transmitter

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.photon.remote.data.local.SettingsStore
import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发射路径管理器单元测试（计划 §3.4 / Todo 13 验收：路由优先级）。
 *
 * 用内存文件 DataStore（真实 SettingsStore，JVM 可运行）+ 记录调用顺序的 FakeTransmitter，
 * 验证：auto 模式 USB→内置→音频 优先级、手动指定优先 + 不可用降级、路径持久化读写、hasAnyTransmitter。
 */
class TransmitterManagerTest {

    private val pattern = IRPattern(38_000, intArrayOf(562, 562))

    /** 记录调用顺序的假发射器 */
    private class FakeTransmitter(val name: String, var available: Boolean = true) : IRTransmitter {
        override val displayName: String = name
        override val isAvailable: Boolean get() = available
        val calls = mutableListOf<IRPattern>()
        override fun transmit(pattern: IRPattern): Boolean {
            if (!available) return false
            calls += pattern
            return true
        }
    }

    /** 真实 SettingsStore（临时文件 DataStore）+ TransmitterManager */
    private fun newManager(
        usb: FakeTransmitter, consumer: FakeTransmitter, audio: FakeTransmitter
    ): TransmitterManager {
        val file = File.createTempFile("transmitter_test", ".preferences_pb")
        file.deleteOnExit()
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job()),
                produceFile = { file }
            )
        )
        return TransmitterManager(consumer, usb, audio, store)
    }

    @Test
    fun `auto 模式按 USB 内置 音频 优先级尝试`() = runBlocking {
        val usb = FakeTransmitter("usb"); val consumer = FakeTransmitter("consumer"); val audio = FakeTransmitter("audio")
        val m = newManager(usb, consumer, audio)

        // 全部可用：只用 USB（最高优先级）
        assertTrue(m.transmit(pattern))
        assertEquals(1, usb.calls.size); assertEquals(0, consumer.calls.size); assertEquals(0, audio.calls.size)

        // USB 不可用：降级内置
        usb.available = false
        assertTrue(m.transmit(pattern))
        assertEquals(1, consumer.calls.size); assertEquals(0, audio.calls.size)

        // 内置不可用：降级音频
        consumer.available = false
        assertTrue(m.transmit(pattern))
        assertEquals(1, audio.calls.size)

        // 全部不可用：返回 false
        audio.available = false
        assertFalse(m.transmit(pattern))
    }

    @Test
    fun `手动指定路径优先且不可用降级`() = runBlocking {
        val usb = FakeTransmitter("usb"); val consumer = FakeTransmitter("consumer"); val audio = FakeTransmitter("audio")
        val m = newManager(usb, consumer, audio)

        // 手动内置：只用内置
        m.setPath("builtin")
        assertTrue(m.transmit(pattern))
        assertEquals(1, consumer.calls.size); assertEquals(0, usb.calls.size)

        // 手动内置不可用：降级 USB
        consumer.available = false
        assertTrue(m.transmit(pattern))
        assertEquals(1, usb.calls.size); assertEquals(0, audio.calls.size)

        // 手动音频：优先音频（即使内置恢复可用也不走内置）
        m.setPath("audio")
        consumer.available = true
        assertTrue(m.transmit(pattern))
        assertEquals(1, audio.calls.size); assertEquals(1, consumer.calls.size)
    }

    @Test
    fun `路径持久化读写`() = runBlocking {
        val m = newManager(FakeTransmitter("u"), FakeTransmitter("c"), FakeTransmitter("a"))
        assertEquals("auto", m.currentPath())   // 默认 auto
        m.setPath("usb")
        assertEquals("usb", m.currentPath())
        m.setPath("auto")
        assertEquals("auto", m.currentPath())
    }

    @Test
    fun `hasAnyTransmitter 任一可用即为真`() = runBlocking {
        val usb = FakeTransmitter("usb"); val consumer = FakeTransmitter("consumer"); val audio = FakeTransmitter("audio")
        val m = newManager(usb, consumer, audio)

        assertTrue(m.hasAnyTransmitter())
        usb.available = false; consumer.available = false
        assertTrue(m.hasAnyTransmitter())   // 音频仍可用
        audio.available = false
        assertFalse(m.hasAnyTransmitter())  // 全不可用
    }
}
