package com.photon.remote.codebase.location

/**
 * 地区名归一化与匹配工具（计划 Todo 49：定位功能）。
 *
 * 定位返回的省市名（Geocoder 的 adminArea/locality）与 IREXT 索引中的地区名
 * 存在后缀差异（如 "广东省" vs "广东"、"北京市" vs "北京"、"内蒙古自治区" vs
 * "内蒙古"），本工具先归一化再做精确/包含匹配。纯函数、无 Android 依赖，可单测。
 */
object AreaNameMatcher {

    /**
     * 省/市/自治区/特别行政区 后缀表（按长度降序，先去掉更长的复合后缀）。
     * 例：新疆维吾尔自治区 → 新疆；广西壮族自治区 → 广西；内蒙古自治区 → 内蒙古；
     *     香港特别行政区 → 香港；广东省 → 广东；深圳市 → 深圳。
     */
    private val SUFFIXES = listOf(
        "特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区", "自治区", "省", "市",
    )

    /**
     * 归一化地区名：去掉全部空白 + 去掉省市后缀。
     * @return 归一化后的名称（"广东省"→"广东"、"上海市"→"上海"、"上海"→"上海"）
     */
    fun normalize(name: String): String {
        val compact = name.filterNot { it.isWhitespace() }
        return SUFFIXES.firstOrNull { compact.endsWith(it) }
            ?.let { compact.dropLast(it.length) }
            ?: compact
    }

    /**
     * 在 IREXT 省名列表中匹配定位到的省：
     * 1. 先精确匹配；2. 再归一化后相等；3. 最后归一化双向包含（长度≥2 防单字误匹配）。
     * @return 命中的 irext 省名；未命中返回 null
     */
    fun matchArea(areas: List<String>, provinceName: String): String? =
        matchIn(areas, provinceName)

    /**
     * 在 IREXT 城市名列表中匹配定位到的市（逻辑与 [matchArea] 相同）。
     * @return 命中的 irext 城市名；未命中返回 null
     */
    fun matchCity(cities: List<String>, cityName: String): String? =
        matchIn(cities, cityName)

    /** 通用匹配实现（省/市共用） */
    private fun matchIn(names: List<String>, query: String): String? {
        // 1) 精确匹配
        names.firstOrNull { it == query }?.let { return it }
        val q = normalize(query)
        if (q.isEmpty()) return null
        // 2) 归一化后相等（"广东" ↔ "广东省"）
        names.firstOrNull { normalize(it) == q }?.let { return it }
        // 3) 归一化双向包含（"新疆维吾尔" ↔ "新疆" 等极端差异；长度≥2 防"海"误匹配"上海"）
        return names.firstOrNull {
            val n = normalize(it)
            n.length >= 2 && q.length >= 2 && (n.contains(q) || q.contains(n))
        }
    }
}
