package com.earlab.app.service

import kotlinx.coroutines.flow.MutableStateFlow

/** Состояние, которое служба сообщает интерфейсу (в памяти, не сохраняется). */
object ServiceState {
    /** null — ещё не знаем, true/false — удалось ли создать аудиоэффекты. */
    val effectsAvailable = MutableStateFlow<Boolean?>(null)
    val serviceRunning = MutableStateFlow(false)
}
