package com.photon.remote.codebase.importer

import com.photon.remote.data.model.ButtonAction
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LircConfParser 单元测试：覆盖 keyId 映射、hex 归一化、协议推断与未知按键降级。
 */
class LircConfParserTest {

    /** 典型 Samsung NEC conf：头部含 name/bits/protocol/freq，按键区含 3 个已知键 + 1 个未知键 */
    private val samsungNecConf = """
        # 三星电视遥控码库（NEC 协议）
        begin remote

          name  Samsung_BN59-00978A
          bits           32
          protocol       NEC
          freq           38000

          header         342   171
          one            342   171
          zero           342   171

              begin codes
                  KEY_POWER             0xE0E040BF
                  KEY_MUTE              0xE0E0F00F
                  KEY_CHANNELUP         0xE0E048B7
                  KEY_SETUP             0xE0E0A05F
              end codes

        end remote
    """.trimIndent()

    @Test
    fun `解析典型 Samsung NEC conf - keyId 映射与协议正确`() {
        val keys = LircConfParser.parse(samsungNecConf)

        assertEquals(4, keys.size)

        // KEY_POWER → POWER，label 保留原始按键名，hex 归一化为大写
        val power = keys[0]
        assertEquals("POWER", power.keyId)
        assertEquals("KEY_POWER", power.label)
        val powerAction = power.action as ButtonAction.SendProtocol
        assertEquals(ProtocolType.NEC, powerAction.protocol)
        assertEquals("E0E040BF", powerAction.hex)

        // KEY_MUTE → MUTE
        assertEquals("MUTE", keys[1].keyId)
        assertEquals("E0E0F00F", (keys[1].action as ButtonAction.SendProtocol).hex)

        // KEY_CHANNELUP → CH_UP，协议仍为 NEC
        assertEquals("CH_UP", keys[2].keyId)
        assertEquals(ProtocolType.NEC, (keys[2].action as ButtonAction.SendProtocol).protocol)
    }

    @Test
    fun `未知 KEY 映射为 CUSTOM_n`() {
        val keys = LircConfParser.parse(samsungNecConf)

        val setup = keys[3]
        assertEquals("CUSTOM_0", setup.keyId)
        assertEquals("KEY_SETUP", setup.label)
        assertEquals("E0E0A05F", (setup.action as ButtonAction.SendProtocol).hex)
    }

    @Test
    fun `hex 小写自动转大写且去掉 0x`() {
        val conf = """
            begin remote
              protocol NEC
              bits 32
              begin codes
                KEY_POWER  0xe0e040bf
              end codes
            end remote
        """.trimIndent()

        val keys = LircConfParser.parse(conf)

        assertEquals(1, keys.size)
        val action = keys[0].action as ButtonAction.SendProtocol
        assertEquals("E0E040BF", action.hex)
        assertEquals(ProtocolType.NEC, action.protocol)
    }

    @Test
    fun `无 protocol 字段时使用 fallbackProtocol`() {
        val conf = """
            begin remote
              name  Generic_Remote
              bits 32
              begin codes
                KEY_VOLUMEUP  0x00FF40BF
              end codes
            end remote
        """.trimIndent()

        // 未传 fallback：默认 NEC
        assertEquals(
            ProtocolType.NEC,
            (LircConfParser.parse(conf)[0].action as ButtonAction.SendProtocol).protocol,
        )
        // 显式 fallback：SAMSUNG32
        assertEquals(
            ProtocolType.SAMSUNG32,
            (LircConfParser.parse(conf, ProtocolType.SAMSUNG32)[0].action as ButtonAction.SendProtocol).protocol,
        )
    }

    @Test
    fun `头部协议映射 - SAMSUNG 到 SAMSUNG32`() {
        val conf = """
            begin remote
              protocol SAMSUNG
              bits 32
              begin codes
                KEY_POWER  0xE0E040BF
              end codes
            end remote
        """.trimIndent()

        val action = LircConfParser.parse(conf)[0].action as ButtonAction.SendProtocol
        assertEquals(ProtocolType.SAMSUNG32, action.protocol)
    }

    @Test
    fun `数字键映射为 NUM_0 到 NUM_9`() {
        val conf = """
            begin remote
              begin codes
                KEY_0  0x00000000
                KEY_9  0x00000009
              end codes
            end remote
        """.trimIndent()

        val keys = LircConfParser.parse(conf)
        assertEquals("NUM_0", keys[0].keyId)
        assertEquals("NUM_9", keys[1].keyId)
    }
}
