package com.photon.remote.codebase

import android.content.Context
import com.photon.remote.ir.core.ProtocolType

/**
 * irdb CSV 码记录（格式：functionname,protocol,device,subdevice,function）。
 *
 * [mappedProtocol] 为 [protocol] 字符串映射到本 App 协议枚举的结果；
 * **null = UNKNOWN**（ProtocolType 枚举无 UNKNOWN 值，不可修改，故以 null 表示
 * "映射不了"——调用方按原始字段降级处理，如转 RAW 或忽略）。
 */
data class IrdbCode(
    /** 功能名（如 "POWER ON" / "VOLUME+"） */
    val functionName: String,
    /** irdb 协议名原文（如 "NECx2"） */
    val protocol: String,
    /** 设备码（irdb 原始字符串，如 "7"；多值如 "7,7" 场景见 device/subdevice 拆分） */
    val device: String,
    /** 子设备码 */
    val subdevice: String,
    /** 功能码（十进制字符串） */
    val function: String,
    /** 映射后的本 App 协议；null = 映射不了（UNKNOWN） */
    val mappedProtocol: ProtocolType?,
)

/**
 * irdb CSV 解析器（计划 §4.2 / Todo 20）。
 *
 * 解析 assets/irdb/<品牌>/<设备类型>/<型号>.csv（保持 irdb 原样格式：
 * functionname,protocol,device,subdevice,function），按品牌/类型/型号列出码组。
 * 协议名 → [ProtocolType] 映射表见 [mapProtocol]（映射不了的标 null=UNKNOWN）。
 *
 * 全部 IO/解析异常一律不抛出（坏行跳过），返回空列表，绝不崩溃。
 */
class IrdbCsvParser(private val context: Context) {

    // ---------- 品牌/类型/型号列举（assets 目录结构） ----------

    /** 全部内置品牌（assets/irdb/ 下的一级目录） */
    fun listBrands(): List<String> = listDirs(ASSET_ROOT)

    /** 某品牌下的设备类型目录（tv / ac / audio / stb …） */
    fun listTypes(brand: String): List<String> = listDirs("$ASSET_ROOT/$brand")

    /** 某品牌某类型下的码组 CSV 文件（去掉 .csv 后缀，即型号/码组名，如 "7,7"） */
    fun listModels(brand: String, type: String): List<String> =
        listDir("$ASSET_ROOT/$brand/$type").filter { it.endsWith(".csv") }
            .map { it.removeSuffix(".csv") }

    // ---------- 码组解析 ----------

