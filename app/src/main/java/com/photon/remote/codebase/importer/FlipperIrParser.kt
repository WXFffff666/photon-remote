package com.photon.remote.codebase.importer

import com.photon.remote.data.model.ButtonAction
import com.photon.remote.ir.core.ProtocolType

/**
 * 单个解析出的红外信号：name 信号名，action 对应的按钮动作。
 */
data class ParsedSignal(val name: String, val action: ButtonAction)

/**
 * Flipper `.ir` 文件解析器（计划 §4.2 / 导入 Flipper 码库）。
 *
 * 文件结构：多个信号块，每块以 `name: xxx` 开头，后续为 `key: value` 字段行，
 * 块之间以 `#` 注释行或空行分隔。两种信号类型：
 *  - `type: raw`：`data:` 为空格分隔的微秒序列（交替 mark/space）→ [ButtonAction.SendRaw]
 *  - `type: parsed`：`protocol:`（NEC / RC5 / RC6 / SONY / SAMSUNG / ...）+ `address:` / `command:`
 *    字节序列（每字节 2 位 hex，空格分隔）→ [ButtonAction.SendProtocol]，hex 为 address 与 command
 *    去掉空格后拼接；协议无法映射或字段缺失 → 拒绝该条并跳过。
 *
 * 容错：逐信号解析，损坏条目（频率非数字、数据非数字、未知协议、缺字段）一律跳过，
 * 不中断后续信号；文件头（Filetype: / Version:）与注释行自动忽略。
 */
object FlipperIrParser {

    /** Flipper 协议名 → 本项目 [ProtocolType] 映射表；不在表内的协议视为无法映射，拒绝该条 */
    private val protocolMap = mapOf(
        "NEC" to ProtocolType.NEC,
        "NECext" to ProtocolType.NECX1,
        "NEC42" to ProtocolType.NECX2,
        "NEC42ext" to ProtocolType.NECX2,
        "RC5" to ProtocolType.RC5,
        "RC5X" to ProtocolType.RC5,
        "RC6" to ProtocolType.RC6,
        "SONY" to ProtocolType.SONY12,
        "SAMSUNG" to ProtocolType.SAMSUNG32,
        "SHARP" to ProtocolType.SHARP,
        "JVC" to ProtocolType.JVC,
        "KASEIKYO" to ProtocolType.KASEIKYO,
        "PIONEER" to ProtocolType.PIONEER,
    )

    /**
     * 解析 Flipper .ir 文件内容为信号列表；损坏条目跳过，不抛异常。
     *
     * @param content .ir 文件全文
     * @return 成功解析的信号列表（保持文件中的出现顺序）
     */
    fun parse(content: String): List<ParsedSignal> {
        val result = mutableListOf<ParsedSignal>()
        // 当前信号块的字段表；key 为小写字段名（name/type/frequency/data/protocol/address/command）
        var fields = mutableMapOf<String, String>()
        var inBlock = false

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue // 跳过空行与注释

            if (line.startsWith("name:", ignoreCase = true)) {
                // 遇到新信号块：先结算上一个块
                if (inBlock) parseBlock(fields)?.let { result.add(it) }
                fields = mutableMapOf()
                fields["name"] = line.substringAfter(':').trim()
                inBlock = true
            } else {
                // 普通字段行：首个冒号前为 key，其后为 value（data 值含空格，须整体保留）
                val idx = line.indexOf(':')
                if (idx > 0) fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        // 结算最后一个块
        if (inBlock) parseBlock(fields)?.let { result.add(it) }
        return result
    }

    /** 将一个信号块的字段解析为 [ParsedSignal]；任何环节损坏均返回 null（跳过） */
    private fun parseBlock(fields: Map<String, String>): ParsedSignal? {
        val name = fields["name"] ?: return null
        return when (fields["type"]) {
            "raw" -> parseRaw(name, fields)
            "parsed" -> parseParsed(name, fields)
            else -> null // 未知类型，跳过
        }
    }

    /** raw 型信号：frequency 载波频率 + data 空格分隔微秒序列 → SendRaw */
    private fun parseRaw(name: String, fields: Map<String, String>): ParsedSignal? {
        val frequency = fields["frequency"]?.toIntOrNull() ?: return null // 频率非数字 → 损坏
        val data = fields["data"] ?: return null
        val intervals = data.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        if (intervals.isEmpty()) return null // 无有效微秒数据 → 损坏
        return ParsedSignal(name, ButtonAction.SendRaw(frequency, intervals))
    }

    /** parsed 型信号：protocol 映射 + address/command 字节 hex 拼接 → SendProtocol */
    private fun parseParsed(name: String, fields: Map<String, String>): ParsedSignal? {
        val protocolName = fields["protocol"] ?: return null
        val protocol = protocolMap[protocolName] ?: return null // 无法映射 → 拒绝该条
        val address = fields["address"]?.takeIf { it.isNotBlank() } ?: return null
        val command = fields["command"]?.takeIf { it.isNotBlank() } ?: return null
        // 每字节 2 位 hex，去掉空格后拼接 address + command
        val hex = address.filterNot { it.isWhitespace() } + command.filterNot { it.isWhitespace() }
        if (hex.isEmpty() || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null // 非 hex → 损坏
        return ParsedSignal(name, ButtonAction.SendProtocol(protocol, hex))
    }
}
