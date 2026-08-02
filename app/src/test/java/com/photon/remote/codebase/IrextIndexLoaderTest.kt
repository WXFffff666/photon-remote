package com.photon.remote.codebase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * IrextIndexLoader 单元测试（计划 §4.1 / Todo 18 验收）。
 *
 * 加载**真实 assets**（Robolectric 支持真实 assets，testOptions 已开
 * isIncludeAndroidResources）：断言 categories 非空、STB 省市区运营商链路有结果
 * （计划验收链路 STB→中兴→广东→深圳→移动；实测数据品牌名为"运营商盒子"、
 * 省名为"广东省"、城市前缀"深圳"，测试按实际数据断言，链路语义一致）。
 */
@RunWith(RobolectricTestRunner::class)
class IrextIndexLoaderTest {

    /** 从真实 assets 读取索引 JSON（约 0.75MB） */
    private val realJson: String by lazy {
        RuntimeEnvironment.getApplication().assets
            .open("irext/irext-index.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    @Test
    fun 加载真实assets_类别非空() {
        val loader = IrextIndexLoader(realJson)
        val categories = loader.getCategories()
        assertTrue("categories 不得为空", categories.isNotEmpty())
        assertTrue("版本号非空", loader.version.isNotEmpty())
    }

    @Test
    fun 按类别查品牌_电视空调机顶盒均有品牌() {
        val loader = IrextIndexLoader(realJson)
        assertTrue(loader.getBrands(2).isNotEmpty())   // TV = irext CategoryID 2
        assertTrue(loader.getBrands(1).isNotEmpty())   // AC = irext CategoryID 1
        assertTrue(loader.getBrands(3).isNotEmpty())   // STB = irext CategoryID 3
        assertTrue(loader.getBrands(999).isEmpty())    // 不存在的大类 → 空列表
    }

    @Test
    fun 查询STB省市区运营商链路_有结果() {
        val loader = IrextIndexLoader(realJson)
        val stb = loader.getCategories().first { it.nameEn == "STB" }
        // 品牌 → 省：STB 品牌带 areas（实测品牌名为"运营商盒子"，id=0）
        val brand = loader.getBrands(stb.id).first()
        assertNotNull("STB 品牌应存在", brand)
        val areas = loader.getAreas(brand.id)
        assertTrue("STB 品牌应带省列表", areas.isNotEmpty())
        // 省 → 市：广东省下应含深圳市
        val gd = areas.first { it.name == "广东省" }
        val cities = loader.getCities(brand.id, "广东省")
        assertTrue(cities.isNotEmpty())
        val sz = cities.first { it.name.startsWith("深圳") }
        // 市 → 运营商：深圳市运营商非空
        val operators = loader.getOperators(brand.id, "广东省", sz.name)
        assertTrue("深圳市应有运营商", operators.isNotEmpty())
        // 运营商 → 遥控器：至少一个运营商带遥控器记录
        val opWithRemotes = operators.first { it.remotes.isNotEmpty() }
        val remote = opWithRemotes.remotes.first()
        assertTrue(remote.bin.isNotEmpty())
        assertTrue(remote.id >= 0)
    }

    @Test
    fun 品牌直属遥控器_非STB链路() {
        val loader = IrextIndexLoader(realJson)
        // TV 大类第一个品牌的直属遥控器（非 STB 品牌 areas 为空、remotes 直挂）
        val tvBrand = loader.getBrands(2).first()
        val remotes = loader.getRemotes(tvBrand.id)
        assertTrue("TV 品牌应有直属遥控器", remotes.isNotEmpty())
        val first = remotes.first()
        assertTrue(first.bin.endsWith(".bin"))
    }

    @Test
    fun 按id与bin全局查找_命中且类别正确() {
        val loader = IrextIndexLoader(realJson)
        val bin = "irda_new_ac_9377.bin"   // 实测存在：AC 类 奥克斯 码组
        val byBin = loader.findRemoteByBin(bin)
        assertNotNull("findRemoteByBin 应命中", byBin)
        assertEquals(bin, byBin!!.bin)
        // 按 id 反查：应命中同一记录
        val byId = loader.findRemoteById(byBin.id)
        assertNotNull("findRemoteById 应命中", byId)
        assertEquals(byBin.bin, byId!!.bin)
        // 类别解析：AC bin → 大类 id = 1
        assertEquals(1, loader.categoryIdOf(bin))
        assertNotNull(loader.findCategoryByBin(bin))
        // 不存在的 bin → null（不崩溃）
        assertFalse(loader.categoryIdOf("no_such.bin") != null)
        assertEquals(null, loader.findRemoteByBin("no_such.bin"))
    }

    // =====================================================================
    // Todo 50：filesDir 缓存优先于 assets + reload()
    // =====================================================================

    @Test
    fun filesDir缓存存在_优先于内置assets() {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheFile = File(ctx.filesDir, "codedb/irext-index.json")
        cacheFile.parentFile!!.mkdirs()
        // 写一个合成缓存索引（版本标记 CACHE-1），与真实 assets 内容不同
        val synthetic = IrextIndexData(version = "CACHE-1", categories = emptyList())
        cacheFile.writeText(Json.encodeToString(IrextIndexData.serializer(), synthetic))
        try {
            val loader = IrextIndexLoader(ctx)
            assertEquals("应读取缓存版本", "CACHE-1", loader.version)
            assertTrue("缓存索引 categories 为空", loader.getCategories().isEmpty())
        } finally {
            cacheFile.delete()
        }
    }

    @Test
    fun 删除缓存后reload_回退内置assets() {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheFile = File(ctx.filesDir, "codedb/irext-index.json")
        cacheFile.parentFile!!.mkdirs()
        val synthetic = IrextIndexData(version = "CACHE-1", categories = emptyList())
        cacheFile.writeText(Json.encodeToString(IrextIndexData.serializer(), synthetic))
        val loader = IrextIndexLoader(ctx)
        assertEquals("CACHE-1", loader.version)
        // 删除缓存 + reload → 回退 assets（版本为真实内置版本）
        cacheFile.delete()
        loader.reload()
        assertTrue("reload 后应重新读取 assets", loader.getCategories().isNotEmpty())
        assertTrue(loader.version.isNotEmpty())
    }

    @Test
    fun 缓存损坏_自动回退assets并清理() {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheFile = File(ctx.filesDir, "codedb/irext-index.json")
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeText("这不是合法 JSON{{{")
        try {
            val loader = IrextIndexLoader(ctx)
            assertTrue("损坏缓存应回退 assets", loader.getCategories().isNotEmpty())
            assertFalse("损坏缓存应被清理", cacheFile.exists())
        } finally {
            cacheFile.delete()
        }
    }

    @Test
    fun reload_不改变纯字符串构造行为() {
        val loader = IrextIndexLoader("{\"version\":\"1.0\",\"categories\":[]}")
        assertEquals("1.0", loader.version)
        loader.reload()   // 不崩溃，重新解析同源 JSON
        assertEquals("1.0", loader.version)
    }
}