    /**
     * 解析单个码组 CSV。
     *
     * @param model 型号/码组名（如 "7,7"）
     * @return 全部有效行；文件缺失/坏行一律跳过，绝不崩溃
     */
    fun codes(brand: String, type: String, model: String): List<IrdbCode> {
        val path = "$ASSET_ROOT/$brand/$type/$model.csv"
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
                parseLines(reader.readLines())
            }
        } catch (e: Exception) {
            emptyList()   // 文件缺失/IO 异常：空列表，绝不崩溃
        }
    }

    /**
     * 合并某品牌某类型下全部码组（测试码 / 暴力找码遍历用）。
     * 按目录内文件顺序拼接，重复键由调用方自行去重。
     */
    fun allCodes(brand: String, type: String): List<IrdbCode> =
        listModels(brand, type).flatMap { codes(brand, type, it) }

    // ---------- 协议映射 ----------

    /**
     * irdb 协议名 → 本 App [ProtocolType]。
     *
     * 匹配规则：去空白 + 忽略大小写。映射不了的返回 null（= UNKNOWN）。
     * 映射表依据 irdb 实际 CSV（assets 内 1024 个文件统计出的协议名）与本 App
     * 14 种协议编码器（ir/protocol）对齐，见 [PROTOCOL_MAP] 注释。
     */
    fun mapProtocol(raw: String): ProtocolType? =
        PROTOCOL_MAP[raw.trim().uppercase()]

    // ---------- 内部 ----------

    private fun listDir(path: String): List<String> = try {
        context.assets.list(path)?.filter { it.isNotEmpty() }?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()   // 目录缺失：空列表，绝不崩溃
    }

    /**
     * 列出一级子目录（兼容 Robolectric 与真机两种 assets.list 行为）：
     *  - Robolectric：list() 返回**拍平路径**（如 "Samsung/tv/7,7.csv"），目录只作为
     *    文件路径前缀出现 → 取一级前缀去重；
     *  - 真机：list() 返回目录名/文件名，目录名无后缀 → 排除已知文件（.csv /
     *    manifest.json）后即目录。
     */
    private fun listDirs(parent: String): List<String> {
        val entries = try {
            context.assets.list(parent)?.filter { it.isNotEmpty() } ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()   // 目录缺失：空列表，绝不崩溃
        }
        val flattened = entries.filter { it.contains('/') }
        if (flattened.isNotEmpty()) {
            // 拍平模式（Robolectric）：目录 = 一级路径前缀
            return flattened.map { it.substringBefore('/') }.distinct().sorted()
        }
        // 真机模式：目录名无 .csv 后缀且非 manifest.json
        return entries.filter { !it.endsWith(".csv") && it != "manifest.json" }
            .map { it.trimEnd('/') }.distinct().sorted()
    }

    private fun parseLines(lines: List<String>): List<IrdbCode> {
        val result = mutableListOf<IrdbCode>()
        var first = true
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (first) {
                first = false
                // 跳过表头（functionname,protocol,device,subdevice,function）
                if (line.startsWith("functionname")) continue
            }
            val fields = line.split(',')
            if (fields.size < 5) continue   // 坏行跳过，绝不崩溃
            val functionName = fields[0].trim()
            val protocol = fields[1].trim()
            val device = fields[2].trim()
            val subdevice = fields[3].trim()
            val function = fields[4].trim()
            result += IrdbCode(
                functionName = functionName,
                protocol = protocol,
                device = device,
                subdevice = subdevice,
                function = function,
                mappedProtocol = mapProtocol(protocol),
            )
        }
        return result
    }

    private companion object {
        /** assets 内 irdb 根目录（计划 §1） */
        const val ASSET_ROOT = "irdb"

        /**
         * irdb 协议名 → 本 App 协议（键全大写；仅收录参数与本 App 编码器一致的协议，
         * 拿不准的一律不映射 → null=UNKNOWN，宁缺毋滥）：
         *   - NEC 家族：NEC/NEC1 → NEC；NECx1 → NECX1；NECx2 → NECX2
         *     （NEC1-f16/y1/y2/y3、NEC2、48-NEC1 等变体地址位长与本 App 32bit 编码器
         *     不一致，标 UNKNOWN，避免错码）
         *   - 三星：SAMSUNG32/Samsung36 → SAMSUNG32（irdb 的 "Samsung36" 即 32bit
         *     三星主码，参数一致）；Samsung20 为 20bit 老码 → UNKNOWN
         *   - 索尼：SONY/Sony12 → SONY12；Sony15 → SONY15；Sony20 → SONY20
         *   - 飞利浦：RC5 → RC5；RC6 → RC6
         *   - 夏普：Sharp/Sharp{1}/Sharp{2} → SHARP
         *   - JVC：JVC/JVC{2} → JVC（JVC-48 为 48bit 变体 → UNKNOWN）
         *   - 松下：Panasonic/Panasonic2/Panasonic_Old → KASEIKYO（Kaseikyo 即松下协议）
         *   - 先锋：Pioneer → PIONEER
         *   - 其余（RCA-38/Thomson/Zenith/Tivo 等）→ UNKNOWN
         */
        val PROTOCOL_MAP: Map<String, ProtocolType> = buildMap {
            put("NEC", ProtocolType.NEC)
            put("NEC1", ProtocolType.NEC)
            put("NECX1", ProtocolType.NECX1)
            put("NECX2", ProtocolType.NECX2)
            put("SAMSUNG32", ProtocolType.SAMSUNG32)
            put("SAMSUNG36", ProtocolType.SAMSUNG32)
            put("SONY", ProtocolType.SONY12)
            put("SONY12", ProtocolType.SONY12)
            put("SONY15", ProtocolType.SONY15)
            put("SONY20", ProtocolType.SONY20)
            put("RC5", ProtocolType.RC5)
            put("RC6", ProtocolType.RC6)
            put("SHARP", ProtocolType.SHARP)
            put("SHARP{1}", ProtocolType.SHARP)
            put("SHARP{2}", ProtocolType.SHARP)
            put("JVC", ProtocolType.JVC)
            put("JVC{2}", ProtocolType.JVC)
            put("PANASONIC", ProtocolType.KASEIKYO)
            put("PANASONIC2", ProtocolType.KASEIKYO)
            put("PANASONIC_OLD", ProtocolType.KASEIKYO)
            put("PIONEER", ProtocolType.PIONEER)
        }
    }
}
