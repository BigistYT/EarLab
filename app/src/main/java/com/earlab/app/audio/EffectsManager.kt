package com.earlab.app.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.earlab.app.data.EqDefaults
import com.earlab.app.data.EqState
import kotlin.math.roundToInt

/**
 * Применяет эквалайзер, бас, объём и «громкость+» к аудио.
 *
 *  • Сессия 0 — глобальный микс. На многих телефонах работает, но часть производителей
 *    (и новые версии Android) её игнорируют.
 *  • Плееры, которые рассылают ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (VLC, многие
 *    музыкальные плееры), подключаются к своей сессии через [attachSession].
 */
class EffectsManager {

    private class Chain(val session: Int) {
        var eq: Equalizer? = null
        var bass: BassBoost? = null
        var virt: Virtualizer? = null
        var loud: LoudnessEnhancer? = null

        init {
            eq = runCatching { Equalizer(0, session) }.getOrNull()
            bass = runCatching { BassBoost(0, session) }.getOrNull()
            virt = runCatching { Virtualizer(0, session) }.getOrNull()
            loud = runCatching { LoudnessEnhancer(session) }.getOrNull()
        }

        val ok get() = eq != null || bass != null || virt != null || loud != null

        fun release() {
            runCatching { eq?.release() }
            runCatching { bass?.release() }
            runCatching { virt?.release() }
            runCatching { loud?.release() }
            eq = null; bass = null; virt = null; loud = null
        }
    }

    private val chains = mutableMapOf<Int, Chain>()
    private var state = EqState()

    /** true, если удалось создать эффекты на глобальной сессии. */
    var globalOk = false
        private set

    fun start() {
        val c = Chain(0)
        if (c.ok) {
            chains[0] = c
            globalOk = true
            apply(state)
        } else {
            c.release()
        }
    }

    fun attachSession(session: Int) {
        if (session <= 0 || chains.containsKey(session)) return
        val c = Chain(session)
        if (c.ok) {
            chains[session] = c
            applyTo(c, state)
        } else {
            c.release()
        }
    }

    fun detachSession(session: Int) {
        if (session == 0) return
        chains.remove(session)?.release()
    }

    fun apply(s: EqState) {
        state = s
        chains.values.forEach { applyTo(it, s) }
    }

    fun release() {
        chains.values.forEach { it.release() }
        chains.clear()
        globalOk = false
    }

    private fun applyTo(chain: Chain, s: EqState) {
        chain.eq?.let { eq ->
            runCatching {
                val n = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minL = range[0].toInt()
                val maxL = range[1].toInt()
                for (b in 0 until n) {
                    val hz = eq.getCenterFreq(b.toShort()) / 1000f // милли-Гц -> Гц
                    val norm = EqDefaults.sample(s.curve, hz)
                    val mb = if (norm >= 0f) norm * maxL else norm * (-minL)
                    eq.setBandLevel(b.toShort(), mb.roundToInt().coerceIn(minL, maxL).toShort())
                }
                eq.setEnabled(s.enabled)
            }
        }
        chain.bass?.let { bb ->
            runCatching {
                if (bb.strengthSupported) bb.setStrength((s.bass * 1000f).roundToInt().coerceIn(0, 1000).toShort())
                bb.setEnabled(s.enabled && s.bass > 0f)
            }
        }
        chain.virt?.let { v ->
            runCatching {
                if (v.strengthSupported) v.setStrength((s.virtualizer * 1000f).roundToInt().coerceIn(0, 1000).toShort())
                v.setEnabled(s.enabled && s.virtualizer > 0f)
            }
        }
        chain.loud?.let { l ->
            runCatching {
                // максимум +8 дБ — выше уже риск для слуха и клиппинг
                l.setTargetGain((s.loudness * 800f).roundToInt().coerceIn(0, 800))
                l.setEnabled(s.enabled && s.loudness > 0f)
            }
        }
    }

    companion object {
        const val ACTION_OPEN = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        const val ACTION_CLOSE = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        const val EXTRA_SESSION = AudioEffect.EXTRA_AUDIO_SESSION
    }
}
