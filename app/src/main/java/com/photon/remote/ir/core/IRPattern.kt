package com.photon.remote.ir.core

/**
 * 红外波形数据类：载波频率（Hz）+ mark/space 间隔序列（微秒）。
 *
 * intervals 约定：偶数下标为 mark、奇数下标为 space，以 mark 开头（RAW 等特殊路径以 space 结尾见 §3.2 规则 2）。
 * 重写 equals/hashCode 使 IntArray 按内容比较（data class 默认按引用比较数组）。
 */
data class IRPattern(val frequency: Int, val intervals: IntArray) {
    override fun equals(other: Any?) = other is IRPattern && other.frequency == frequency &&
        other.intervals.contentEquals(intervals)
    override fun hashCode() = frequency * 31 + intervals.contentHashCode()
}
