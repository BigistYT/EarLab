package com.earlab.app

import android.app.Application
import com.earlab.app.data.Store
import com.earlab.app.service.Notifier

class EarLabApp : Application() {

    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Notifier.ensureChannels(this)
    }
}
