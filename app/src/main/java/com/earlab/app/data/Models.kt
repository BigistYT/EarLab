package com.earlab.app.data

/** Что делать автоматически при подключении конкретных наушников. */
data class Automation(
    val presetName: String? = null,   // какой пресет эквалайзера включить
    val volumePercent: Int = -1,      // -1 = не трогать громкость
    val autoPlay: Boolean = false,    // запустить музыку после подключения
    val lowBatteryAlert: Int = 20,    // 0 = не предупреждать
    val breakReminderMin: Int = 0,    // 0 = без напоминаний об отдыхе для ушей
)

data class HeadphoneProfile(
    val address: String,
    val name: String,
    val battery: Int = -1,            // -1 = неизвестно
    val connected: Boolean = false,
    val lastSeen: Long = 0L,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val locTime: Long = 0L,
    val automation: Automation = Automation(),
)

data class BatterySample(val address: String, val time: Long, val percent: Int)

/** Сессия прослушивания. end == 0 — сессия ещё идёт. */
data class ListenSession(val address: String, val start: Long, val end: Long)

/**
 * Кривая эквалайзера хранится нормализованной (-1..1) для 5 канонических частот.
 * На устройстве с любым числом полос она пересчитывается по частоте.
 */
data class EqPreset(
    val name: String,
    val curve: List<Float>,
    val bass: Float,
    val virtualizer: Float,
    val loudness: Float,
    val builtIn: Boolean = false,
)

data class EqState(
    val enabled: Boolean = true,
    val curve: List<Float> = EqDefaults.FLAT,
    val bass: Float = 0f,
    val virtualizer: Float = 0f,
    val loudness: Float = 0f,
    val presetName: String? = "Плоский",
)

object EqDefaults {
    const val BANDS = 5
    val FREQS = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    val FREQ_LABELS = listOf("60", "230", "910", "3.6k", "14k")
    val FLAT: List<Float> = List(BANDS) { 0f }

    val BUILT_IN = listOf(
        EqPreset("Плоский", FLAT, 0f, 0f, 0f, true),
        EqPreset("Бас+", listOf(0.65f, 0.4f, 0f, 0f, 0f), 0.45f, 0f, 0f, true),
        EqPreset("Вокал", listOf(-0.3f, -0.1f, 0.35f, 0.4f, 0.1f), 0f, 0f, 0f, true),
        EqPreset("Яркие верха", listOf(-0.1f, 0f, 0f, 0.4f, 0.65f), 0f, 0.2f, 0f, true),
        EqPreset("Рок", listOf(0.45f, 0.2f, -0.25f, 0.3f, 0.5f), 0.15f, 0f, 0f, true),
        EqPreset("Электроника", listOf(0.55f, 0.3f, 0f, 0.2f, 0.4f), 0.3f, 0.25f, 0f, true),
        EqPreset("Подкаст", listOf(-0.5f, -0.1f, 0.45f, 0.35f, -0.2f), 0f, 0f, 0.25f, true),
        EqPreset("Ночь", listOf(0.3f, 0.1f, -0.1f, -0.25f, -0.45f), 0f, 0f, 0f, true),
    )

    /** Значение кривой (-1..1) на произвольной частоте — интерполяция по логарифму частоты. */
    fun sample(curve: List<Float>, freqHz: Float): Float {
        val c = if (curve.size == BANDS) curve else FLAT
        if (freqHz <= FREQS.first()) return c.first()
        if (freqHz >= FREQS.last()) return c.last()
        for (i in 0 until BANDS - 1) {
            val f0 = FREQS[i]
            val f1 = FREQS[i + 1]
            if (freqHz in f0..f1) {
                val t = (kotlin.math.ln(freqHz) - kotlin.math.ln(f0)) / (kotlin.math.ln(f1) - kotlin.math.ln(f0))
                return c[i] + (c[i + 1] - c[i]) * t
            }
        }
        return 0f
    }
}
