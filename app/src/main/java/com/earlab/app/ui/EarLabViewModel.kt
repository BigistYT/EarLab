package com.earlab.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.earlab.app.EarLabApp
import com.earlab.app.audio.PingTone
import com.earlab.app.bt.Radar
import com.earlab.app.bt.RadarState
import com.earlab.app.data.Automation
import com.earlab.app.data.HeadphoneProfile
import com.earlab.app.service.KeepAliveService
import com.earlab.app.service.ServiceState
import com.earlab.app.util.LocationHelper
import com.earlab.app.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class EarLabViewModel(private val app: Application) : AndroidViewModel(app) {

    private val store = (app as EarLabApp).store
    private val radar = Radar(app)
    private val ping = PingTone(app)

    val profiles = store.profiles
    val presets = store.presets
    val eq = store.eq
    val history = store.history
    val sessions = store.sessions
    val serviceEnabled = store.serviceEnabled
    val effectsAvailable: StateFlow<Boolean?> = ServiceState.effectsAvailable.asStateFlow()
    val serviceRunning: StateFlow<Boolean> = ServiceState.serviceRunning.asStateFlow()
    val radarState: StateFlow<RadarState> = radar.state
    val pinging: StateFlow<Boolean> = ping.active

    private val selectedAddress = MutableStateFlow<String?>(null)

    val selected: StateFlow<HeadphoneProfile?> = combine(store.profiles, selectedAddress) { list, addr -> pick(list, addr) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pick(store.profiles.value, null))

    private companion object {
        fun pick(list: List<HeadphoneProfile>, addr: String?): HeadphoneProfile? =
            list.firstOrNull { it.address == addr } ?: list.firstOrNull { it.connected } ?: list.firstOrNull()
    }

    fun select(address: String) {
        if (selectedAddress.value != address) stopRadar()
        selectedAddress.value = address
    }

    // ───────── Эквалайзер ─────────

    fun setEqEnabled(on: Boolean) = store.updateEq { it.copy(enabled = on) }

    fun setBand(index: Int, value: Float) = store.updateEq { s ->
        if (index !in s.curve.indices) s
        else s.copy(curve = s.curve.toMutableList().also { it[index] = value }, presetName = null)
    }

    fun setBass(v: Float) = store.updateEq { it.copy(bass = v, presetName = null) }
    fun setVirtualizer(v: Float) = store.updateEq { it.copy(virtualizer = v, presetName = null) }
    fun setLoudness(v: Float) = store.updateEq { it.copy(loudness = v, presetName = null) }
    fun applyPreset(name: String) = store.applyPreset(name)
    fun savePreset(name: String) = store.savePreset(name)
    fun deletePreset(name: String) = store.deletePreset(name)

    // ───────── Автоматизации и устройства ─────────

    fun updateAutomation(address: String, f: (Automation) -> Automation) =
        store.updateProfile(address) { it.copy(automation = f(it.automation)) }

    fun forget(address: String) {
        stopRadar()
        store.forget(address)
    }

    fun toggleService(on: Boolean) {
        store.setServiceEnabled(on)
        if (on && Perms.btConnect(app)) KeepAliveService.start(app) else KeepAliveService.stop(app)
    }

    /** Сопряжённые аудиоустройства, которые можно добавить вручную. */
    @SuppressLint("MissingPermission")
    fun bondedAudio(): List<Pair<String, String>> {
        if (!Perms.btConnect(app)) return emptyList()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        return try {
            adapter.bondedDevices
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
                .map { it.address to (it.name ?: it.address) }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun addBonded(address: String, name: String) {
        store.unignore(address)
        store.upsertDevice(address, name)
        selectedAddress.value = address
        if (Perms.btConnect(app)) KeepAliveService.start(app) // служба заново синхронизирует подключённые устройства
    }

    // ───────── Поиск ─────────

    fun saveLocationNow(address: String) {
        LocationHelper.capture(app) { loc ->
            store.recordLocation(
                address, loc.latitude, loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy else null, loc.time,
            )
        }
    }

    fun startRadar(address: String) = radar.start(address)
    fun stopRadar() = radar.stop()
    fun startPing(address: String) = ping.start(address)
    fun stopPing() = ping.stop()

    override fun onCleared() {
        radar.stop()
        ping.stop()
        super.onCleared()
    }
}
