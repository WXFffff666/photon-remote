package com.photon.remote.ir.transmitter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 音频转红外发射器（计划 §3.4）——无红外手机也能用的关键路径（USB/音频适配器演示）。
 *
 * 用 AudioTrack 播放 192kHz 16bit PCM 方波（mark 段 38kHz 载波 ≈ 5.05 采样/周期，
 * 见 [AudioPcmBuilder]）；个别设备不支持 192kHz 时自动回退 96kHz（≈2.53 采样/周期，可接受近似，
 * 设置页可标注）。支持 mono 1-LED 与 stereo anti-phase 2-LED 两种适配器（[stereoMode] 切换）。
 *
 * 使用提示（UI 层展示）：需插入音频转红外 LED 适配器，并将音量开到最大。
 * 发送经 [IrDispatcher] 后台串行执行（MODE_STATIC 播放期间本线程轮询等待完成）。
 */
class AudioIrTransmitter(
    /** 初始模式：true = stereo 反相 2-LED，false = mono 1-LED（设置页可切换，见 [stereoMode]） */
    stereo: Boolean = false
) : IRTransmitter {

    override val displayName: String = "音频转红外"

    /** 1LED（mono）/ 2LED（stereo 反相）模式；设置页联动 SettingsStore.audioMode（"1LED"/"2LED"）时赋值 */
    @Volatile
    var stereoMode: Boolean = stereo

    /** 真实探测：尝试创建 AudioTrack 并检查 STATE_INITIALIZED，否则 false（无音频能力/采样率不支持时为 false） */
    override val isAvailable: Boolean
        get() = runCatching {
            val rate = 96_000
            val mask = AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0 || minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) return@runCatching false
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(mask)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                track.state == AudioTrack.STATE_INITIALIZED
            } finally {
                track.release()
            }
        }.getOrDefault(false)

    /** 探测可用采样率：192kHz 优先，不支持则回退 96kHz（结果缓存） */
    private val supportedSampleRate: Int by lazy { probe(192_000) ?: probe(96_000) ?: 96_000 }

    /**
     * 同步发送：合成 PCM → AudioTrack 播放至整帧结束。192kHz 失败自动回退 96kHz 重试。
     * 由 IrDispatcher 后台队列调用；内部以非阻塞 delay 等待播放完成（不占用 Dispatchers.Default 线程的阻塞等待）。
     */
    override fun transmit(pattern: IRPattern): Boolean {
        val stereo = stereoMode
        val rates = linkedSetOf(supportedSampleRate, 96_000)   // 回退链（distinct）
        for (rate in rates) {
            val ok = try {
                val pcm = AudioPcmBuilder.build(pattern, rate, stereo)
                runBlocking { playPcm(pcm, rate, stereo) }
            } catch (e: Exception) {
                false   // 设备不支持该采样率 / 构建 AudioTrack 失败 → 尝试下一档
            }
            if (ok) return true
        }
        return false
    }

    /** 探测采样率是否受支持（getMinBufferSize 返回错误码即不支持） */
    private fun probe(rate: Int): Int? = try {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0 || min == AudioTrack.ERROR || min == AudioTrack.ERROR_BAD_VALUE) null else rate
    } catch (e: Exception) {
        null
    }

    /** 创建 AudioTrack（MODE_STATIC）→ 整段写入 → play → 非阻塞等待播放完成 → release */
    private suspend fun playPcm(pcm: ShortArray, sampleRate: Int, stereo: Boolean): Boolean {
        val channels = if (stereo) 2 else 1
        val mask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0 || minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) return false

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            return false
        }
        return try {
            if (track.write(pcm, 0, pcm.size) != pcm.size) return false
            track.play()
            // MODE_STATIC 播完自动停止；非阻塞轮询等待完成（协程 delay，不阻塞 IrDispatcher 的 Dispatchers.Default 线程）
            val durationMs = pcm.size * 1000L / sampleRate / channels
            val deadline = System.currentTimeMillis() + durationMs + 300
            while (track.playState != AudioTrack.PLAYSTATE_STOPPED && System.currentTimeMillis() < deadline) {
                delay(2)
            }
            true
        } finally {
            track.release()
        }
    }
}
