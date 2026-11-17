package com.earlab.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

data class PermState(val bt: Boolean, val notif: Boolean, val loc: Boolean)

private data class Tab(val emoji: String, val title: String)

private val tabs = listOf(
    Tab("🎧", "Главная"),
    Tab("🎚", "Звук"),
    Tab("⚡", "Авто"),
    Tab("📍", "Поиск"),
)

@Composable
fun EarLabRoot(
    vm: EarLabViewModel,
    perms: PermState,
    requestBasic: () -> Unit,
    requestLocation: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var asked by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    // При первом запуске сразу просим Bluetooth и уведомления.
    LaunchedEffect(Unit) {
        if (!asked && (!perms.bt || !perms.notif)) {
            asked = true
            requestBasic()
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.emoji, fontSize = 20.sp) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(vm, perms, onRequestBt = requestBasic)
                1 -> EqualizerScreen(vm)
                2 -> AutomationScreen(vm, perms, onRequestBasic = requestBasic)
                else -> FindScreen(vm, perms, onRequestLocation = requestLocation)
            }
        }
    }
}
