package com.earlab.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Perms {
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** До Android 12 Bluetooth — обычное разрешение, запрашивать нечего. */
    fun btConnect(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun notifications(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun location(ctx: Context) =
        has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Что нужно спросить при первом запуске (может быть пусто на старых Android). */
    fun basicRequest(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun locationRequest(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}
