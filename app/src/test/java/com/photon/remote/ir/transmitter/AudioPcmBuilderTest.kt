package com.photon.remote.ir.transmitter

import com.photon.remote.ir.core.IRPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频转红外 PCM 合成器单元测试（计划 §3.4 / Todo 12 验收）。
 *
 * 断言：mark 段含 38kHz 载波（过零计数 ≈ 2 × 载波频率 × 时长，每周期两次过零）、
 * space 段全零、mono 与 stereo 反相、总长按采样率换算。
 */
class AudioPcmBuilderTest {

    private fun sign(v: Short): Int = when {
        v > 0 -> 1
        v < 0 -> -1
        else -> 0
    }

    /** 统计 [from, to) 区间内过零次数（相邻样本符号变化） */
    private fun zeroCrossings(samples: ShortArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from until to - 1) {
            if (sign(samples[i]) != sign(samples[i + 1])) count++
        }
        return count
    }

    @Test
    fun `mark 段含 38kHz 载波且 space 段全零`() {
        // 1ms mark + 1ms space，192kHz → 各 192 样本（192000/1e6 × 1000 = 192）
        val pcm = AudioPcmBuilder.build(IRPattern(38_000, intArrayOf(1000, 1000)), 192_000)
        assertEquals(384, pcm.size)

        // 过零次数 ≈ 2 × 38kHz × 1ms = 76（38 个周期 × 每周期 2 次过零），容差 ±4
        val crossings = zeroCrossings(pcm, 0, 192)
        assertTrue("mark 段过零次数异常: $crossings（期望 ≈76）", crossings in 72..80)

        // mark 段存在满幅方波（无静音样本）
        assertTrue(pcm.take(192).any { it != 0.toShort() })

        // space 段全零
        assertTrue(pcm.drop(192).all { it == 0.toShort() })
    }

    @Test
    fun `96kHz 回退采样率下载波频率仍正确`() {
        // 96kHz ≈ 2.53 采样/周期，相位累加器保证频率仍为 38kHz
        val pcm = AudioPcmBuilder.build(IRPattern(38_000, intArrayOf(1000, 1000)), 96_000)
        assertEquals(192, pcm.size)
        val crossings = zeroCrossings(pcm, 0, 96)
        assertTrue("96kHz 下过零次数异常: $crossings（期望 ≈76）", crossings in 72..80)
        assertTrue(pcm.drop(96).all { it == 0.toShort() })
    }

    @Test
    fun `stereo 模式右声道与左声道反相`() {
        val pcm = AudioPcmBuilder.build(IRPattern(38_000, intArrayOf(1000, 1000)), 192_000, stereo = true)
        assertEquals(384 * 2, pcm.size)

        // mark 段：右声道 = -左声道（反相）
        for (i in 0 until 192) {
            assertEquals("第 $i 组反相失败", (-pcm[i * 2].toInt()).toShort(), pcm[i * 2 + 1])
        }
        // space 段：双声道均静音
        for (i in 192 until 384) {
            assertEquals(0.toShort(), pcm[i * 2])
            assertEquals(0.toShort(), pcm[i * 2 + 1])
        }
    }

    @Test
    fun `NEC 整帧时长按采样率换算`() {
        // 前导 9000/4500 + 3 位 + 帧尾 562 + 补零 89116 = 108800µs
        val pattern = IRPattern(38_000, intArrayOf(9000, 4500, 562, 1687, 562, 562, 562, 1687, 562, 89_116))
        assertEquals(108_800, pattern.intervals.sum())
        // 108800µs × 192kHz / 1e6 = 20889.6 → 20889（累积边界取整，无逐段误差）
        val pcm = AudioPcmBuilder.build(pattern, 192_000)
        assertEquals(20_889, pcm.size)
    }

    @Test
    fun `非法采样率抛异常`() {
        // 48kHz 下 38kHz 载波低于奈奎斯特（1.26 采样/周期）无法成形，必须拒绝
        val thrown = try {
            AudioPcmBuilder.build(IRPattern(38_000, intArrayOf(562, 562)), 48_000)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("48kHz 应抛 IllegalArgumentException", thrown != null)
    }
}
