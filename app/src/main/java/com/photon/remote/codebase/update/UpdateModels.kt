package com.photon.remote.codebase.update

import com.photon.remote.codebase.IrextCategory
import kotlinx.serialization.Serializable

/**
 * 码库在线更新数据模型（计划 Todo 50）。
 *
 * 数据包格式（本 App 定义，formatVersion = 1）：
 * - 远程清单（Release 根目录）`manifest.json`：仅声明最新版本与包名，轻量（几百字节）；
 * - 全量包 `codedb-v<版本>.zip`：
 *   - `manifest.json`（[PackageManifest]：版本 + 各文件 SHA-256 + 变更说明 + baseVersion）
 *   - `irext-index.json`（全量索引）
 *   - `irext-binaries.zip`（全量二进制，zip 内根目录 irext-binaries_<版本>/）
 *   - `incremental/<baseVersion>-<newVersion>.json`（增量清单，见 [IncrementalDescriptor]）
 * - 增量包 `codedb-incr-<baseVersion>-<newVersion>.zip`：
 *   - `manifest.json`（[PackageManifest]：files 只列增量涉及的文件）
 *   - `incremental/<baseVersion>-<newVersion>.json`（增量清单：新增/修改的
 *     categories/brands/remotes 条目 + 对应二进制文件名）
 *   - `binaries/<文件名>.bin`（新增/修改的二进制）
 *
 * 安全约定：每个下载文件都以 SHA-256 与包内 manifest 比对，任一不匹配整包拒绝；
 * 应用新版本前备份旧缓存，失败回滚，内置 assets 永不写入。
 */
object UpdateFormats {

    /** 数据包格式版本（本 App 定义，当前唯一版本） */
    const val FORMAT_VERSION = 1

    /** 包内索引文件名（与 assets 同名，缓存目录 codedb/ 内） */
    const val INDEX_FILE_NAME = "irext-index.json"

    /** 包内全量二进制 zip 名 */
    const val BINARIES_ZIP_NAME = "irext-binaries.zip"

    /** 全量包名前缀：codedb-v<版本>.zip */
    const val FULL_PACKAGE_PREFIX = "codedb-v"

    /** 增量包名前缀：codedb-incr-<base>-<new>.zip */
    const val INCREMENTAL_PACKAGE_PREFIX = "codedb-incr-"
}

/**
 * 远程清单（Release 根目录 manifest.json）。
 *
 * 仅承载「哪个版本最新 + 包名」，下载后真正的逐文件 SHA-256 校验
 * 以包内 [PackageManifest] 为准（防远程清单被篡改后包内文件不一致）。
 */
@Serializable
data class UpdateManifest(
    /** 数据包格式版本（= [UpdateFormats.FORMAT_VERSION]） */
    val formatVersion: Int = UpdateFormats.FORMAT_VERSION,
    /** 最新码库版本（如 "20260519" / "1.1"） */
    val version: String = "",
    /** 增量包的基础版本（本地版本必须与之相等才能走增量，否则回退全量） */
    val baseVersion: String = "",
    /** 变更说明（设置页更新对话框展示） */
    val changelog: String = "",
    /** 全量包文件名（如 codedb-v20260801.zip） */
    val fullPackage: String = "",
    /** 增量包文件名（如 codedb-incr-20260519-20260801.zip；无增量包时为空串） */
    val incrementalPackage: String = "",
)

/**
 * 包内清单（全量包 / 增量包 zip 内的 manifest.json）。
 *
 * [files]：相对 zip 内路径 → SHA-256 十六进制小写。CodebaseUpdater 解压后
 * 逐文件比对，任一不匹配 → 整包拒绝（不落盘/删除已落盘）。
 */
@Serializable
data class PackageManifest(
    val formatVersion: Int = UpdateFormats.FORMAT_VERSION,
    /** 本包版本（须与远程清单 version 一致，防错包） */
    val version: String = "",
    /** 增量包的基础版本（全量包可为空） */
    val baseVersion: String = "",
    val changelog: String = "",
    /** 文件相对路径 → SHA-256（小写十六进制） */
    val files: Map<String, String> = emptyMap(),
)

/**
 * 增量清单（增量包内 incremental/<baseVersion>-<newVersion>.json）。
 *
 * 合并语义：以本地 filesDir 缓存索引为底，按大类 id 整条替换增量 categories、
 * 追加新大类；[binaries] 为新增/修改的二进制文件名（对应包内 binaries/ 目录）。
 */
@Serializable
data class IncrementalDescriptor(
    /** 基础版本（合并前须与本地缓存版本相等） */
    val baseVersion: String = "",
    /** 目标版本 */
    val version: String = "",
    /** 新增/修改的完整大类条目（含品牌/省市运营商/遥控器子树） */
    val categories: List<IrextCategory> = emptyList(),
    /** 新增/修改的二进制文件名（不含路径，如 "irda_new_ac_9377.bin"） */
    val binaries: List<String> = emptyList(),
)

/** 更新方式：FULL=全量替换 / INCREMENTAL=增量合并（由 CodebaseUpdater 自动选择） */
enum class UpdateMode { FULL, INCREMENTAL }

/**
 * 更新操作结果（checkForUpdate / downloadAndApply 共用）。
 *
 * 失败一律携带**可读中文原因**，UI 直接展示；更新失败时旧版本必然可用
 * （回滚保证），提示语由 ViewModel 拼装。
 */
sealed interface UpdateResult {

    /** 已是最新版本，无需更新 */
    data object UpToDate : UpdateResult

    /** 发现新版本（更新前状态；[mode] 为自动选择的更新方式） */
    data class Available(
        val version: String,
        val changelog: String,
        val mode: UpdateMode,
    ) : UpdateResult

    /** 更新成功（[mode] 为实际执行的方式——增量条件不满足时可能从增量回退为全量） */
    data class Succeeded(val version: String, val mode: UpdateMode) : UpdateResult

    /** 失败（可读中文原因；旧版本已保留可用） */
    data class Failed(val reason: String) : UpdateResult
}
