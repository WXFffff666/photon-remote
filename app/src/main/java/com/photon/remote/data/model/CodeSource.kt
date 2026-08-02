package com.photon.remote.data.model

/**
 * 码库来源枚举（计划 §2.1）。
 *
 * 决定 Device.codeRef 的语义：
 * - IREXT：码库 bin 文件名（如 "ab12cd34.bin"）
 * - IRDB：irdb CSV 相对路径
 * - FLIPPER / LIRC：导入文件内容标识
 * - CUSTOM：用户自定义（手动添加的按钮）
 */
enum class CodeSource {
    IREXT, IRDB, FLIPPER, LIRC, CUSTOM,
}
