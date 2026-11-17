package com.earlab.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Всё состояние приложения: профили наушников, история заряда, сессии, пресеты, текущий эквалайзер.
 * Хранится в SharedPreferences (JSON), наружу отдаётся через StateFlow.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("earlab", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<HeadphoneProfile>> = _profiles.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<BatterySample>> = _history.asStateFlow()

    private val _sessions = MutableStateFlow(readSessions())
    val sessions: StateFlow<List<ListenSession>> = _sessions.asStateFlow()

    private val _presets = MutableStateFlow(EqDefaults.BUILT_IN + readCustomPresets())
    val presets: StateFlow<List<EqPreset>> = _presets.asStateFlow()

    private val _eq = MutableStateFlow(readEq())
    val eq: StateFlow<EqState> = _eq.asStateFlow()

    private val _serviceEnabled = MutableStateFlow(prefs.getBoolean(K_SERVICE, true))
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    @Volatile
    private var ignored: Set<String> = prefs.getStringSet(K_IGNORED, emptySet())?.toSet() ?: emptySet()

    // ───────────────────────── Профили ─────────────────────────

    fun profile(address: String): HeadphoneProfile? = _profiles.value.firstOrNull { it.address == address }

    fun isIgnored(address: String): Boolean = address in ignored

    fun upsertDevice(address: String, name: String?) {
        synchronized(lock) {
            val list = _profiles.value
            val existing = list.firstOrNull { it.address == address }
            val newName = name?.takeIf { it.isNotBlank() }
            val updated = when {
                existing == null -> list + HeadphoneProfile(address = address, name = newName ?: address)
                newName != null && newName != existing.name ->
                    list.map { if (it.address == address) it.copy(name = newName) else it }
                else -> list
            }
            if (updated !== list) {
                _profiles.value = updated
                saveProfiles()
            }
        }
    }

    fun updateProfile(address: String, block: (HeadphoneProfile) -> HeadphoneProfile) {
        synchronized(lock) {
            _profiles.value = _profiles.value.map { if (it.address == address) block(it) else it }
            saveProfiles()
        }
    }

    fun recordLocation(address: String, lat: Double, lon: Double, accuracyM: Float?, time: Long) {
        val p = profile(address) ?: return
        if (time < p.locTime) return
        updateProfile(address) { it.copy(lat = lat, lon = lon, accuracyM = accuracyM, locTime = time) }
    }

    /** Убрать устройство и больше не добавлять его автоматически. */
    fun forget(address: String) {
        synchronized(lock) {
            _profiles.value = _profiles.value.filter { it.address != address }
            _history.value = _history.value.filter { it.address != address }
            _sessions.value = _sessions.value.filter { it.address != address }
            ignored = ignored + address
            prefs.edit()
                .putStringSet(K_IGNORED, ignored)
                .putString(K_PROFILES, encodeProfiles(_profiles.value))
                .putString(K_HISTORY, encodeHistory(_history.value))
                .putString(K_SESSIONS, encodeSessions(_sessions.value))
                .apply()
        }
    }

    fun unignore(address: String) {
        synchronized(lock) {
            if (address in ignored) {
                ignored = ignored - address
                prefs.edit().putStringSet(K_IGNORED, ignored).apply()
            }
        }
    }

    // ───────────────────────── История заряда ─────────────────────────

    fun addSample(address: String, percent: Int, force: Boolean = false) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = _history.value.lastOrNull { it.address == address }
            if (!force && last != null && last.percent == percent && now - last.time < SAMPLE_MIN_GAP_MS) {
                return@synchronized
            }
            var list = _history.value + BatterySample(address, now, percent)
            if (list.size > MAX_SAMPLES) list = list.takeLast(MAX_SAMPLES)
            _history.value = list
            prefs.edit().putString(K_HISTORY, encodeHistory(list)).apply()
        }
    }

    // ───────────────────────── Сессии прослушивания ─────────────────────────

    fun startSession(address: String) {
        synchronized(lock) {
            if (_sessions.value.any { it.address == address && it.end == 0L }) return@synchronized
            var list = _sessions.value + ListenSession(address, System.currentTimeMillis(), 0L)
            if (list.size > MAX_SESSIONS) list = list.takeLast(MAX_SESSIONS)
            _sessions.value = list
            prefs.edit().putString(K_SESSIONS, encodeSessions(list)).apply()
        }
    }

    fun endSession(address: String, at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = _sessions.value.map {
                if (it.address == address && it.end == 0L) it.copy(end = maxOf(at, it.start + 1000L)) else it
            }
            _sessions.value = list
            prefs.edit().putString(K_SESSIONS, encodeSessions(list)).apply()
        }
    }

    /** При старте службы: незакрытые сессии (процесс был убит) закрываем по последнему «замеченному» времени. */
    fun closeOpenSessions() {
        synchronized(lock) {
            val seen = _profiles.value.associate { it.address to it.lastSeen }
            val list = _sessions.value.map {
                if (it.end == 0L) it.copy(end = maxOf(it.start + 1000L, seen[it.address] ?: it.start)) else it
            }
            _sessions.value = list
            prefs.edit().putString(K_SESSIONS, encodeSessions(list)).apply()
        }
    }

    // ───────────────────────── Эквалайзер и пресеты ─────────────────────────

    fun updateEq(block: (EqState) -> EqState) {
        synchronized(lock) {
            _eq.value = block(_eq.value)
            prefs.edit().putString(K_EQ, _eq.value.toJson().toString()).apply()
        }
    }

    fun applyPreset(name: String) {
        val p = _presets.value.firstOrNull { it.name == name } ?: return
        updateEq {
            it.copy(
                enabled = true,
                curve = p.curve,
                bass = p.bass,
                virtualizer = p.virtualizer,
                loudness = p.loudness,
                presetName = p.name,
            )
        }
    }

    fun savePreset(rawName: String) {
        synchronized(lock) {
            var name = rawName.trim()
            if (name.isEmpty()) return@synchronized
            if (EqDefaults.BUILT_IN.any { it.name.equals(name, ignoreCase = true) }) name += " *"
            val e = _eq.value
            val preset = EqPreset(name, e.curve, e.bass, e.virtualizer, e.loudness, builtIn = false)
            val custom = _presets.value.filter { !it.builtIn && it.name != name } + preset
            _presets.value = EqDefaults.BUILT_IN + custom
            prefs.edit().putString(K_PRESETS, JSONArray(custom.map { it.toJson() }).toString()).apply()
            _eq.value = e.copy(presetName = name)
            prefs.edit().putString(K_EQ, _eq.value.toJson().toString()).apply()
        }
    }

    fun deletePreset(name: String) {
        synchronized(lock) {
            val custom = _presets.value.filter { !it.builtIn && it.name != name }
            _presets.value = EqDefaults.BUILT_IN + custom
            prefs.edit().putString(K_PRESETS, JSONArray(custom.map { it.toJson() }).toString()).apply()
            if (_eq.value.presetName == name) {
                _eq.value = _eq.value.copy(presetName = null)
                prefs.edit().putString(K_EQ, _eq.value.toJson().toString()).apply()
            }
            // отвязываем удалённый пресет от наушников
            _profiles.value = _profiles.value.map {
                if (it.automation.presetName == name) it.copy(automation = it.automation.copy(presetName = null)) else it
            }
            saveProfiles()
        }
    }

    fun setServiceEnabled(on: Boolean) {
        _serviceEnabled.value = on
        prefs.edit().putBoolean(K_SERVICE, on).apply()
    }

    // ───────────────────────── Сериализация ─────────────────────────

    private fun saveProfiles() {
        prefs.edit().putString(K_PROFILES, encodeProfiles(_profiles.value)).apply()
    }

    private fun encodeProfiles(list: List<HeadphoneProfile>): String =
        JSONArray(list.map { it.toJson() }).toString()

    private fun readProfiles(): List<HeadphoneProfile> = try {
        val arr = JSONArray(prefs.getString(K_PROFILES, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toProfile() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun encodeHistory(list: List<BatterySample>): String =
        list.joinToString("\n") { "${it.address},${it.time},${it.percent}" }

    private fun readHistory(): List<BatterySample> =
        (prefs.getString(K_HISTORY, "") ?: "").lineSequence().mapNotNull { line ->
            val p = line.split(',')
            val t = p.getOrNull(1)?.toLongOrNull()
            val pc = p.getOrNull(2)?.toIntOrNull()
            if (p.size == 3 && t != null && pc != null) BatterySample(p[0], t, pc) else null
        }.toList()

    private fun encodeSessions(list: List<ListenSession>): String =
        list.joinToString("\n") { "${it.address},${it.start},${it.end}" }

    private fun readSessions(): List<ListenSession> =
        (prefs.getString(K_SESSIONS, "") ?: "").lineSequence().mapNotNull { line ->
            val p = line.split(',')
            val s = p.getOrNull(1)?.toLongOrNull()
            val e = p.getOrNull(2)?.toLongOrNull()
            if (p.size == 3 && s != null && e != null) ListenSession(p[0], s, e) else null
        }.toList()

    private fun readCustomPresets(): List<EqPreset> = try {
        val arr = JSONArray(prefs.getString(K_PRESETS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toPreset() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun readEq(): EqState = try {
        val raw = prefs.getString(K_EQ, null)
        if (raw == null) EqState() else JSONObject(raw).toEqState()
    } catch (e: Exception) {
        EqState()
    }

    private fun Automation.toJson() = JSONObject().apply {
        put("preset", presetName ?: JSONObject.NULL)
        put("volume", volumePercent)
        put("autoPlay", autoPlay)
        put("lowBattery", lowBatteryAlert)
        put("breakMin", breakReminderMin)
    }

    private fun JSONObject.toAutomation() = Automation(
        presetName = if (isNull("preset")) null else getString("preset"),
        volumePercent = optInt("volume", -1),
        autoPlay = optBoolean("autoPlay", false),
        lowBatteryAlert = optInt("lowBattery", 20),
        breakReminderMin = optInt("breakMin", 0),
    )

    private fun HeadphoneProfile.toJson() = JSONObject().apply {
        put("address", address)
        put("name", name)
        put("battery", battery)
        put("lastSeen", lastSeen)
        if (lat != null && lon != null) {
            put("lat", lat)
            put("lon", lon)
        }
        accuracyM?.let { put("acc", it.toDouble()) }
        put("locTime", locTime)
        put("auto", automation.toJson())
    }

    // connected намеренно не сохраняем: после перезапуска правду скажет BtMonitor.
    private fun JSONObject.toProfile() = HeadphoneProfile(
        address = getString("address"),
        name = optString("name", "Наушники"),
        battery = optInt("battery", -1),
        connected = false,
        lastSeen = optLong("lastSeen", 0L),
        lat = if (has("lat")) getDouble("lat") else null,
        lon = if (has("lon")) getDouble("lon") else null,
        accuracyM = if (has("acc")) getDouble("acc").toFloat() else null,
        locTime = optLong("locTime", 0L),
        automation = optJSONObject("auto")?.toAutomation() ?: Automation(),
    )

    private fun EqPreset.toJson() = JSONObject().apply {
        put("name", name)
        put("curve", JSONArray(curve.map { it.toDouble() }))
        put("bass", bass.toDouble())
        put("virt", virtualizer.toDouble())
        put("loud", loudness.toDouble())
    }

    private fun JSONObject.toPreset() = EqPreset(
        name = getString("name"),
        curve = readCurve(getJSONArray("curve")),
        bass = optDouble("bass", 0.0).toFloat(),
        virtualizer = optDouble("virt", 0.0).toFloat(),
        loudness = optDouble("loud", 0.0).toFloat(),
        builtIn = false,
    )

    private fun EqState.toJson() = JSONObject().apply {
        put("enabled", enabled)
        put("curve", JSONArray(curve.map { it.toDouble() }))
        put("bass", bass.toDouble())
        put("virt", virtualizer.toDouble())
        put("loud", loudness.toDouble())
        put("preset", presetName ?: JSONObject.NULL)
    }

    private fun JSONObject.toEqState() = EqState(
        enabled = optBoolean("enabled", true),
        curve = readCurve(getJSONArray("curve")),
        bass = optDouble("bass", 0.0).toFloat(),
        virtualizer = optDouble("virt", 0.0).toFloat(),
        loudness = optDouble("loud", 0.0).toFloat(),
        presetName = if (isNull("preset")) null else getString("preset"),
    )

    private fun readCurve(arr: JSONArray): List<Float> {
        val list = (0 until arr.length()).map { arr.getDouble(it).toFloat().coerceIn(-1f, 1f) }
        return if (list.size == EqDefaults.BANDS) list else EqDefaults.FLAT
    }

    private companion object {
        const val K_PROFILES = "profiles"
        const val K_HISTORY = "history"
        const val K_SESSIONS = "sessions"
        const val K_PRESETS = "presets"
        const val K_EQ = "eq"
        const val K_SERVICE = "service"
        const val K_IGNORED = "ignored"
        const val MAX_SAMPLES = 4000
        const val MAX_SESSIONS = 1000
        const val SAMPLE_MIN_GAP_MS = 10 * 60_000L
    }
}
