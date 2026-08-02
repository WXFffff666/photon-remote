package com.photon.remote.codebase.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.photon.remote.codebase.IrextBinaryStore
import com.photon.remote.codebase.IrextCategory
import com.photon.remote.codebase.IrextIndexData
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.data.local.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CodebaseUpdater 单元测试（计划 Todo 50 验收）。
 *
 * 网络层以本地文件模拟（[CodebaseUpdater.fetchBytes] / [downloader] 注入，
 * 按 URL 最后一段文件名取 serverDir 内文件），其余（解压、SHA-256 校验、
 * 增量合并、备份回滚）走真实文件系统，覆盖：
 * - SHA-256 校验通过 / 任一文件不匹配整包拒绝（不落盘）；
 * - 全量更新落盘 + DataStore 版本记录 + 缓存优先生效；
 * - 增量合并正确（条目合并 + 二进制拷贝 + 旧文件保留）；
 * - baseVersion 不匹配 / 无缓存 → 自动回退全量；
 * - 应用失败 → 回滚保留旧版本；
 * - 无网络优雅降级（不影响内置码库）。
 */
@RunWith(RobolectricTestRunner::class)
class CodebaseUpdaterTest {

    private lateinit var serverDir: File
    private lateinit var app: Context
    private lateinit var settings: SettingsStore
    private lateinit var updater: CodebaseUpdater

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        serverDir = File.createTempFile("upd-server", "").apply { delete(); mkdirs() }
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(
                produceFile = { File(serverDir.parentFile, "upd-settings-${System.nanoTime()}.preferences_pb") },
            ),
        )
        val indexLoader = IrextIndexLoader(app)
        val binaryStore = IrextBinaryStore(app, indexLoader)
        updater = CodebaseUpdater(app, indexLoader, binaryStore, settings)
        // 网络层模拟：URL 最后一段文件名 → serverDir 内文件
        updater.fetchBytes = { url ->
            File(serverDir, url.substringAfterLast('/')).takeIf { it.isFile }?.readBytes()
        }
        updater.downloader = { url, target, onProgress ->
            val f = File(serverDir, url.substringAfterLast('/'))
            if (!f.isFile) {
                onProgress(0f)
                false
            } else {
                f.copyTo(target, overwrite = true)
                onProgress(1f)
                true
            }
        }
    }

    @After
    fun tearDown() {
        serverDir.deleteRecursively()
    }

    // =====================================================================
    // 测试数据构造
    // =====================================================================

    private fun cat(id: Int, name: String) = IrextCategory(id = id, name = name, nameEn = name)

    private fun shaHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 远程清单（Release 根目录 manifest.json） */
    private fun writeRemote(version: String, baseVersion: String, fullName: String, incrName: String?) {
        val m = UpdateManifest(
            version = version,
            baseVersion = baseVersion,
            changelog = "测试更新",
            fullPackage = fullName,
            incrementalPackage = incrName ?: "",
        )
        File(serverDir, "manifest.json").writeText(json.encodeToString(UpdateManifest.serializer(), m))
    }

    /** 全量包 codedb-v<version>.zip；[tamperIndex] = 篡改索引字节但 manifest 哈希不匹配 */
    private fun buildFullPackage(
        version: String,
        index: IrextIndexData,
        bins: Map<String, ByteArray>,
        tamperIndex: Boolean = false,
    ): String {
        val indexBytes = json.encodeToString(IrextIndexData.serializer(), index).toByteArray()
        val actualIndexBytes = if (tamperIndex) indexBytes + byteArrayOf(0) else indexBytes
        val binZip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                bins.forEach { (name, bytes) ->
                    zos.putNextEntry(ZipEntry("irext-binaries_$version/$name"))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }.toByteArray()
        val manifest = PackageManifest(
            version = version,
            baseVersion = "",
            files = mapOf(
                "irext-index.json" to shaHex(indexBytes),
                "irext-binaries.zip" to shaHex(binZip),
            ),
        )
        val name = "codedb-v$version.zip"
        val pkg = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(PackageManifest.serializer(), manifest).toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("irext-index.json"))
                zos.write(actualIndexBytes)
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("irext-binaries.zip"))
                zos.write(binZip)
                zos.closeEntry()
            }
        }.toByteArray()
        File(serverDir, name).writeBytes(pkg)
        return name
    }

    /** 增量包 codedb-incr-<base>-<new>.zip（binBytes 缺失的 bin 不打包 → 应用阶段失败） */
    private fun buildIncrementalPackage(
        base: String,
        version: String,
        descriptor: IncrementalDescriptor,
        binBytes: Map<String, ByteArray>,
    ): String {
        val descName = "incremental/$base-$version.json"
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries += descName to json.encodeToString(IncrementalDescriptor.serializer(), descriptor).toByteArray()
        descriptor.binaries.forEach { bin ->
            binBytes[bin]?.let { entries += "binaries/$bin" to it }
        }
        val manifest = PackageManifest(
            version = version,
            baseVersion = base,
            files = entries.associate { (n, b) -> n to shaHex(b) },
        )
        val name = "codedb-incr-$base-$version.zip"
        val pkg = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(PackageManifest.serializer(), manifest).toByteArray())
                zos.closeEntry()
                entries.forEach { (n, b) ->
                    zos.putNextEntry(ZipEntry(n))
                    zos.write(b)
                    zos.closeEntry()
                }
            }
        }.toByteArray()
        File(serverDir, name).writeBytes(pkg)
        return name
    }

    /** 读 filesDir 缓存索引 */
    private fun cachedIndex(): IrextIndexData? {
        val f = File(app.filesDir, "codedb/irext-index.json")
        if (!f.isFile) return null
        return json.decodeFromString(IrextIndexData.serializer(), f.readText())
    }

    private fun cachedBinDir(): File = File(app.filesDir, "codedb/binaries")

    // =====================================================================
    // 检查更新
    // =====================================================================

    @Test
    fun 检查更新_远程与内置同版本_返回已是最新() = runBlocking {
        writeRemote("20260519", "", "codedb-v20260519.zip", null)
        assertEquals(UpdateResult.UpToDate, updater.checkForUpdate())
    }

    @Test
    fun 检查更新_发现新版本_无缓存推荐全量() = runBlocking {
        writeRemote("20260520", "20260519", "codedb-v20260520.zip", null)
        val result = updater.checkForUpdate() as UpdateResult.Available
        assertEquals("20260520", result.version)
        assertEquals(UpdateMode.FULL, result.mode)
    }

    @Test
    fun 检查更新_增量条件满足_推荐增量() = runBlocking {
        // 先全量应用 v1.0（建立本地缓存 + DataStore 版本）
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage("1.0", IrextIndexData("1.0", listOf(cat(1, "空调"))), mapOf("a.bin" to "AA".toByteArray()))
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 远程 v1.1：baseVersion 匹配 + 提供增量包
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "1.1", listOf(cat(3, "机顶盒")), listOf("b.bin")),
            mapOf("b.bin" to "BB".toByteArray()),
        )
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")
        val result = updater.checkForUpdate() as UpdateResult.Available
        assertEquals(UpdateMode.INCREMENTAL, result.mode)
    }

    // =====================================================================
    // 全量更新：校验通过 / 不匹配
    // =====================================================================

    @Test
    fun 全量更新_校验通过_缓存落盘且版本记录() = runBlocking {
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调"), cat(2, "电视"))),
            mapOf("a.bin" to "AAA".toByteArray()),
        )
        val result = updater.downloadAndApply(UpdateMode.FULL)
        assertTrue("应更新成功：$result", result is UpdateResult.Succeeded)
        assertEquals(UpdateMode.FULL, (result as UpdateResult.Succeeded).mode)
        // 缓存落盘
        val index = cachedIndex()
        assertNotNull(index)
        assertEquals("1.0", index!!.version)
        assertEquals(2, index.categories.size)
        assertEquals("AAA", String(File(cachedBinDir(), "a.bin").readBytes()))
        // DataStore 版本记录 + 本地版本生效
        assertEquals("1.0", settings.codedbVersion.first())
        assertEquals("1.0", updater.localVersion())
        // 新构造的加载器（缓存优先）读取到更新版本
        assertEquals("1.0", IrextIndexLoader(app).version)
    }

    @Test
    fun 全量更新_SHA256不匹配_整包拒绝且不落盘() = runBlocking {
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调"))),
            mapOf("a.bin" to "AAA".toByteArray()),
            tamperIndex = true,   // 索引被篡改：manifest 声明的哈希与实际不符
        )
        val result = updater.downloadAndApply(UpdateMode.FULL) as UpdateResult.Failed
        assertTrue("应提示校验失败：${result.reason}", result.reason.contains("SHA-256"))
        // 不落盘：codedb 目录不存在，DataStore 未记录
        assertFalse(File(app.filesDir, "codedb").exists())
        assertEquals("", settings.codedbVersion.first())
    }

    @Test
    fun 全量更新_篡改包与本地已有缓存_拒绝且保留旧缓存() = runBlocking {
        // 先成功应用 v0（旧版本缓存）
        writeRemote("0.9", "", "codedb-v0.9.zip", null)
        buildFullPackage("0.9", IrextIndexData("0.9", listOf(cat(1, "空调"))), mapOf("a.bin" to "AAA".toByteArray()))
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 再拉篡改的新包 → 拒绝
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage("1.0", IrextIndexData("1.0", listOf(cat(1, "空调"))), mapOf(), tamperIndex = true)
        val result = updater.downloadAndApply(UpdateMode.FULL) as UpdateResult.Failed
        assertTrue(result.reason.contains("SHA-256"))
        // 旧缓存保持可用
        assertEquals("0.9", cachedIndex()!!.version)
        assertEquals("0.9", settings.codedbVersion.first())
    }

    // =====================================================================
    // 增量更新：合并正确 / baseVersion 不匹配回退全量 / 失败回滚
    // =====================================================================

    @Test
    fun 增量更新_合并正确_新条目加入且旧文件保留() = runBlocking {
        // 先全量应用 v1.0（2 个大类 + a.bin）
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调"), cat(2, "电视"))),
            mapOf("a.bin" to "AAA".toByteArray()),
        )
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 增量 v1.1：新增机顶盒大类 + b.bin
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "1.1", listOf(cat(3, "机顶盒")), listOf("b.bin")),
            mapOf("b.bin" to "BBB".toByteArray()),
        )
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")

        val result = updater.downloadAndApply(UpdateMode.INCREMENTAL)
        assertTrue("应增量成功：$result", result is UpdateResult.Succeeded)
        assertEquals(UpdateMode.INCREMENTAL, (result as UpdateResult.Succeeded).mode)
        // 合并结果：3 个大类（1/2 保留 + 3 追加），版本更新
        val index = cachedIndex()!!
        assertEquals("1.1", index.version)
        assertEquals(listOf(1, 2, 3), index.categories.map { it.id })
        // 二进制：新文件加入、旧文件保留
        assertEquals("BBB", String(File(cachedBinDir(), "b.bin").readBytes()))
        assertEquals("AAA", String(File(cachedBinDir(), "a.bin").readBytes()))
        assertEquals("1.1", settings.codedbVersion.first())
    }

    @Test
    fun 增量更新_同id大类整条替换() = runBlocking {
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调旧版"))),
            mapOf("a.bin" to "AAA".toByteArray()),
        )
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 增量把大类 1 整条替换为「空调新版」
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "1.1", listOf(cat(1, "空调新版")), listOf()),
            emptyMap(),
        )
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")
        assertTrue(updater.downloadAndApply(UpdateMode.INCREMENTAL) is UpdateResult.Succeeded)
        val index = cachedIndex()!!
        assertEquals(1, index.categories.size)
        assertEquals("空调新版", index.categories[0].name)
    }

    @Test
    fun 增量更新_baseVersion不匹配_自动回退全量() = runBlocking {
        // 本地缓存 v1.0
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调"))),
            mapOf("a.bin" to "AAA".toByteArray()),
        )
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 远程 v1.1：baseVersion = "0.9"（≠ 本地 1.0）
        buildFullPackage("1.1", IrextIndexData("1.1", listOf(cat(1, "空调"), cat(2, "电视"))), mapOf("a.bin" to "AAA".toByteArray()))
        buildIncrementalPackage(
            "0.9", "1.1",
            IncrementalDescriptor("0.9", "1.1", listOf(cat(3, "机顶盒")), listOf("b.bin")),
            mapOf("b.bin" to "BBB".toByteArray()),
        )
        writeRemote("1.1", "0.9", "codedb-v1.1.zip", "codedb-incr-0.9-1.1.zip")

        val result = updater.downloadAndApply(UpdateMode.INCREMENTAL)
        assertTrue("应自动回退全量：$result", result is UpdateResult.Succeeded)
        assertEquals(UpdateMode.FULL, (result as UpdateResult.Succeeded).mode)
        // 全量替换：没有增量追加的机顶盒大类
        val index = cachedIndex()!!
        assertEquals("1.1", index.version)
        assertEquals(listOf(1, 2), index.categories.map { it.id })
        assertEquals("1.1", settings.codedbVersion.first())
    }

    @Test
    fun 增量更新_无本地缓存_自动回退全量() = runBlocking {
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")
        buildFullPackage("1.1", IrextIndexData("1.1", listOf(cat(1, "空调"))), mapOf("a.bin" to "AAA".toByteArray()))
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "1.1", listOf(cat(3, "机顶盒")), listOf("b.bin")),
            mapOf("b.bin" to "BBB".toByteArray()),
        )
        val result = updater.downloadAndApply(UpdateMode.INCREMENTAL)
        assertTrue("应回退全量：$result", result is UpdateResult.Succeeded)
        assertEquals(UpdateMode.FULL, (result as UpdateResult.Succeeded).mode)
        assertEquals("1.1", cachedIndex()!!.version)
    }

    @Test
    fun 增量更新_包内缺二进制_失败回滚保留旧版本() = runBlocking {
        // 先全量应用 v1.0
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage(
            "1.0",
            IrextIndexData("1.0", listOf(cat(1, "空调"))),
            mapOf("a.bin" to "AAA".toByteArray()),
        )
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 增量包声明 b.bin 但未打包 → 应用阶段抛错 → 回滚
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "1.1", listOf(cat(3, "机顶盒")), listOf("b.bin")),
            emptyMap(),
        )
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")

        val result = updater.downloadAndApply(UpdateMode.INCREMENTAL) as UpdateResult.Failed
        assertTrue("应提示已保留旧版本：${result.reason}", result.reason.contains("已保留旧版本"))
        // 回滚后旧版本完好
        val index = cachedIndex()!!
        assertEquals("1.0", index.version)
        assertEquals(listOf(1), index.categories.map { it.id })
        assertEquals("AAA", String(File(cachedBinDir(), "a.bin").readBytes()))
        assertEquals("1.0", settings.codedbVersion.first())
        assertEquals("1.0", updater.localVersion())
    }

    @Test
    fun 增量更新_索引损坏_失败回滚保留旧版本() = runBlocking {
        // 先全量应用 v1.0
        writeRemote("1.0", "", "codedb-v1.0.zip", null)
        buildFullPackage("1.0", IrextIndexData("1.0", listOf(cat(1, "空调"))), mapOf("a.bin" to "AAA".toByteArray()))
        assertTrue(updater.downloadAndApply(UpdateMode.FULL) is UpdateResult.Succeeded)
        // 增量描述符版本与包不一致 → applyIncremental 抛错 → 回滚
        buildIncrementalPackage(
            "1.0", "1.1",
            IncrementalDescriptor("1.0", "9.9", listOf(cat(3, "机顶盒")), listOf()),   // 描述符版本 9.9 ≠ 包版本 1.1
            emptyMap(),
        )
        writeRemote("1.1", "1.0", "codedb-v1.1.zip", "codedb-incr-1.0-1.1.zip")
        val result = updater.downloadAndApply(UpdateMode.INCREMENTAL) as UpdateResult.Failed
        assertTrue(result.reason.contains("已保留旧版本"))
        assertEquals("1.0", cachedIndex()!!.version)
    }

    // =====================================================================
    // 无网络优雅降级
    // =====================================================================

    @Test
    fun 无网络_检查与更新均失败_不影响离线() = runBlocking {
        updater.fetchBytes = { null }
        updater.downloader = { _, _, _ -> false }
        val check = updater.checkForUpdate() as UpdateResult.Failed
        assertTrue(check.reason.isNotBlank())
        val apply = updater.downloadAndApply(UpdateMode.FULL) as UpdateResult.Failed
        assertTrue(apply.reason.isNotBlank())
        // 本地版本仍可用（内置 assets）
        assertEquals("20260519", updater.localVersion())
        assertFalse(File(app.filesDir, "codedb").exists())
    }

    // =====================================================================
    // 内部工具直接断言
    // =====================================================================

    @Test
    fun 版本比较_数值段与日期串() {
        assertTrue(updater.compareVersions("1.1", "1.0") > 0)
        assertTrue(updater.compareVersions("1.10", "1.9") > 0)     // 段级数值比较
        assertTrue(updater.compareVersions("20260801", "20260519") > 0)
        assertEquals(0, updater.compareVersions("1.0", "1.0"))
        assertTrue(updater.compareVersions("0.9", "1.0") < 0)
    }

    @Test
    fun 增量合并_同id替换_新id追加_空增量原样() {
        val base = listOf(cat(1, "空调"), cat(2, "电视"))
        val merged = updater.mergeCategories(base, listOf(cat(2, "电视新版"), cat(3, "机顶盒")))
        assertEquals(listOf(1, 2, 3), merged.map { it.id })
        assertEquals("电视新版", merged[1].name)
        assertEquals(base, updater.mergeCategories(base, emptyList()))
    }

    @Test
    fun 内置版本_读取真实assets() {
        assertEquals("20260519", updater.builtinVersion())
    }
}
