package com.earlab.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.earlab.app.EarLabApp
import com.earlab.app.audio.EffectsManager
import com.earlab.app.bt.BtMonitor
import com.earlab.app.data.HeadphoneProfile
import com.earlab.app.util.LocationHelper
import com.earlab.app.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Фоновая служба: слушает подключения наушников, применяет автоматизации,
 * держит аудиоэффекты, предупреждает о низком заряде и запоминает, где наушники были в последний раз.
 */
class KeepAliveService : Service(), BtMonitor.Callbacks {

    private val store get() = (application as EarLabApp).store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var monitor: BtMonitor? = null
    private var effects: EffectsManager? = null
    private var initialized = false
    private var tickCount = 0
    private val breakJobs = mutableMapOf<String, Job>()
    private val lowAlerted = mutableSetOf<String>()

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EffectsManager.EXTRA_SESSION, -1)
            if (id <= 0) return
            when (intent.action) {
                EffectsManager.ACTION_OPEN -> effects?.attachSession(id)
                EffectsManager.ACTION_CLOSE -> effects?.detachSession(id)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // null-intent = системный перезапуск; из фона разрешён только безопасный тип службы.
        val safeMode = intent == null || intent.getBooleanExtra(EXTRA_BOOT, false)
        if (!promote(safeMode)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!initialized) init() else monitor?.sync()
        return START_STICKY
    }

    private fun promote(safeMode: Boolean): Boolean {
        Notifier.ensureChannels(this)
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // Тип «location» можно запросить, только пока приложение на экране.
        if (!safeMode && Perms.location(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE,
                Notifier.serviceNotification(this, "Жду подключения наушников"),
                type,
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun init() {
        initialized = true
        ServiceState.serviceRunning.value = true

        val fx = EffectsManager().also { it.start() }
        effects = fx
        ServiceState.effectsAvailable.value = fx.globalOk

        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply {
                addAction(EffectsManager.ACTION_OPEN)
                addAction(EffectsManager.ACTION_CLOSE)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        monitor = BtMonitor(applicationContext, store, this).also { it.start() }

        scope.launch { store.eq.collect { fx.apply(it) } }

        scope.launch {
            store.profiles
                .map { list ->
                    list.filter { it.connected }
                        .joinToString(" · ") { if (it.battery >= 0) "${it.name} ${it.battery}%" else it.name }
                }
                .distinctUntilChanged()
                .collect { text ->
                    Notifier.updateService(this@KeepAliveService, text.ifEmpty { "Жду подключения наушников" })
                }
        }

        scope.launch {
            while (isActive) {
                delay(60_000)
                monitor?.pollBattery()
                tickCount++
                if (tickCount % 5 == 0) trackLocation()
            }
        }
    }

    // ───────────────────────── BtMonitor.Callbacks ─────────────────────────

    override fun onConnected(p: HeadphoneProfile, fresh: Boolean) {
        val a = p.automation
        a.presetName?.let { store.applyPreset(it) }
        lowAlerted.remove(p.address)
        if (a.breakReminderMin > 0) startBreakTimer(p)
        trackLocation()

        if (!fresh) return // после перезапуска службы громкость и плеер не трогаем
        if (a.volumePercent >= 0) setVolume(a.volumePercent)
        if (a.autoPlay) {
            scope.launch {
                delay(1500) // даём аудиомаршруту переключиться на наушники
                sendPlay()
            }
        }
    }

    override fun onDisconnected(p: HeadphoneProfile) {
        breakJobs.remove(p.address)?.cancel()
        // Позиция телефона в момент отключения — лучшая подсказка, где остались наушники.
        LocationHelper.capture(this) { loc ->
            store.recordLocation(
                p.address, loc.latitude, loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy else null, loc.time,
            )
        }
    }

    override fun onBattery(p: HeadphoneProfile, level: Int) {
        val threshold = p.automation.lowBatteryAlert
        if (threshold <= 0) return
        if (level <= threshold) {
            if (lowAlerted.add(p.address)) Notifier.lowBattery(this, p, level)
        } else if (level > threshold + 5) {
            lowAlerted.remove(p.address)
        }
    }

    // ───────────────────────── Автоматизации ─────────────────────────

    private fun setVolume(percent: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).roundToInt(), 0)
    }

    private fun sendPlay() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    private fun startBreakTimer(p: HeadphoneProfile) {
        breakJobs.remove(p.address)?.cancel()
        val minutes = p.automation.breakReminderMin
        if (minutes <= 0) return
        breakJobs[p.address] = scope.launch {
            var elapsed = 0
            while (isActive) {
                delay(minutes * 60_000L)
                elapsed += minutes
                val name = store.profile(p.address)?.name ?: p.name
                Notifier.breakReminder(this@KeepAliveService, name, elapsed, p.address)
            }
        }
    }

    /** Пока наушники подключены, раз в ~5 минут обновляем «где они были в последний раз». */
    private fun trackLocation() {
        if (store.profiles.value.none { it.connected }) return
        LocationHelper.capture(this) { loc ->
            store.profiles.value.filter { it.connected }.forEach {
                store.recordLocation(
                    it.address, loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy else null, loc.time,
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        monitor?.stop()
        effects?.release()
        scope.cancel()
        ServiceState.serviceRunning.value = false
        ServiceState.effectsAvailable.value = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_BOOT = "from_boot"

        fun start(ctx: Context, fromBoot: Boolean = false) {
            val i = Intent(ctx, KeepAliveService::class.java).putExtra(EXTRA_BOOT, fromBoot)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
