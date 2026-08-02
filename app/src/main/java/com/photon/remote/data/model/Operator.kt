package com.photon.remote.data.model

/**
 * 运营商枚举（计划 §2.1，仅机顶盒设备使用）。
 */
enum class Operator(val label: String) {
    CMCC("中国移动"),
    CUCC("中国联通"),
    CTCC("中国电信"),
    CABLENET("中国广电"),
}
