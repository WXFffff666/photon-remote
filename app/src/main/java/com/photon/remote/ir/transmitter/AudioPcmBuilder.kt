package com.photon.remote.ir.transmitter

import com.photon.remote.ir.core.IRPattern

/**
 * 音频转红外 PCM 合成器（计划 §3.4）——纯函数，无 Android 依赖，便于 JVM 单测。
 *
 * 原理：38kHz 红外载波在 192kHz 采样率下 ≈ 5.05 采样/周期（48kHz 下仅 1.26 采样/周期，
 * 低于奈奎斯特无法成形）。mark 时段输出 38kHz 满幅方波（±1），space 时段输出 0（静音）。
 *
 * 方波用**相位累加器**合成：每采样相位 += 载波频率 / 采样率，相位 < 0.5 输出 +满幅、
 * 否则输出 -满幅。这样任意采样率下实际载波频率都精确等于 38kHz（192kHz 时每周期 ≈5.05 采样，
 * 96kHz 时 ≈2.53 采样/周期，均可成形）。
 *
 * 声道模式：
 *  - mono（1-LED 适配器）：单声道，载波方波
 *  - stereo（2-LED 适配器）：左右声道**反相**（一 LED 正相、另一 LED 反相），space 段双声道静音
 */
object AudioPcmBuilder {

    /** 红外载波频率（标准 38kHz） */
    const val CARRIER_HZ = 38_000

    /** 满幅正电平（16bit PCM 最大值） */
    private const val FULL = Short.MAX_VALUE.toInt()   // +32767

    /** 满幅负电平 */
    private const val NEG_FULL = -FULL                 // -32767

    /**
     * 合成 PCM 样本。
     *
     * @param pattern 红外波形（intervals 以 mark 开头、space 结尾）
     * @param sampleRateHz 采样率（192000 或 96000 回退）
     * @param stereo true = stereo 反相 2-LED；false = mono 1-LED
     * @return 16bit PCM ShortArray（stereo 时为左右交错 [L,R,L,R...]）
     */
    fun build(pattern: IRPattern, sampleRateHz: Int, stereo: Boolean = false): ShortArray {
        require(sampleRateHz >= CARRIER_HZ * 2) { "采样率过低: $sampleRateHz Hz 无法承载 38kHz 载波" }

        // 按累积时长换算样本边界，保证总样本数 = floor(总时长µs × 采样率 / 1e6)（每段分别取整会累积误差）
        val totalUs = pattern.intervals.sumOf { it.toLong() }
        val totalSamples = (totalUs * sampleRateHz / 1_000_000L).toInt()
        val mono = ShortArray(totalSamples)

        val phaseStep = CARRIER_HZ.toDouble() / sampleRateHz   // 每采样相位增量（0..1 为一个周期）
        var phase = 0.0
        var sampleIdx = 0
        var cumUs = 0L
        for ((i, us) in pattern.intervals.withIndex()) {
            cumUs += us
            val endSample = (cumUs * sampleRateHz / 1_000_000L).toInt()
            val n = (endSample - sampleIdx).coerceAtLeast(0)
            if (i % 2 == 0) {
                // mark 段：38kHz 方波（±1 满幅）
                repeat(n) {
                    mono[sampleIdx] = if (phase < 0.5) FULL.toShort() else NEG_FULL.toShort()
                    sampleIdx++
                    phase = (phase + phaseStep) % 1.0
                }
            } else {
                // space 段：静音
                repeat(n) { mono[sampleIdx] = 0; sampleIdx++ }
            }
        }
        return if (stereo) stereoAntiPhase(mono) else mono
    }

    /** stereo 反相：右声道 = -左声道（2-LED 适配器）；space 段两边都是 0，取反后仍是 0 */
    private fun stereoAntiPhase(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = (-mono[i].toInt()).toShort()
        }
        return out
    }
}
