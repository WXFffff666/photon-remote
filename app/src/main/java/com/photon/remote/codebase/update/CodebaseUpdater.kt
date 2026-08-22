package com.photon.remote.codebase.update

import android.content.Context
import com.photon.remote.codebase.IrextBinaryStore
import com.photon.remote.codebase.IrextCategory
import com.photon.remote.codebase.IrextIndexData
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 码库在线更新器（计划 Todo 50）。
 *
 * 主路径 = 内置离线 assets（不可变，永不写入）；备用 = 云端更新（GitHub Release
 * 镜像托管数据包，IREXT 官方源为优先源）。更新产物写入 **filesDir 缓存**
 * （`filesDir/codedb/`），IrextIndexLoader / IrextBinaryStore 读取时缓存优先。
 *
 * 更新流程（两阶段）：
 * 1. [checkForUpdate]：GET 远程 manifest.json → 与本地版本（DataStore 记录优先，
 *    其次缓存索引/内置 assets）比对 → 有新版本返回 [UpdateResult.Available]，
 *    并自动选择 FULL / INCREMENTAL（增量需 baseVersion 匹配 + 已有本地缓存 +
 *    远程提供增量包，任一不满足回退全量）；
 * 2. [downloadAndApply]：下载数据包 → 解压并**逐文件 SHA-256 校验**（任一不匹配
 *    整包拒绝，不触碰现有缓存）→ 先备份旧缓存（`filesDir/codedb-backup/`）→
 *    应用（全量替换 / 增量合并）→ 写入后自检索引可解析 → 成功才记录 DataStore
 *    版本并 [IrextIndexLoader.reload] / [IrextBinaryStore.clearCache] 刷新内存；
 *    任一步失败 → 恢复备份，旧版本保持可用。
 *
 * 网络：原生 HttpURLConnection（API 24 兼容），连接/读取各 15s 超时，
 * 全部 IO 在 Dispatchers.IO；无网络 / 更新失败一律返回可读中文原因，
 * 不影响内置码库离线使用。
 *
 * 测试注入：网络层为可替换函数 [fetchBytes] / [downloader]（单测用本地文件模拟），
 * 其余（校验/合并/回滚）走真实文件系统路径，Robolectric 单测覆盖。
 */
