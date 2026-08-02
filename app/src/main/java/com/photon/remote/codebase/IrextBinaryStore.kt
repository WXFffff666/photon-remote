package com.photon.remote.codebase

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.irext.decode.sdk.utils.Constants
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * IREXT 二进制码组包装对象（计划 §4.3 / Todo 19）。
 *
 * 除 [bytes] 外携带码组元数据 [category]/[subCate]（IrextDecoder.open 必需，
 * 由 IrextBinaryStore.load 从索引解析）。
 */
data class IrextBinaryRef(
    /** 二进制文件名（= Device.codeRef，规则 d 会话恢复用） */
    val binaryName: String,
    /** 二进制内容 */
    val bytes: ByteArray,
    /** 设备大类（irext Constants.CategoryID：1=AC 2=TV 3=STB…） */
    val category: Int,
    /** 子类（AC=0；TV/STB 等命令类=1；索引 JSON 无 sub_cate 字段，按 irext 约定取值） */
    val subCate: Int,
)

/**
 * IREXT 二进制码库按需读取（计划 §4.3 / Todo 19）。
 *
 * assets/irext/irext-binaries.zip（官方 1.76MB、5126 个 .bin，根目录
 * irext-binaries_<version>/）按需解压读取 + 内存 LRU 缓存：
 *   - [load] 返回 [IrextBinaryRef]（bytes + binaryName + category + subCate）；
 *   - category/subCate 从索引（IrextIndexLoader）解析，索引查不到时按 bin 文件名
 *     启发式兜底（irext bin 命名含品类片段，如 _ac_ / _box_ / _tv_）；
 *   - 全部 IO/解析异常一律返回 null，绝不崩溃（与 IrextDecoder 降级约定一致）。
 *
 * 线程模型：本类内部对缓存加锁，zip 读取在 [load] 的 IO 调度内执行；
 * open/decode 仍须经 IrDispatcher 串行（计划 §3.4，本类不参与）。
 */
class IrextBinaryStore(
    private val context: Context,
    private val indexLoader: IrextIndexLoader,
) {

    /** 缓存锁（zip 读取与缓存读写均须串行，防止并发重复解压） */
    private val lock = Any()

    /** 按需解压后的内存缓存：访问序 LinkedHashMap（LRU），容量上限见 [MAX_CACHE_ENTRIES] */
    private val byteCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)

    /** zip 根目录前缀（首次读取时发现，如 "irext-binaries_20260519/"） */
    private var rootPrefix: String? = null

    /**
     * 按 bin 文件名读取码组。
     *
     * @param ref 设备 codeRef（bin 文件名，如 "irda_new_ac_9377.bin"）
     * @return 包装对象；zip 无此文件 / 索引查不到类别 / 任何异常返回 null
     */
    suspend fun load(ref: String): IrextBinaryRef? = withContext(Dispatchers.IO) {
        val bytes = readCached(ref) ?: readFromZip(ref)?.also { putCached(ref, it) }
            ?: return@withContext null
        // category/subCate 从索引 remote 记录解析（Todo 19 验收：包装对象含两者）
        val category = indexLoader.categoryIdOf(ref) ?: categoryFromBinName(ref)
            ?: return@withContext null
        val subCate = if (category == Constants.CategoryID.AIR_CONDITIONER.getValue()) 0 else 1
        IrextBinaryRef(ref, bytes, category, subCate)
    }

    // ---------- zip 按需解压 ----------

    private fun readFromZip(ref: String): ByteArray? {
        val assets = context.assets
        // 优先按已知根目录前缀直接定位（避免 5126 条全量扫描）
        val prefix = rootPrefix ?: discoverRootPrefix(assets)
        val exact = prefix?.let { findEntry(assets) { name -> name == "$it$ref" } }
        if (exact != null) return exact
        // 前缀不匹配（版本变化等）：退化为按文件名后缀全量扫描
        return findEntry(assets) { name -> name.substringAfterLast('/') == ref }
    }

    /** 发现 zip 根目录前缀（取第一个 entry 的目录段），结果缓存 */
    private fun discoverRootPrefix(assets: AssetManager): String? {
        val prefix = try {
            ZipInputStream(assets.open(ZIP_ASSET)).use { zip ->
                val first = zip.nextEntry ?: return null
                first.name.substringBefore('/').takeIf { it.isNotEmpty() }?.let { "$it/" }
            }
        } catch (e: Exception) {
            null   // assets 缺失/损坏：返回 null，load 走全量扫描后同样失败
        }
        rootPrefix = prefix
        return prefix
    }

    /** 遍历 zip 条目，命中 [match] 即读出全部字节；无命中/异常返回 null */
    private fun findEntry(assets: AssetManager, match: (String) -> Boolean): ByteArray? = try {
        ZipInputStream(assets.open(ZIP_ASSET)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (match(name)) {
                    return@use readAll(zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    } catch (e: Exception) {
        null   // 资源缺失/损坏 zip：绝不崩溃，返回 null
    }

    /** 从当前 entry 读到 EOF（ZipInputStream 的 entry.size 不可靠，手动流式读取） */
    private fun readAll(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val buf = ByteArray(4096)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------- 内存缓存（LRU，容量上限防内存膨胀） ----------

    private fun readCached(ref: String): ByteArray? = synchronized(lock) { byteCache[ref] }

    private fun putCached(ref: String, bytes: ByteArray) {
        synchronized(lock) {
            byteCache[ref] = bytes
            while (byteCache.size > MAX_CACHE_ENTRIES) {
                // 访问序 LinkedHashMap：keys() 头部即最久未使用
                val oldest = byteCache.keys.firstOrNull() ?: break
                byteCache.remove(oldest)
            }
        }
    }

    /**
     * bin 文件名启发式推断大类（索引查不到时的兜底）。
     *
     * irext bin 命名含品类片段：_ac_（空调）、_box_（机顶盒）、_tv_（电视）、
     * _dvd_（DVD）等；空调另有 "acleaner"（空气净化器）命名。顺序敏感：
     * box 优先于 tv（hddvb 等名称含 "tv" 片段但实际是 box）。
     */
    private fun categoryFromBinName(bin: String): Int? = when {
        bin.contains("_box_") -> Constants.CategoryID.STB.getValue()
        bin.contains("_ac_") || bin.contains("acleaner") -> Constants.CategoryID.AIR_CONDITIONER.getValue()
        bin.contains("_tv_") || bin.endsWith("_tv") -> Constants.CategoryID.TV.getValue()
        bin.contains("_dvd_") -> Constants.CategoryID.DVD.getValue()
        else -> null
    }

    private companion object {
        /** assets 内 zip 路径（计划 §1） */
        const val ZIP_ASSET = "irext/irext-binaries.zip"

        /** 内存缓存条数上限（单个 .bin 数 KB~数十 KB，24 条约几百 KB 量级） */
        const val MAX_CACHE_ENTRIES = 24
    }
}
