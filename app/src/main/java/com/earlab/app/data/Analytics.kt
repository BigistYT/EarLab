package com.earlab.app.data

import java.util.Calendar

object Analytics {
    private const val MAX_GAP_MS = 45 * 60_000L

    /**
     * Средний расход заряда (%/час) за текущий «разряд» — с последней зарядки.
     * Считаем только активное время: паузы больше 45 минут (наушники были отключены) не учитываются.
     */
    fun drainPerHour(samples: List<BatterySample>): Float? {
        val s = samples.sortedBy { it.time }
        if (s.size < 2) return null
        var start = s.lastIndex
        while (start > 0 && s[start - 1].percent >= s[start].percent) start--
        val run = s.subList(start, s.size)
        if (run.size < 2) return null
        var activeMs = 0L
        for (i in 1 until run.size) {
            val dt = run[i].time - run[i - 1].time
            if (dt in 1..MAX_GAP_MS) activeMs += dt
        }
        val drop = run.first().percent - run.last().percent
        if (drop < 2 || activeMs < 10 * 60_000L) return null
        return drop / (activeMs / 3_600_000f)
    }

    fun hoursLeft(percent: Int, perHour: Float?): Float? {
        if (perHour == null || perHour <= 0.01f || percent < 0) return null
        return percent / perHour
    }

    /** Начала последних [days] суток (локальная полночь), от старых к новым. */
    fun dayStarts(now: Long, days: Int): List<Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val list = ArrayList<Long>()
        repeat(days) {
            list.add(0, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return list
    }

    /** Миллисекунды прослушивания по каждым из последних суток. */
    fun perDay(sessions: List<ListenSession>, now: Long, days: Int = 7): List<Long> {
        val starts = dayStarts(now, days)
        return starts.mapIndexed { i, from ->
            val to = if (i + 1 < starts.size) starts[i + 1] else from + 86_400_000L
            sessions.sumOf { overlap(it, from, to, now) }
        }
    }

    private fun overlap(s: ListenSession, from: Long, to: Long, now: Long): Long {
        val end = if (s.end == 0L) now else s.end
        return (minOf(end, to) - maxOf(s.start, from)).coerceAtLeast(0L)
    }
}
