package com.earlab.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
object LocationHelper {

    /** Самая свежая из известных системе позиций. */
    fun lastKnown(ctx: Context): Location? {
        if (!Perms.location(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Сразу отдаёт последнюю известную позицию, затем (Android 11+) пытается получить свежую.
     * onFix может вызваться один или два раза.
     */
    fun capture(ctx: Context, onFix: (Location) -> Unit) {
        if (!Perms.location(ctx)) return
        lastKnown(ctx)?.let(onFix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
            try {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                    if (loc != null) onFix(loc)
                }
            } catch (e: SecurityException) {
                // разрешение отозвали на лету — просто остаёмся с lastKnown
            }
        }
    }
}
