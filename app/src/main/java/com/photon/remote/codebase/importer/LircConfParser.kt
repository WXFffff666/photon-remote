package com.photon.remote.codebase.importer

import com.photon.remote.data.model.ButtonAction
import com.photon.remote.ir.core.ProtocolType

/**
 * LIRC 码库文件中的一个按键：keyId 为语义键标识（如 POWER），
 * label 为 LIRC 原始按键名（如 KEY_POWER），action 为发射动作。
 */
data class LircKey(
    val keyId: String,
    val label: String,
    val action: ButtonAction,
)

/**
 * LIRC .conf 解析器：解析 "begin remote ... name / bits / freq / ... KEY_POWER 0xE0E040BF ... end remote"。
 *
 * - 头部 protocol/bits 字段映射为 [ProtocolType]（NEC/32→NEC、SAMSUNG→SAMSUNG32、RC5→RC5、
 *   SONY→SONY12、JVC→JVC、PANASONIC→KASEIKYO），缺失时使用 [parse] 的 fallbackProtocol；
 * - 每条 "KEY_X 0xHEX" 行生成 [ButtonAction.SendProtocol]（hex 去掉 0x、小写转大写）；
 * - begin/end/name/remote 等非按键行与 # 注释行一律跳过。
 */
object LircConfParser {

    /** 按键行匹配：KEY_xxx 后跟 0x 十六进制码值 */
    private val keyLineRegex = Regex("^(KEY_\\S+)\\s+0x([0-9A-Fa-f]+)\\s*$")

    /**
     * 解析 LIRC .conf 文本，返回按键列表（保持文件中的出现顺序）。
     *
     * @param content LIRC .conf 文件全文
     * @param fallbackProtocol 头部无 protocol 字段（或无法识别）时使用的协议，默认 NEC
     */
    fun parse(content: String, fallbackProtocol: ProtocolType = ProtocolType.NEC): List<LircKey> {
        val lines = content.lineSequence().map { it.trim() }.toList()

        // 先整体扫描头部 protocol / bits 字段，与按键行解析解耦（兼容字段顺序不标准的 conf）
        var protocolName: String? = null
        var bits: Int? = null
        for (line in lines) {
            when {
                line.startsWith("protocol ", ignoreCase = true) ->
                    protocolName = line.substringAfter(' ').trim().takeIf { it.isNotEmpty() }

                line.startsWith("bits ", ignoreCase = true) ->
                    bits = line.substringAfter(' ').trim().toIntOrNull()
            }
        }
        val protocol = mapProtocol(protocolName, bits, fallbackProtocol)

        // 逐行解析按键
        val keys = mutableListOf<LircKey>()
        var customIndex = 0
        for (line in lines) {
            // 跳过空行与注释（# 或 ; 开头）
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
            val match = keyLineRegex.matchEntire(line) ?: continue
            val keyName = match.groupValues[1]
            val rawHex = match.groupValues[2]
            val keyId = mapKeyId(keyName) ?: "CUSTOM_${customIndex++}"
            keys += LircKey(
                keyId = keyId,
                label = keyName,
                action = ButtonAction.SendProtocol(protocol = protocol, hex = rawHex.uppercase()),
            )
        }
        return keys
    }

    /**
     * KEY 名 → keyId 映射表：KEY_POWER→POWER、KEY_VOLUMEUP→VOL_UP、KEY_VOLUMEDOWN→VOL_DOWN、
     * KEY_CHANNELUP→CH_UP、KEY_CHANNELDOWN→CH_DOWN、KEY_MUTE→MUTE、KEY_0..9→NUM_0..9、
     * KEY_OK/ENTER→OK、KEY_UP→UP、KEY_DOWN→DOWN、KEY_LEFT→LEFT、KEY_RIGHT→RIGHT、
     * KEY_BACK→BACK、KEY_MENU→MENU、KEY_SOURCE/INPUT→INPUT；未知返回 null（调用方赋 CUSTOM_<n>）。
     */
    private fun mapKeyId(keyName: String): String? {
        // 数字键 KEY_0..KEY_9 → NUM_0..NUM_9（"KEY_" 为 4 字符，整体长度 5）
        if (keyName.length == 5 && keyName.startsWith("KEY_") && keyName[4] in '0'..'9') {
            return "NUM_${keyName[4]}"
        }
        return when (keyName) {
            "KEY_POWER" -> "POWER"
            "KEY_VOLUMEUP" -> "VOL_UP"
            "KEY_VOLUMEDOWN" -> "VOL_DOWN"
            "KEY_CHANNELUP" -> "CH_UP"
            "KEY_CHANNELDOWN" -> "CH_DOWN"
            "KEY_MUTE" -> "MUTE"
            "KEY_OK", "KEY_ENTER" -> "OK"
            "KEY_UP" -> "UP"
            "KEY_DOWN" -> "DOWN"
            "KEY_LEFT" -> "LEFT"
            "KEY_RIGHT" -> "RIGHT"
            "KEY_BACK" -> "BACK"
            "KEY_MENU" -> "MENU"
            "KEY_SOURCE", "KEY_INPUT" -> "INPUT"
            else -> null
        }
    }

    /**
     * conf 头部 protocol/bits 字段 → 协议映射：
     * NEC/32→NEC、SAMSUNG→SAMSUNG32、RC5→RC5、SONY→SONY12、JVC→JVC、PANASONIC→KASEIKYO；
     * 其余（含 NEC 但 bits≠32、或完全缺失）回退到 [fallback]。
     */
    private fun mapProtocol(protocolName: String?, bits: Int?, fallback: ProtocolType): ProtocolType =
        when (protocolName?.uppercase()) {
            "NEC" -> if (bits == 32) ProtocolType.NEC else fallback
            "SAMSUNG" -> ProtocolType.SAMSUNG32
            "RC5" -> ProtocolType.RC5
            "SONY" -> ProtocolType.SONY12
            "JVC" -> ProtocolType.JVC
            "PANASONIC" -> ProtocolType.KASEIKYO
            else -> fallback
        }
}
