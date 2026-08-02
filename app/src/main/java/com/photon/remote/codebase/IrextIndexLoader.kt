package com.photon.remote.codebase

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---------- 索引 JSON 数据类（计划 §4.1 结构，字段与 assets/irext/irext-index.json 对齐） ----------

/**
 * IREXT 索引根节点（计划 §4.1 脚本产出）。
 *
 * 结构：categories[{id,name,nameEn,brands[{id,name,areas[{name,cities[{name,
 * operators[{operator,remotes[{id,name,bin}]}]}]}],remotes[{id,name,bin}]}]}]
 *  - 非 STB 品牌：areas 为空、remotes 直挂品牌下；
 *  - STB 品牌：remotes 为空，走 areas→cities→operators→remotes 四级链路。
 */
@Serializable
data class IrextIndexData(
    /** 码库版本（如 "20260519"），与 zip 根目录前缀 irext-binaries_<version>/ 对齐 */
    val version: String = "",
    /** 设备大类列表（id 与 irext Constants.CategoryID 一致：1=AC 2=TV 3=STB …） */
    val categories: List<IrextCategory> = emptyList(),
)

/** 设备大类（id = irext 原生大类 id） */
@Serializable
data class IrextCategory(
    val id: Int,
    /** 中文名（如 "电视"） */
    val name: String = "",
    /** 英文名（如 "TV"） */
    val nameEn: String = "",
    val brands: List<IrextBrand> = emptyList(),
)

/** 品牌 */
@Serializable
data class IrextBrand(
    val id: Int,
    val name: String = "",
    val nameEn: String = "",
    /** 省列表（仅 STB 品牌非空） */
    val areas: List<IrextArea> = emptyList(),
    /** 品牌直属运营商（当前数据一般为空，STB 运营商在 areas 链路内） */
    val operators: List<IrextOperator> = emptyList(),
    /** 品牌直属遥控器（非 STB 品牌使用；STB 品牌为空） */
    val remotes: List<IrextRemote> = emptyList(),
)

/** 省（JSON 中无 id，仅按 name 匹配） */
@Serializable
data class IrextArea(
    val name: String,
    val cities: List<IrextCity> = emptyList(),
)

/** 市 */
@Serializable
data class IrextCity(
    val name: String,
    val operators: List<IrextOperator> = emptyList(),
)

/** 运营商（如 "深圳天威华数" / "中国移动"） */
@Serializable
data class IrextOperator(
    val operator: String,
    val remotes: List<IrextRemote> = emptyList(),
)

/** 遥控器（码组）：id = remote_index id，bin = 二进制文件名（设备 codeRef 引用） */
@Serializable
data class IrextRemote(
    val id: Int,
    val name: String = "",
    val bin: String = "",
)

/**
 * IREXT 索引加载器（计划 §4.1 / Todo 18）。
 *
 * 读取 assets/irext/irext-index.json（约 0.75MB，16 个设备大类），以 kotlinx.serialization
 * 解析后常驻内存，提供品牌/省市/运营商/遥控器五级查询 API。
 *
 * 主构造器直接注入 JSON 字符串（纯 JVM 可测）；[IrextIndexLoader] 的应用入口
 * [IrextIndexLoader]（Context 重载）从 assets 读取。解析惰性执行（首次查询触发）。
 */
class IrextIndexLoader(private val json: String) {

    /** 应用入口：从 assets 读取索引 JSON */
    constructor(context: Context) : this(readAsset(context))

    /** 惰性解析 + 内存缓存（仅解析一次） */
    private val index: IrextIndexData by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(IrextIndexData.serializer(), json)
    }

    /** 索引版本号（IrextBinaryStore 定位 zip 根目录前缀用） */
    val version: String get() = index.version

    // ---------- 查询 API（计划 §4.1 / Todo 18 验收） ----------

    /** 全部设备大类（categories 非空 = 索引加载成功） */
    fun getCategories(): List<IrextCategory> = index.categories

    /** 按大类 id 查品牌列表（categoryId = irext Constants.CategoryID，如 TV=2 / STB=3 / AC=1） */
    fun getBrands(categoryId: Int): List<IrextBrand> =
        index.categories.firstOrNull { it.id == categoryId }?.brands ?: emptyList()

    /** 按品牌 id 查省列表（仅 STB 品牌非空） */
    fun getAreas(brandId: Int): List<IrextArea> =
        findBrand(brandId)?.areas ?: emptyList()

    /** 按 品牌 id + 省名 查城市列表（JSON 中 area 无 id，只能按名匹配） */
    fun getCities(brandId: Int, areaName: String): List<IrextCity> =
        findBrand(brandId)?.areas?.firstOrNull { it.name == areaName }?.cities ?: emptyList()

    /** 按 品牌 id + 省名 + 城市名 查运营商列表 */
    fun getOperators(brandId: Int, areaName: String, cityName: String): List<IrextOperator> =
        getCities(brandId, areaName).firstOrNull { it.name == cityName }?.operators ?: emptyList()

    /** 品牌直属遥控器（非 STB 品牌；STB 请用 getOperators(...) 后取 operator.remotes） */
    fun getRemotes(brandId: Int): List<IrextRemote> =
        findBrand(brandId)?.remotes ?: emptyList()

    /** 按遥控器 id 全局查找（遍历品牌直属 + 运营商链路全部记录） */
    fun findRemoteById(id: Int): IrextRemote? =
        allRemotes().firstOrNull { it.first.id == id }?.first

    /** 按 bin 文件名全局查找（IrextBinaryStore 解析元数据用） */
    fun findRemoteByBin(bin: String): IrextRemote? =
        allRemotes().firstOrNull { it.first.bin == bin }?.first

    /** 按 bin 文件名查所属设备大类（找不到返回 null） */
    fun findCategoryByBin(bin: String): IrextCategory? =
        allRemotes().firstOrNull { it.first.bin == bin }?.second

    /** 按 bin 文件名查所属大类 id */
    fun categoryIdOf(bin: String): Int? = findCategoryByBin(bin)?.id

    // ---------- 内部工具 ----------

    /** 按品牌 id 全局查找（品牌 id 在全部大类中唯一） */
    private fun findBrand(brandId: Int): IrextBrand? =
        index.categories.asSequence().flatMap { it.brands }.firstOrNull { it.id == brandId }

    /**
     * 全局遍历所有遥控器记录（品牌直属 + 运营商链路），同时带出所属大类。
     * 用 Sequence 惰性求值：find 命中即停，无需展开全部记录。
     */
    private fun allRemotes(): Sequence<Pair<IrextRemote, IrextCategory>> =
        index.categories.asSequence().flatMap { cat ->
            cat.brands.asSequence().flatMap { brand ->
                val direct = brand.remotes.asSequence().map { it to cat }
                val nested = brand.areas.asSequence().flatMap { area ->
                    area.cities.asSequence().flatMap { city ->
                        city.operators.asSequence().flatMap { op ->
                            op.remotes.asSequence().map { it to cat }
                        }
                    }
                }
                direct + nested
            }
        }

    private companion object {
        /** assets 内索引路径（计划 §1） */
        const val ASSET_PATH = "irext/irext-index.json"

        /** 读取 assets 索引全文（UTF-8；0.75MB 单次读入内存缓存） */
        private fun readAsset(context: Context): String =
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
