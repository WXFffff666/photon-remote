package com.photon.remote.codebase.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AreaNameMatcher 单元测试（计划 Todo 49：定位功能）。
 *
 * 归一化与匹配逻辑；用**真实 irext 索引地区名**（assets/irext/irext-index.json 的
 * areas/cities 名称）验证：广东省/北京市/内蒙古自治区/新疆维吾尔自治区 等后缀形态。
 */
class AreaNameMatcherTest {

    /** 真实 irext 索引省名样例（areas.name 含省/市/自治区/特别行政区 后缀） */
    private val realAreas = listOf(
        "北京市", "上海市", "天津市", "重庆市",
        "河北省", "广东省", "江苏省", "浙江省", "海南省",
        "内蒙古自治区", "广西壮族自治区", "新疆维吾尔自治区",
        "宁夏回族自治区", "西藏自治区", "香港特别行政区",
    )

    /** 真实 irext 索引城市名样例（cities.name 均带"市"后缀） */
    private val realCities = listOf(
        "广州市", "深圳市", "珠海市", "汕头市", "佛山市", "东莞市",
        "石家庄市", "唐山市", "呼和浩特市", "乌鲁木齐市",
    )

    // ---------- normalize ----------

    @Test
    fun 归一化_去省市后缀() {
        assertEquals("广东", AreaNameMatcher.normalize("广东省"))
        assertEquals("广东", AreaNameMatcher.normalize("广东"))
        assertEquals("深圳", AreaNameMatcher.normalize("深圳市"))
        assertEquals("上海", AreaNameMatcher.normalize("上海市"))
        assertEquals("上海", AreaNameMatcher.normalize("上海"))
    }

    @Test
    fun 归一化_处理自治区与特别行政区() {
        assertEquals("内蒙古", AreaNameMatcher.normalize("内蒙古自治区"))
        assertEquals("新疆", AreaNameMatcher.normalize("新疆维吾尔自治区"))
        assertEquals("广西", AreaNameMatcher.normalize("广西壮族自治区"))
        assertEquals("宁夏", AreaNameMatcher.normalize("宁夏回族自治区"))
        assertEquals("西藏", AreaNameMatcher.normalize("西藏自治区"))
        assertEquals("香港", AreaNameMatcher.normalize("香港特别行政区"))
        assertEquals("澳门", AreaNameMatcher.normalize("澳门特别行政区"))
    }

    @Test
    fun 归一化_去空白() {
        assertEquals("广东", AreaNameMatcher.normalize(" 广 东省 "))
        assertEquals("深圳", AreaNameMatcher.normalize("深圳市\n"))
    }

    // ---------- matchArea ----------

    @Test
    fun 匹配省_精确命中() {
        assertEquals("广东省", AreaNameMatcher.matchArea(realAreas, "广东省"))
        assertEquals("北京市", AreaNameMatcher.matchArea(realAreas, "北京市"))
    }

    @Test
    fun 匹配省_无后缀命中() {
        assertEquals("广东省", AreaNameMatcher.matchArea(realAreas, "广东"))
        assertEquals("北京市", AreaNameMatcher.matchArea(realAreas, "北京"))
        assertEquals("河北省", AreaNameMatcher.matchArea(realAreas, "河北"))
        assertEquals("内蒙古自治区", AreaNameMatcher.matchArea(realAreas, "内蒙古"))
        assertEquals("新疆维吾尔自治区", AreaNameMatcher.matchArea(realAreas, "新疆"))
        assertEquals("广西壮族自治区", AreaNameMatcher.matchArea(realAreas, "广西"))
        assertEquals("宁夏回族自治区", AreaNameMatcher.matchArea(realAreas, "宁夏"))
        assertEquals("西藏自治区", AreaNameMatcher.matchArea(realAreas, "西藏"))
        assertEquals("香港特别行政区", AreaNameMatcher.matchArea(realAreas, "香港"))
    }

    @Test
    fun 匹配省_带后缀命中() {
        assertEquals("内蒙古自治区", AreaNameMatcher.matchArea(realAreas, "内蒙古自治区"))
        assertEquals("新疆维吾尔自治区", AreaNameMatcher.matchArea(realAreas, "新疆维吾尔自治区"))
        assertEquals("广西壮族自治区", AreaNameMatcher.matchArea(realAreas, "广西壮族自治区"))
        assertEquals("香港特别行政区", AreaNameMatcher.matchArea(realAreas, "香港特别行政区"))
    }

    @Test
    fun 匹配省_未知返回空() {
        assertNull(AreaNameMatcher.matchArea(realAreas, "外星省"))
        assertNull(AreaNameMatcher.matchArea(realAreas, "东"))
        assertNull(AreaNameMatcher.matchArea(realAreas, ""))
    }

    // ---------- matchCity ----------

    @Test
    fun 匹配市_精确与无后缀() {
        assertEquals("深圳市", AreaNameMatcher.matchCity(realCities, "深圳市"))
        assertEquals("深圳市", AreaNameMatcher.matchCity(realCities, "深圳"))
        assertEquals("广州市", AreaNameMatcher.matchCity(realCities, "广州"))
        assertEquals("呼和浩特市", AreaNameMatcher.matchCity(realCities, "呼和浩特"))
        assertEquals("乌鲁木齐市", AreaNameMatcher.matchCity(realCities, "乌鲁木齐"))
    }

    @Test
    fun 匹配市_未知返回空() {
        // 北京市不在广东城市列表中；单字防误匹配
        assertNull(AreaNameMatcher.matchCity(realCities, "北京市"))
        assertNull(AreaNameMatcher.matchCity(realCities, "火星市"))
    }
}
