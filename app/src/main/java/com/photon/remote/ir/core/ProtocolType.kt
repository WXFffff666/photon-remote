package com.photon.remote.ir.core

/**
 * 红外协议类型枚举（计划 §2.1）。
 *
 * 自实现 14 种协议（NEC 家族 / RC5 / RC6 / SONY / SAMSUNG / SHARP / JVC / KASEIKYO / PIONEER / RAW），
 * 各协议参数以计划 §3.2 表为准；本阶段（Todo 1-4）仅落地 NEC，其余随 Todo 9 补充。
 */
enum class ProtocolType {
    NEC, NECX1, NECX2, RC5, RC6, SONY12, SONY15, SONY20,
    SAMSUNG32, SHARP, JVC, KASEIKYO, PIONEER, RAW
}
