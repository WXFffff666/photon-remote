package com.photon.remote.data.model

import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.ir.core.ProtocolType
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ButtonAction 序列化往返测试（计划 §2.3 / Todo 6）。
 *
 * 覆盖：三种子类型往返、全部 14 种协议名、非法 JSON 中文异常。
 */
class ButtonActionSerializationTest {

    @Test
    fun 原始波形_SendRaw_往返一致() {
        val action = ButtonAction.SendRaw(frequency = 38000, intervals = listOf(9000, 4500, 562, 1687, 562))
        val json = action.toJson()
        assertTrue(json.isNotBlank())
        val restored = ButtonActionJson.decodeFromString<ButtonAction>(json)
        assertEquals(action, restored)
    }

    @Test
    fun 协议发送_SendProtocol_全部14种协议往返一致() {
        ProtocolType.entries.forEach { protocol ->
            val action = ButtonAction.SendProtocol(protocol = protocol, hex = "00FF12ED")
            val json = action.toJson()
            val restored = ButtonActionJson.decodeFromString<ButtonAction>(json)
            assertEquals("协议 $protocol 往返失败", action, restored)
        }
    }

    @Test
    fun irext键_IrextKey_往返一致() {
        val action = ButtonAction.IrextKey(keyCode = 0, binaryRef = "legacy_import.bin")
        val json = action.toJson()
        val restored = ButtonActionJson.decodeFromString<ButtonAction>(json)
        assertEquals(action, restored)
    }

    @Test
    fun RemoteButton_action_反序列化成功() {
        val button = RemoteButton(
            deviceId = 1L,
            keyId = "POWER",
            label = "电源",
            actionJson = ButtonAction.SendProtocol(ProtocolType.NEC, "00FF12ED").toJson(),
        )
        assertEquals(ButtonAction.SendProtocol(ProtocolType.NEC, "00FF12ED"), button.action())
    }

    @Test
    fun RemoteButton_action_非法JSON_抛中文异常() {
        val button = RemoteButton(deviceId = 2L, keyId = "VOL_UP", label = "音量+", actionJson = "这不是JSON")
        try {
            button.action()
            fail("应当抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应含中文提示，实际：${e.message}", e.message!!.contains("JSON"))
            assertTrue("异常信息应含按键定位信息", e.message!!.contains("VOL_UP"))
        }
    }

    @Test
    fun RemoteButton_action_未知协议名_抛中文序列化异常() {
        val button = RemoteButton(
            deviceId = 3L,
            keyId = "CUSTOM_1",
            label = "自定义",
            actionJson = """{"type":"SendProtocol","protocol":"BOGUS_PROTOCOL","hex":"00FF"}""",
        )
        try {
            button.action()
            fail("应当抛出异常")
        } catch (e: SerializationException) {
            // ProtocolTypeSerializer 内部抛出的中文异常
            assertTrue(e.message!!.contains("未知协议类型"))
        } catch (e: IllegalArgumentException) {
            // action() 包装后的顶层消息应包含中文根因信息（调用方 UI 可见）
            assertTrue("顶层消息应含中文，实际：${e.message}", e.message!!.contains("未知协议类型"))
            // 异常链中部应包含自定义序列化器的中文 SerializationException
            val hasChineseRoot = generateSequence<Throwable>(e) { it.cause }
                .any { it is SerializationException && it.message!!.contains("未知协议类型") }
            assertTrue("异常链应含中文序列化异常", hasChineseRoot)
        }
    }

    @Test
    fun 序列化_json携带type判别字段() {
        val json = ButtonAction.SendRaw(38000, listOf(1, 2)).toJson()
        assertTrue(json.contains("\"type\""))
        assertTrue(json.contains("SendRaw"))
    }
}
