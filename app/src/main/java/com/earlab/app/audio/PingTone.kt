package com.earlab.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * «Позвонить» в наушники: пищит двойным сигналом, громкость нарастает плавно.
 * Системную громкость не трогаем, амплитуда ограничена, максимум 90 секунд.
 */
class PingTone(private val ctx: Context) {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var stopFlag = false
    private var thread: Thread? = null

    fun start(address: String?) {
        if (_active.value) return
        stopFlag = false
        _active.value = true
        thread = Thread { run(address) }.also {
            it.name = "EarLab-ping"
            it.start()
        }
    }

    fun stop() {
        stopFlag = true
    }

    private fun run(address: String?) {
        var track: AudioTrack? = null
        try {
            val rate = 44_100
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track = t

            preferredDevice(address)?.let { t.setPreferredDevice(it) }
            t.play()

            val startedAt = System.currentTimeMillis()
            val beep = tone(rate, 2400.0, 160)
            val gap = ShortArray(rate * 90 / 1000)
            val pause = ShortArray(rate * 650 / 1000)

            while (!stopFlag) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed > 90_000) break
                // от 8% до 60% полной шкалы за 25 секунд
                val amp = 0.08f + 0.52f * (elapsed / 25_000f).coerceAtMost(1f)
                t.write(scale(beep, amp), 0, beep.size)
                t.write(gap, 0, gap.size)
                t.write(scale(beep, amp), 0, beep.size)
                t.write(pause, 0, pause.size)
            }
        } catch (t: Throwable) {
            // нет маршрута/звука — просто завершаемся
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            _active.value = false
        }
    }

    private fun preferredDevice(address: String?): AudioDeviceInfo? {
        if (address == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.address.equals(address, ignoreCase = true) &&
                (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
    }

    private fun tone(rate: Int, freq: Double, ms: Int): ShortArray {
        val n = rate * ms / 1000
        val fade = rate * 6 / 1000 // 6 мс — чтобы не щёлкало
        return ShortArray(n) { i ->
            val env = when {
                i < fade -> i / fade.toFloat()
                i > n - fade -> (n - i) / fade.toFloat()
                else -> 1f
            }
            (sin(2 * PI * freq * i / rate) * env * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun scale(src: ShortArray, k: Float): ShortArray = ShortArray(src.size) { (src[it] * k).toInt().toShort() }
}
