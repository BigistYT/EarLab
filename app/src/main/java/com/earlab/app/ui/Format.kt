package com.earlab.app.ui

fun timeAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return "—"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "только что"
        s < 3600 -> "${s / 60} мин назад"
        s < 86_400 -> "${s / 3600} ч назад"
        else -> "${s / 86_400} дн назад"
    }
}

fun durationText(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
}

fun hoursText(h: Float): String = durationText((h * 60f).toLong() * 60_000L)
