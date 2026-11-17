package com.earlab.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.earlab.app.data.HeadphoneProfile
import com.earlab.app.data.Store
import com.earlab.app.util.Perms

/**
 * Следит за подключением Bluetooth-аудиоустройств (A2DP / HFP) и читает уровень заряда.
 *
 * Заряд берётся из системы: скрытый BluetoothDevice.getBatteryLevel() и широковещательное
 * событие BATTERY_LEVEL_CHANGED. Если наушники не сообщают заряд по стандартным каналам
 * (HFP-батарея / Battery Service), система вернёт -1 — тогда в интерфейсе будет «—».
 */
@SuppressLint("MissingPermission")
class BtMonitor(
    private val app: Context,
    private val store: Store,
    private val cb: Callbacks,
) {
    interface Callbacks {
        /** [fresh] = true, если подключение случилось «на наших глазах», а не найдено при синхронизации. */
        fun onConnected(p: HeadphoneProfile, fresh: Boolean)
        fun onDisconnected(p: HeadphoneProfile)
        fun onBattery(p: HeadphoneProfile, level: Int)
    }

    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** адрес -> какие профили сейчас подключены (a2dp / hfp). Устройство «в сети», пока есть хоть один. */
    private val connected = mutableMapOf<String, MutableSet<String>>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        if (registered) return
        store.closeOpenSessions()
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Это системные широковещательные сообщения — получатель должен быть «экспортируемым».
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        sync()
    }

    fun stop() {
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Находит уже подключённые устройства (например, после перезапуска приложения). */
    fun sync() {
        val ad = adapter ?: return
        if (!Perms.btConnect(app) || !ad.isEnabled) return
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val tag = if (profile == BluetoothProfile.A2DP) TAG_A2DP else TAG_HFP
            ad.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach { markConnected(it, tag, announce = false) }
                    } catch (e: SecurityException) {
                        // разрешение пропало — игнорируем
                    } finally {
                        ad.closeProfileProxy(p, proxy)
                    }
                }

                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    /** Периодический опрос заряда (событие BATTERY_LEVEL_CHANGED приходит не от всех наушников). */
    fun pollBattery() {
        val ad = adapter ?: return
        if (!Perms.btConnect(app)) return
        val now = System.currentTimeMillis()
        for (addr in connected.keys.toList()) {
            store.updateProfile(addr) { it.copy(lastSeen = now) }
            val level = try {
                readBattery(ad.getRemoteDevice(addr))
            } catch (e: Exception) {
                null
            }
            if (level != null) updateBattery(addr, level)
        }
    }

    private fun handle(i: Intent) {
        when (i.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> profileEvent(i, TAG_A2DP)
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> profileEvent(i, TAG_HFP)
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val dev = i.device() ?: return
                val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (level in 0..100 && connected.containsKey(dev.address)) updateBattery(dev.address, level)
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    connected.keys.toList().forEach { markDisconnected(it) }
                    connected.clear()
                }
            }
        }
    }

    private fun profileEvent(i: Intent, tag: String) {
        val dev = i.device() ?: return
        when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_CONNECTED -> markConnected(dev, tag, announce = true)
            BluetoothProfile.STATE_DISCONNECTED -> {
                val tags = connected[dev.address] ?: return
                tags.remove(tag)
                if (tags.isEmpty()) {
                    connected.remove(dev.address)
                    markDisconnected(dev.address)
                }
            }
        }
    }

    private fun markConnected(dev: BluetoothDevice, tag: String, announce: Boolean) {
        val addr = dev.address ?: return
        if (store.isIgnored(addr)) return
        val tags = connected.getOrPut(addr) { mutableSetOf() }
        val first = tags.isEmpty()
        tags.add(tag)

        store.upsertDevice(addr, safeName(dev))
        val battery = readBattery(dev)
        val now = System.currentTimeMillis()
        store.updateProfile(addr) { it.copy(connected = true, lastSeen = now, battery = battery ?: it.battery) }

        if (first) {
            store.startSession(addr)
            if (battery != null) store.addSample(addr, battery, force = true)
            store.profile(addr)?.let { cb.onConnected(it, announce) }
        } else if (battery != null) {
            updateBattery(addr, battery)
        }
    }

    private fun markDisconnected(addr: String) {
        val now = System.currentTimeMillis()
        store.updateProfile(addr) { it.copy(connected = false, lastSeen = now) }
        store.endSession(addr, now)
        store.profile(addr)?.let { cb.onDisconnected(it) }
    }

    private fun updateBattery(addr: String, level: Int) {
        val before = store.profile(addr) ?: return
        store.addSample(addr, level)
        if (before.battery != level) {
            store.updateProfile(addr) { it.copy(battery = level, lastSeen = System.currentTimeMillis()) }
            store.profile(addr)?.let { cb.onBattery(it, level) }
        }
    }

    private fun safeName(dev: BluetoothDevice): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dev.alias ?: dev.name else dev.name
    } catch (e: SecurityException) {
        null
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG_A2DP = "a2dp"
        private const val TAG_HFP = "hfp"

        // Скрытые в SDK константы (стабильные строки платформы).
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        private val getBatteryLevelMethod by lazy {
            runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel") }.getOrNull()
        }

        /** 0..100 или null, если система уровень заряда не знает. */
        fun readBattery(dev: BluetoothDevice): Int? = try {
            val v = getBatteryLevelMethod?.invoke(dev) as? Int
            if (v != null && v in 0..100) v else null
        } catch (t: Throwable) {
            null
        }
    }
}