class CodebaseUpdater(
    private val context: Context,
    private val indexLoader: IrextIndexLoader,
    private val binaryStore: IrextBinaryStore,
    private val settingsStore: SettingsStore,
) {

    // ---------- 网络层（单测可注入替换；生产走 HttpURLConnection） ----------

    /** 小文件拉取（远程清单等）：URL → 字节；失败/非 200 返回 null */
    @Volatile
    internal var fetchBytes: (String) -> ByteArray? = { url -> httpGet(url) }

    /** 大文件下载（数据包）：URL → 目标文件 + 进度回调；成功返回 true */
    @Volatile
    internal var downloader: (String, File, (Float) -> Unit) -> Boolean =
        { url, target, onProgress -> httpDownload(url, target, onProgress) }

    // ---------- 阶段一：检查更新 ----------

    /**
     * 检查是否有新版本。
     * @return [UpdateResult.UpToDate] / [UpdateResult.Available]（自动选择更新方式）/
     * [UpdateResult.Failed]（网络等失败，不影响离线使用）
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        val remote = fetchJson<UpdateManifest>(REMOTE_MANIFEST_URL)
            ?: return@withContext UpdateResult.Failed(
                "检查更新失败：网络异常或服务不可用（离线不影响使用内置码库）",
            )
        val local = localVersion()
        if (compareVersions(remote.version, local) <= 0) {
            return@withContext UpdateResult.UpToDate
        }
        val mode = if (
            remote.incrementalPackage.isNotBlank() &&
            remote.baseVersion.isNotBlank() &&
            remote.baseVersion == local &&
            hasLocalCache()
        ) {
            UpdateMode.INCREMENTAL
        } else {
            UpdateMode.FULL
        }
        UpdateResult.Available(remote.version, remote.changelog, mode)
    }

    // ---------- 阶段二：下载 + 校验 + 应用（含回滚） ----------

    /**
     * 下载并应用指定方式的更新。
     *
     * 增量前置条件（baseVersion 匹配 / 已有本地缓存 / 远程提供增量包）任一不满足时
     * **自动回退全量**；成功后刷新 [IrextIndexLoader] / [IrextBinaryStore] 内存，
     * 下次查询自动使用新缓存。
     *
     * @param onProgress 下载进度回调（0f~1f，IO 线程）
     */
    suspend fun downloadAndApply(
        mode: UpdateMode,
        onProgress: (Float) -> Unit = {},
    ): UpdateResult = withContext(Dispatchers.IO) {
        // 1) 远程清单：包名 + 版本
        val remote = fetchJson<UpdateManifest>(REMOTE_MANIFEST_URL)
            ?: return@withContext UpdateResult.Failed("获取更新清单失败：网络异常或服务不可用")
        // 2) 增量条件不满足 → 自动回退全量
        val effective = when {
            mode == UpdateMode.FULL -> UpdateMode.FULL
            remote.incrementalPackage.isBlank() -> UpdateMode.FULL
            remote.baseVersion.isBlank() || remote.baseVersion != localVersion() -> UpdateMode.FULL
            !hasLocalCache() -> UpdateMode.FULL
            else -> UpdateMode.INCREMENTAL
        }
        val pkgName = if (effective == UpdateMode.FULL) remote.fullPackage else remote.incrementalPackage
        if (pkgName.isBlank()) {
            return@withContext UpdateResult.Failed(
                "服务器未提供${if (effective == UpdateMode.FULL) "全量" else "增量"}数据包，请稍后重试",
            )
        }
        // 3) 下载数据包（临时文件，成功后删除）
        val tmp = File(context.cacheDir, "codedb-download-${System.currentTimeMillis()}.zip")
        val downloaded = downloader(releaseUrl(pkgName), tmp, onProgress)
        if (!downloaded) {
            tmp.delete()
            return@withContext UpdateResult.Failed("下载失败：网络异常或服务器响应错误（旧版本不受影响）")
        }
        // 4) 解压 + 逐文件 SHA-256 校验（任一不匹配 → 整包拒绝）
        val stage = File(context.cacheDir, "codedb-stage-${System.currentTimeMillis()}")
        val verifyError = extractAndVerify(tmp, stage)
        if (verifyError != null) {
            tmp.delete(); stage.deleteRecursively()
            return@withContext UpdateResult.Failed(verifyError)
        }
        val inner = readJson(PackageManifest.serializer(), File(stage, "manifest.json"))
        if (inner == null || inner.version != remote.version) {
            tmp.delete(); stage.deleteRecursively()
            return@withContext UpdateResult.Failed("数据包清单版本与服务器不一致，已拒绝更新")
        }
        // 5) 备份旧缓存 → 应用 → 失败回滚
        val applyError = applyWithBackup(stage, effective, inner)
        tmp.delete(); stage.deleteRecursively()
        if (applyError != null) {
            return@withContext UpdateResult.Failed("更新失败：$applyError，已保留旧版本")
        }
        // 6) 成功：记录版本 + 刷新内存（下次查询自动走新缓存）
        settingsStore.setCodedbVersion(remote.version)
        indexLoader.reload()
        binaryStore.clearCache()
        UpdateResult.Succeeded(remote.version, effective)
    }

    // ---------- 本地版本查询（供 UI 展示与比对） ----------

    /**
     * 当前生效的本地码库版本：
     * 有 filesDir 缓存 → DataStore 记录优先（成功更新时写入），其次读缓存索引 version；
     * 无缓存 → 内置 assets 版本。
     */
    suspend fun localVersion(): String {
        if (hasLocalCache()) {
            val stored = settingsStore.codedbVersion.first()
            if (stored.isNotBlank()) return stored
            val fromIndex = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(IrextIndexData.serializer(), cachedIndexFile().readText())
                    .version
            }.getOrNull()
            if (!fromIndex.isNullOrBlank()) return fromIndex
        }
        return indexLoader.version
    }

    /** 内置 assets 码库版本（展示用；不受 filesDir 缓存影响，始终读 assets） */
    fun builtinVersion(): String {
        val text = runCatching {
            context.assets.open(ASSET_INDEX_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return ""
        return runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(IrextIndexData.serializer(), text)
                .version
        }.getOrDefault("")
    }

    // ---------- 下载校验 ----------

    /**
     * 解压数据包到 [stage] 目录并逐文件与包内 manifest 比对 SHA-256。
     * @return null = 全部校验通过；否则返回失败原因（整包拒绝）
     */
    private fun extractAndVerify(zipFile: File, stage: File): String? = try {
        stage.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // 防御：拒绝路径穿越；目录段一律拍平（增量描述符目录 incremental/ 保留）
                    val rel = entry.name.removePrefix("/")
                    if (rel.contains("..")) throw IllegalStateException("数据包含非法路径，已拒绝")
                    val out = File(stage, rel)
                    // 增强 ZipSlip 防护：校验 canonicalPath 必须落在 stage 内
                    if (!out.canonicalFile.path.startsWith(stage.canonicalFile.path + File.separator)) {
                        throw IllegalStateException("数据包含非法路径（ZipSlip），已拒绝: $rel")
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val manifest = readJson(PackageManifest.serializer(), File(stage, "manifest.json"))
            ?: return "数据包缺少 manifest.json，已拒绝更新"
        // 逐文件 SHA-256 校验（任一不匹配 → 整包拒绝）
        manifest.files.forEach { (name, expected) ->
            val f = File(stage, name)
            if (!f.isFile) return "数据包缺少文件 $name，已拒绝更新"
            val actual = sha256Hex(f)
            if (!actual.equals(expected, ignoreCase = true)) {
                return "文件 $name 校验失败（SHA-256 不匹配），已拒绝更新"
            }
        }
        null
    } catch (e: Exception) {
        e.message ?: "数据包解析失败，已拒绝更新"
    }

    // ---------- 应用（备份 → 写入 → 自检 → 回滚） ----------

    /**
     * 应用数据包：先备份旧缓存到 codedb-backup/，应用后自检索引可解析；
     * 任何异常 → 删除半成品、恢复备份，旧版本保持可用。
     * @return null = 成功；否则失败原因（已回滚）
     */
    private fun applyWithBackup(stage: File, mode: UpdateMode, inner: PackageManifest): String? {
        val codedbDir = File(context.filesDir, CODEDB_DIR)
        val backupDir = File(context.filesDir, BACKUP_DIR)
        return try {
            // 1) 备份当前缓存（先清旧备份再复制）
            backupDir.deleteRecursively()
            if (codedbDir.isDirectory) codedbDir.copyRecursively(backupDir)
            // 2) 应用
            when (mode) {
                UpdateMode.FULL -> applyFull(stage, codedbDir)
                UpdateMode.INCREMENTAL -> applyIncremental(stage, codedbDir, inner.version)
            }
            // 3) 自检：索引可解析且版本非空（损坏则回滚）
            val check = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(IrextIndexData.serializer(), File(codedbDir, UpdateFormats.INDEX_FILE_NAME).readText())
            }.getOrElse { throw IllegalStateException("更新后的索引无法解析") }
            if (check.version.isBlank()) throw IllegalStateException("更新后的索引版本为空")
            null
        } catch (e: Exception) {
            // 回滚：删除半成品，恢复备份
            runCatching { codedbDir.deleteRecursively() }
            runCatching { if (backupDir.exists()) backupDir.copyRecursively(codedbDir) }
            e.message ?: "未知错误"
        }
    }

    /** 全量应用：整个 codedb 目录替换为包内索引 + 二进制（解压 zip 平铺到 binaries/） */
    private fun applyFull(stage: File, codedbDir: File) {
        codedbDir.deleteRecursively()
        codedbDir.mkdirs()
        File(stage, UpdateFormats.INDEX_FILE_NAME).copyTo(
            File(codedbDir, UpdateFormats.INDEX_FILE_NAME), overwrite = true,
        )
        val binDir = File(codedbDir, BINARIES_DIR).apply { mkdirs() }
        ZipInputStream(File(stage, UpdateFormats.BINARIES_ZIP_NAME).inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    if (name.endsWith(".bin")) {
                        File(binDir, name).outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * 增量应用：把增量清单条目合并进本地缓存索引 + 拷贝新增二进制。
     * baseVersion 匹配已在 downloadAndApply 前置校验，此处仍防御性复核。
     */
    private fun applyIncremental(stage: File, codedbDir: File, newVersion: String) {
        // 定位增量描述符 incremental/<base>-<new>.json
        val descriptorFile = stage.walkTopDown().firstOrNull {
            it.isFile && it.parentFile?.name == "incremental" && it.extension == "json"
        } ?: throw IllegalStateException("增量包缺少增量清单")
        val descriptor = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(IncrementalDescriptor.serializer(), descriptorFile.readText())
        }.getOrElse { throw IllegalStateException("增量清单解析失败") }
        if (descriptor.version != newVersion) throw IllegalStateException("增量清单版本与包不一致")
        // 合并索引：以本地缓存为底，同 id 大类整条替换、新大类追加，版本更新为目标版本
        val indexFile = File(codedbDir, UpdateFormats.INDEX_FILE_NAME)
        if (!indexFile.isFile) throw IllegalStateException("本地无缓存索引，无法增量合并")
        val current = Json { ignoreUnknownKeys = true }
            .decodeFromString(IrextIndexData.serializer(), indexFile.readText())
        val merged = current.copy(
            version = descriptor.version,
            categories = mergeCategories(current.categories, descriptor.categories),
        )
        indexFile.writeText(Json { ignoreUnknownKeys = true }.encodeToString(IrextIndexData.serializer(), merged))
        // 拷贝新增/修改二进制
        val binDir = File(codedbDir, BINARIES_DIR).apply { mkdirs() }
        descriptor.binaries.forEach { bin ->
            val name = bin.substringAfterLast('/')
            if (name.isBlank() || name.contains("..")) throw IllegalStateException("增量清单含非法二进制名 $bin")
            val src = File(stage, "binaries/$name")
            if (!src.isFile) throw IllegalStateException("增量包缺少二进制 $bin")
            src.copyTo(File(binDir, name), overwrite = true)
        }
    }

    // ---------- 内部工具 ----------

    /** 增量合并：以 [base] 为底，[delta] 中同 id 大类整条替换、新 id 追加（保持 base 顺序） */
    internal fun mergeCategories(
        base: List<IrextCategory>,
        delta: List<IrextCategory>,
    ): List<IrextCategory> {
        if (delta.isEmpty()) return base
        val deltaById = delta.associateBy { it.id }
        val existingIds = base.map { it.id }.toSet()
        return base.map { deltaById[it.id] ?: it } + delta.filter { it.id !in existingIds }
    }

    /** 版本比较："1.10" > "1.9"、"20260801" > "20260519"；段级数字比较，非数字段按 0 计 */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun hasLocalCache(): Boolean = cachedIndexFile().isFile

    private fun cachedIndexFile(): File = File(context.filesDir, "$CODEDB_DIR/${UpdateFormats.INDEX_FILE_NAME}")

    /** 小 JSON 拉取 + 反序列化（解析失败返回 null） */
    private inline fun <reified T> fetchJson(url: String): T? {
        val bytes = fetchBytes(url) ?: return null
        return runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<T>(bytes.decodeToString())
        }.getOrNull()
    }

    private fun <T> readJson(serializer: kotlinx.serialization.KSerializer<T>, file: File): T? =
        runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer, file.readText())
        }.getOrNull()

    /** 远程文件名 → 完整下载 URL */
    private fun releaseUrl(pkgName: String): String =
        "$RELEASE_BASE/${pkgName.removePrefix("/")}"

    // ---------- 网络实现（HttpURLConnection，API 24 兼容） ----------

    private fun httpGet(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true   // GitHub release 下载会 302 跳转
            conn.setRequestProperty("User-Agent", "PhotonRemote-Update/1.0")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** 流式下载到 [target]，按 Content-Length 汇报进度 */
    private fun httpDownload(url: String, target: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PhotonRemote-Update/1.0")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
                val total = conn.contentLengthLong
                var read = 0L
                target.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 文件 SHA-256（小写十六进制） */
    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 数据包托管地址：项目自己的 GitHub Release 镜像（见 README 码库声明；
         *  IREXT 官方源 github.com/irext 为优先源，发布流程同步发布到两处） */
        const val RELEASE_BASE = "https://github.com/WXFffff666/photon-remote/releases/latest/download"

        /** 远程清单路径（Release 根目录） */
        const val REMOTE_MANIFEST_URL = "$RELEASE_BASE/manifest.json"

        /** assets 内索引路径（builtinVersion 读取用） */
        const val ASSET_INDEX_PATH = "irext/irext-index.json"

        /** filesDir 缓存根目录（IrextIndexLoader/BinaryStore 优先读取） */
        const val CODEDB_DIR = "codedb"

        /** 二进制缓存子目录 */
        const val BINARIES_DIR = "binaries"

        /** 回滚备份目录 */
        const val BACKUP_DIR = "codedb-backup"

        /** 网络超时（连接 + 读取，毫秒） */
        const val TIMEOUT_MS = 15_000
    }
}
