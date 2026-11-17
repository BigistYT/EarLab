package com.earlab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.earlab.app.data.Analytics
import com.earlab.app.data.BatterySample
import com.earlab.app.data.HeadphoneProfile
import com.earlab.app.data.ListenSession
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: EarLabViewModel, perms: PermState, onRequestBt: () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val selected by vm.selected.collectAsState()
    val history by vm.history.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val running by vm.serviceRunning.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("EarLab", "Центр управления наушниками") {
            StatusPill(if (running) "Служба активна" else "Служба выключена", if (running) Teal else Red)
        }

        when {
            !perms.bt -> Panel {
                SectionTitle("Нужен доступ к Bluetooth")
                Spacer(Modifier.height(6.dp))
                Dim("Чтобы видеть подключённые наушники и их заряд, разрешите доступ к устройствам поблизости.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestBt) { Text("Разрешить") }
            }

            profiles.isEmpty() -> EmptyState(vm)

            else -> {
                DeviceSwitcher(vm)
                selected?.let { DeviceDetails(it, history, sessions, now) }
            }
        }
    }
}

@Composable
private fun EmptyState(vm: EarLabViewModel) {
    val bonded = remember { vm.bondedAudio() }
    Panel {
        Text("🎧", style = MaterialTheme.typography.displayMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        SectionTitle("Наушников пока нет")
        Spacer(Modifier.height(4.dp))
        Dim("Подключите наушники по Bluetooth — они появятся здесь автоматически. Или добавьте уже сопряжённые:")
        if (bonded.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bonded.forEach { (addr, name) ->
                    OutlinedButton(onClick = { vm.addBonded(addr, name) }, modifier = Modifier.fillMaxWidth()) {
                        Text("＋  $name")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDetails(
    p: HeadphoneProfile,
    history: List<BatterySample>,
    sessions: List<ListenSession>,
    now: Long,
) {
    val samples = remember(history, p.address) { history.filter { it.address == p.address } }
    val perHour = remember(samples) { Analytics.drainPerHour(samples) }
    val hoursLeft = Analytics.hoursLeft(p.battery, perHour)

    Panel {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            if (p.connected) {
                Text("● Подключены", color = Teal, style = MaterialTheme.typography.labelLarge)
            } else {
                Dim("○ Не подключены · в сети " + timeAgo(p.lastSeen, now))
            }
            Spacer(Modifier.height(14.dp))
            BatteryRing(p.battery)
            Spacer(Modifier.height(14.dp))
            val note = when {
                p.battery < 0 && p.connected -> "Эти наушники не сообщают заряд системе Android"
                p.battery < 0 -> "Заряд появится после подключения"
                hoursLeft != null -> "≈ ${hoursText(hoursLeft)} до разрядки · %.1f%%/ч".format(Locale.getDefault(), perHour)
                else -> "Копим статистику для прогноза времени работы…"
            }
            Text(note, style = MaterialTheme.typography.bodyMedium, color = TextDim, textAlign = TextAlign.Center)
        }
    }

    Panel {
        SectionTitle("Заряд за 48 часов")
        Spacer(Modifier.height(10.dp))
        if (samples.count { it.time >= now - 48 * 3_600_000L } < 2) {
            Dim("Пока мало данных. График появится после нескольких часов использования.")
        } else {
            HistoryChart(samples, Modifier.fillMaxWidth().height(140.dp))
        }
    }

    val perDay = remember(sessions, p.address, now / 60_000) {
        Analytics.perDay(sessions.filter { it.address == p.address }, now)
    }
    val labels = remember(now / 3_600_000) {
        val fmt = SimpleDateFormat("EE", Locale.getDefault())
        Analytics.dayStarts(now, 7).map { fmt.format(Date(it)).take(2).replaceFirstChar { c -> c.uppercase() } }
    }
    val sessionCount = sessions.count { it.address == p.address && (if (it.end == 0L) now else it.end) >= now - 7 * 86_400_000L }

    Panel {
        SectionTitle("Время в наушниках")
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(durationText(perDay.last()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Teal)
                Dim("сегодня")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(durationText(perDay.sum()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Violet)
                Dim("за 7 дней · $sessionCount сессий")
            }
        }
        Spacer(Modifier.height(14.dp))
        WeekBars(perDay, labels)
    }
}
