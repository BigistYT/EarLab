package com.earlab.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.earlab.app.EarLabApp
import com.earlab.app.util.Perms

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = (context.applicationContext as EarLabApp).store
        if (store.serviceEnabled.value && Perms.btConnect(context)) {
            KeepAliveService.start(context, fromBoot = true)
        }
    }
}
