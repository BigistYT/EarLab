package com.earlab.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.earlab.app.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RadarState {
    data object Idle : RadarState
    data object Connecting : RadarState

    /** [trend]: +1 — становится теплее, -1 — холоднее, 0 — без изменений. */
    data class Tracking(val rssi: Int, val proximity: Float, val trend: Int) : RadarState
    data class Failed(val reason: String) : RadarState
}

/**
 * «Тепло–холодно»: подключаемся к наушникам по Bluetooth LE и раз в ~350 мс читаем RSSI.
 * Работает только с наушниками, которые принимают LE-соединение (многие TWS — да, классические — нет).
 */
@SuppressLint("MissingPermission")
class Radar(private val ctx: Context) {

    private val _state = MutableStateFlow<RadarState>(RadarState.Idle)
    val state: StateFlow<RadarState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var smoothed = Float.NaN
    private val recent = ArrayDeque<Float>()

    private val connectTimeout = Runnable {
        if (_state.value is RadarState.Connecting) {
            fail("Не удалось подключиться по Bluetooth LE. Возможно, эти наушники не поддерживают радар.")
        }
    }
    private val poll = Runnable { if (running) gatt?.readRemoteRssi() }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (!running) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    handler.removeCallbacks(connectTimeout)
                    g.readRemoteRssi()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Связь с наушниками потеряна. Попробуйте подойти ближе и запустить радар снова.")
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            handler.post {
                if (!running) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) update(rssi)
                handler.postDelayed(poll, 350)
            }
        }
    }

    fun start(address: String) {
        stop()
        if (!Perms.btConnect(ctx)) {
            fail("Нет разрешения на Bluetooth.")
            return
        }
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Включите Bluetooth.")
            return
        }
        running = true
        smoothed = Float.NaN
        recent.clear()
        _state.value = RadarState.Connecting
        try {
            gatt = adapter.getRemoteDevice(address)
                .connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            fail("Не удалось запустить радар: ${e.message ?: "ошибка Bluetooth"}")
            return
        }
        handler.postDelayed(connectTimeout, 12_000)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        _state.value = RadarState.Idle
    }

    private fun fail(reason: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        _state.value = RadarState.Failed(reason)
    }

    private fun closeGatt() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private fun update(rssi: Int) {
        smoothed = if (smoothed.isNaN()) rssi.toFloat() else smoothed * 0.7f + rssi * 0.3f
        recent.addLast(smoothed)
        if (recent.size > 6) recent.removeFirst()
        val delta = recent.last() - recent.first()
        val trend = when {
            recent.size < 4 -> 0
            delta > 2f -> 1
            delta < -2f -> -1
            else -> 0
        }
        // -95 дБм ≈ «очень далеко», -40 дБм ≈ «в руках»
        val proximity = ((smoothed + 95f) / 55f).coerceIn(0f, 1f)
        _state.value = RadarState.Tracking(smoothed.toInt(), proximity, trend)
    }
}
