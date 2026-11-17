package com.earlab.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.earlab.app.EarLabApp
import com.earlab.app.service.KeepAliveService
import com.earlab.app.util.Perms

class MainActivity : ComponentActivity() {

    /** Растёт при каждом возврате в приложение и после ответа на запрос разрешений — перечитываем состояние. */
    private var permVersion by mutableIntStateOf(0)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permVersion++
            ensureService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarLabTheme {
                val vm: EarLabViewModel = viewModel()
                val perms = remember(permVersion) {
                    PermState(
                        bt = Perms.btConnect(this),
                        notif = Perms.notifications(this),
                        loc = Perms.location(this),
                    )
                }
                EarLabRoot(
                    vm = vm,
                    perms = perms,
                    requestBasic = { launch(Perms.basicRequest()) },
                    requestLocation = { launch(Perms.locationRequest()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permVersion++
        ensureService()
    }

    private fun launch(permissions: Array<String>) {
        if (permissions.isEmpty()) {
            permVersion++
            ensureService()
        } else {
            permLauncher.launch(permissions)
        }
    }

    private fun ensureService() {
        val store = (application as EarLabApp).store
        if (store.serviceEnabled.value && Perms.btConnect(this)) KeepAliveService.start(this)
    }
}
