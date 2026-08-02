package com.photon.remote.data.model

/**
 * 按键形状枚举（计划 §2.2 RemoteButton.shape）。
 *
 * ROUNDED = 圆角矩形（默认），CIRCLE = 圆形。
 */
enum class ButtonShape(val label: String) {
    ROUNDED("圆角矩形"),
    CIRCLE("圆形"),
}
