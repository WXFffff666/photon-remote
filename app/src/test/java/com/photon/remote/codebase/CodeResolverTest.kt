package com.photon.remote.codebase

import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.toJson
import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.PressKind
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.IrextDecoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * CodeResolver 单元测试（计划 §4.3 / Todo 21 验收）。
 *
 * 测试策略：
 *  - IrextBinaryStore / IrdbCsvParser / 编码器用 Mockito mock；
 *  - IrextDecoder 为 Kotlin 单例对象（不可 mock 替换），直接用真实对象——
 *    JVM 无 libirdecode.so（isAvailable=false），open 恒失败、decode 恒 null，
 *    恰好覆盖"open 失败"路径；会话状态 currentOpenRef 用反射注入以覆盖
 *    快速路径与规则 d（恢复）路径。
 *  - 验收点：三种 ButtonAction 均能 resolve；open 失败路径也恢复页面会话
 *    （规则 d 无条件恢复）；同设备 one-shot 快速路径不破坏会话。
 */
class CodeResolverTest {

    private val store = mock(IrextBinaryStore::class.java)
    private val parser = mock(IrdbCsvParser::class.java)
    private val necEncoder = mock(IrProtocolEncoder::class.java)
    private val encoders = mapOf(ProtocolType.NEC to necEncoder)

    private val resolver = CodeResolver(
        irextStore = store,
        irextDecoder = IrextDecoder,
        irdbParser = parser,
        encoders = encoders,
        currentAcStatus = { null },   // 无 AC 状态 → 默认状态兜底
    )
    @After
    fun tearDown() {
        // 清空反射注入的会话状态，避免跨测试污染
        setCurrentOpenRef(null)
    }

    // ---------- 工具 ----------

    private fun device(codeRef: String) = Device(
        id = 1, name = "测试", type = DeviceType.STB, brand = "测试",
        codeSource = CodeSource.IREXT, codeRef = codeRef,
    )

    private fun button(action: ButtonAction): RemoteButton = RemoteButton(
        deviceId = 1, keyId = "POWER", label = "电源", actionJson = action.toJson(),
    )

    /** 反射注入 IrextDecoder.currentOpenRef（Kotlin 单例对象 private set，测试专用） */
    private fun setCurrentOpenRef(value: String?) {
        val field = IrextDecoder::class.java.getDeclaredField("currentOpenRef")
        field.isAccessible = true
        field.set(IrextDecoder, value)
    }

    private fun ref(bin: String, category: Int = 3) =
        IrextBinaryRef(binaryName = bin, bytes = byteArrayOf(1, 2, 3), category = category, subCate = 1)

    // ---------- resolve：页面路径 ----------

    @Test
    fun resolve_SendRaw直通波形() = runBlocking<Unit> {
        val d = device("t.bin")
        val b = button(ButtonAction.SendRaw(frequency = 38000, intervals = listOf(9000, 4500, 560)))
        val pattern = resolver.resolve(d, b)
        assertEquals(38000, pattern!!.frequency)
        assertArrayEquals(intArrayOf(9000, 4500, 560), pattern.intervals)
    }

    @Test
    fun resolve_SendProtocol走编码器() = runBlocking<Unit> {
        val pattern = IRPattern(38000, intArrayOf(1, 2))
        `when`(necEncoder.encode("E0E040BF", PressKind.NEW_PRESS)).thenReturn(pattern)
        val d = device("t.bin")
        val b = button(ButtonAction.SendProtocol(protocol = ProtocolType.NEC, hex = "E0E040BF"))
        assertEquals(pattern, resolver.resolve(d, b))
        verify(necEncoder).encode("E0E040BF", PressKind.NEW_PRESS)
    }

    @Test
    fun resolve_IrextKey未open返回null_isOpen守卫() = runBlocking<Unit> {
        // JVM 无 so：isOpen 恒 false → 守卫放行失败，返回 null（页面会话未 open 不发送）
        val d = device("t.bin")
        val b = button(ButtonAction.IrextKey(keyCode = IrextDecoder.APP_KEY_POWER, binaryRef = "t.bin"))
        assertNull(resolver.resolve(d, b))
    }

    // ---------- resolveOneShot：一次性路径（规则 b/c/d） ----------

    @Test
    fun resolveOneShot_同设备快速路径_不破坏会话() = runBlocking<Unit> {
        // 目标正是已 open 的会话：直接 decode，不 close、不重复 load（规则 b 快速路径）
        setCurrentOpenRef("t.bin")
        `when`(store.load("t.bin")).thenReturn(ref("t.bin"))
        val d = device("t.bin")
        val b = button(ButtonAction.IrextKey(keyCode = IrextDecoder.APP_KEY_POWER, binaryRef = "t.bin"))
        // JVM 无 so：decode 返回 null 属预期（快速路径命中，未触发 open/close/restore）
        assertNull(resolver.resolveOneShot(d, b))
        verify(store, times(1)).load("t.bin")   // 只 load 一次，不触发规则 d 的再次 load
        verify(store, never()).load("prev.bin")
    }

    @Test
    fun resolveOneShot_open失败_仍无条件恢复prev会话_规则d() = runBlocking<Unit> {
        // 页面会话 prev=prev.bin；one-shot 目标 target.bin：
        // open 失败（JVM 无 so 恒失败）→ 结果 null，但 prev 必须被重新 load+open（规则 d）
        setCurrentOpenRef("prev.bin")
        `when`(store.load("target.bin")).thenReturn(ref("target.bin"))
        `when`(store.load("prev.bin")).thenReturn(ref("prev.bin"))
        val d = device("target.bin")
        val b = button(ButtonAction.IrextKey(keyCode = IrextDecoder.APP_KEY_POWER, binaryRef = "target.bin"))
        assertNull(resolver.resolveOneShot(d, b))
        // 规则 d：无条件恢复 prev（open 失败也必须恢复）
        verify(store).load("prev.bin")
    }

    @Test
    fun resolveOneShot_无页面会话_不做恢复() = runBlocking<Unit> {
        // prev = null：one-shot 自包含，open 失败后不尝试恢复（规则 d 前提 prev != null）
        `when`(store.load("target.bin")).thenReturn(ref("target.bin"))
        val d = device("target.bin")
        val b = button(ButtonAction.IrextKey(keyCode = IrextDecoder.APP_KEY_POWER, binaryRef = "target.bin"))
        assertNull(resolver.resolveOneShot(d, b))
        verify(store, times(1)).load("target.bin")
    }

    @Test
    fun resolveOneShot_SendProtocol走编码器() = runBlocking<Unit> {
        val pattern = IRPattern(38000, intArrayOf(3, 4))
        `when`(necEncoder.encode("AABB", PressKind.NEW_PRESS)).thenReturn(pattern)
        val d = device("t.bin")
        val b = button(ButtonAction.SendProtocol(protocol = ProtocolType.NEC, hex = "AABB"))
        assertEquals(pattern, resolver.resolveOneShot(d, b))
        verify(necEncoder).encode("AABB", PressKind.NEW_PRESS)
    }
}
