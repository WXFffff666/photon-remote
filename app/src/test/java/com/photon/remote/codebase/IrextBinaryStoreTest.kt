package com.photon.remote.codebase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * IrextBinaryStore 单元测试（计划 §4.3 / Todo 19，Todo 50 追加缓存目录优先）。
 *
 * 覆盖：filesDir 缓存目录（codedb/binaries/）优先于内置 assets zip、
 * 缓存缺失回退 zip、clearCache 不崩溃、目录穿越防御。
 */
@RunWith(RobolectricTestRunner::class)
class IrextBinaryStoreTest {

    /** 真实 assets 索引（约 0.75MB）中存在的 AC 码组 bin（既有测试已验证） */
    private val realBin = "irda_new_ac_9377.bin"

    private fun newStore(): IrextBinaryStore =
        IrextBinaryStore(RuntimeEnvironment.getApplication(), IrextIndexLoader(RuntimeEnvironment.getApplication()))

    @Test
    fun 缓存目录存在_优先于内置zip() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheBin = File(ctx.filesDir, "codedb/binaries/$realBin")
        cacheBin.parentFile!!.mkdirs()
        val fakeBytes = "CACHED-BYTES-FOR-TEST".toByteArray()
        cacheBin.writeBytes(fakeBytes)
        try {
            val ref = newStore().load(realBin)
            assertTrue("应从缓存目录命中", ref != null)
            assertEquals(String(fakeBytes), String(ref!!.bytes))
            // 元数据仍从索引解析：AC 大类 id = 1、subCate = 0
            assertEquals(1, ref.category)
            assertEquals(0, ref.subCate)
        } finally {
            cacheBin.delete()
        }
    }

    @Test
    fun 缓存目录缺失_回退内置zip() = runBlocking {
        val ref = newStore().load(realBin)
        assertTrue("应回退 zip 命中", ref != null)
        assertEquals(realBin, ref!!.binaryName)
        assertEquals(1, ref.category)
        assertTrue(ref.bytes.isNotEmpty())
    }

    @Test
    fun 不存在文件_返回null不崩溃() = runBlocking {
        assertNull(newStore().load("no_such_file.bin"))
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
        val first = store.load(realBin)
        store.clearCache()
        val second = store.load(realBin)
        assertEquals(first?.binaryName, second?.binaryName)
    }
}
