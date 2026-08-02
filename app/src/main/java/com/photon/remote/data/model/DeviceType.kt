package com.photon.remote.data.model

/**
 * 设备分类枚举（计划 §2.1）。
 *
 * label 为用户可见名称，icon 为图标键名（静态白名单，防 R8 裁剪，见计划 §5.5/Todo 40）。
 */
enum class DeviceType(val label: String, val icon: String) {
    TV("电视", "tv"),
    STB("机顶盒", "settop"),
    AC("空调", "ac"),
    FAN("风扇", "fan"),
    PROJECTOR("投影仪", "projector"),
    AUDIO("音响", "speaker"),
    PURIFIER("净化器", "air"),
    OTHER("其他", "other"),
}
