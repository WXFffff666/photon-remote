package com.photon.remote.codebase

import kotlinx.coroutines.runBlocking
import net.irext.decode.sdk.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * IrextBinaryStore 单元测试（计划 §4.3 / Todo 19，Todo 50 追加缓存目录优先，
 * 下载优先改造后自建 fixture zip，绝不依赖生产 assets 内置码库）。
 *
 * 覆盖：多条目 fixture zip 按需解析、filesDir 缓存目录优先、索引查不到时
 * bin 名启发式大类推断、内置 zip 缺失/损坏降级为 null（下载优先：交由
 * CodebaseUpdater 在线路径）、clearCache、目录穿越防御。
 */
@RunWith(RobolectricTestRunner::class)
class IrextBinaryStoreTest {

    /** 真实 assets 索引（irext-index.json 保留入库）中存在的 AC 码组 bin */
    private val indexedAcBin = "irda_new_ac_9377.bin"

    @get:Rule
    val tmp = TemporaryFolder()

    /** 自建多条目 fixture zip：根目录前缀 + 两个 .bin + 一个非 bin 条目 */
    private fun newFixtureZip(): File {
        val zip = tmp.newFile("irext-binaries-fixture.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            put("irext-binaries_20260825/$indexedAcBin", "FIXTURE-AC-BYTES".toByteArray())
            put("irext-binaries_20260825/irda_new_tv_demo.bin", "FIXTURE-TV-BYTES".toByteArray())
            put("irext-binaries_20260825/README.txt", "fixture".toByteArray())
        }
        return zip
    }

    /** 经缝隙注入 fixture zip（zip=null 模拟内置码库缺失） */
    private fun newStore(zip: File? = newFixtureZip()): IrextBinaryStore =
        IrextBinaryStore(
            RuntimeEnvironment.getApplication(),
            IrextIndexLoader(RuntimeEnvironment.getApplication()),
            openBundledZip = { zip?.let { FileInputStream(it) } },
        )

    @Test
    fun 内置zip命中_返回字节与索引元数据() = runBlocking {
        val ref = newStore().load(indexedAcBin)
        assertTrue("应从 fixture zip 命中", ref != null)
        assertEquals("FIXTURE-AC-BYTES", String(ref!!.bytes))
        assertEquals(indexedAcBin, ref.binaryName)
        // 元数据从真实索引解析：AC 大类 id = 1、subCate = 0
        assertEquals(Constants.CategoryID.AIR_CONDITIONER.getValue(), ref.category)
        assertEquals(0, ref.subCate)
    }

    @Test
    fun 缓存目录存在_优先于内置zip() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheBin = File(ctx.filesDir, "codedb/binaries/$indexedAcBin")
        cacheBin.parentFile!!.mkdirs()
        cacheBin.writeBytes("CACHED-BYTES-FOR-TEST".toByteArray())
        try {
            val ref = newStore().load(indexedAcBin)
            assertTrue("应从缓存目录命中", ref != null)
            assertEquals("CACHED-BYTES-FOR-TEST", String(ref!!.bytes))
            assertEquals(Constants.CategoryID.AIR_CONDITIONER.getValue(), ref.category)
            assertEquals(0, ref.subCate)
        } finally {
            cacheBin.delete()
        }
    }

    @Test
    fun 索引查不到_按bin名启发式推断大类() = runBlocking {
        val ref = newStore().load("irda_new_tv_demo.bin")
        assertTrue(ref != null)
        assertEquals("FIXTURE-TV-BYTES", String(ref!!.bytes))
        // 索引无此条目时按命名启发式兜底：_tv_ → TV 大类，命令类 subCate = 1
        assertEquals(Constants.CategoryID.TV.getValue(), ref.category)
        assertEquals(1, ref.subCate)
    }

    @Test
    fun 内置zip缺失_返回null不崩溃() = runBlocking {
        // 下载优先：内置 zip 不存在 → 告警并降级为 null，由 CodebaseUpdater 在线补齐
        assertNull(newStore(zip = null).load(indexedAcBin))
    }

    @Test
    fun 内置zip损坏_返回null不崩溃() = runBlocking {
        val garbage = tmp.newFile("garbage.zip")
        garbage.writeBytes("NOT-A-ZIP".toByteArray())
        assertNull(newStore(zip = garbage).load(indexedAcBin))
    }

    @Test
    fun 目录穿越bin名_无法逃出缓存目录() = runBlocking {
        // 在 filesDir 根（缓存目录之外）放投毒文件：穿越串必须无法命中它。
        // 实现只取 bin 名最后一段（"../evil.bin" → "evil.bin"）且拒绝含 ".." 的段，
        // 解析后永远落在 codedb/binaries/ 内，绝无逃逸路径。
        val ctx = RuntimeEnvironment.getApplication()
        val evil = File(ctx.filesDir, "evil.bin")
        evil.writeBytes("EVIL".toByteArray())
        try {
            assertNull(newStore().load("../evil.bin"))
        } finally {
            evil.delete()
        }
    }

    @Test
    fun clearCache_不崩溃且下次仍可读() = runBlocking {
        val store = newStore()
        val first = store.load(indexedAcBin)
        store.clearCache()
        val second = store.load(indexedAcBin)
        assertEquals(first?.binaryName, second?.binaryName)
        assertEquals(String(first!!.bytes), String(second!!.bytes))
    }
}
