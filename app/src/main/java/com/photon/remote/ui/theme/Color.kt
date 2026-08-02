package com.photon.remote.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 强调色种子定义（计划 §5.1）。
 *
 * Material 3 基准 8 色：Android 12+ 默认动态取色跟随壁纸；
 * 低版本设备（或用户关闭动态取色）时用种子色生成配色。
 * 设置页色板 8 选 1 的持久化在 Todo 36 接入。
 */
val AccentSeeds = listOf(
    Color(0xFF6750A4), // 紫色（默认种子）
    Color(0xFF0B57D0), // 蓝色
    Color(0xFF00696D), // 青色
    Color(0xFF006E1C), // 绿色
    Color(0xFFB25300), // 橙色
    Color(0xFFBA1A1A), // 红色
    Color(0xFFC2006A), // 粉色
    Color(0xFF455A64), // 蓝灰
)

/** 默认强调色种子 */
val AccentSeed: Color = AccentSeeds[0]
