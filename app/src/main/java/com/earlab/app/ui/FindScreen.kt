package com.earlab.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.earlab.app.bt.RadarState
import com.earlab.app.data.HeadphoneProfile
import java.util.Locale

@Composable
fun FindScreen(vm: EarLabViewModel, perms: PermState, onRequestLocation: () -> Unit) {
    val selected by vm.selected.collectAsState()
    val radar by vm.radarState.collectAsState()
    val pinging by vm.pinging.collectAsState()
    var confirmPing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Поиск", "Потеряли наушники? Найдём")
        DeviceSwitcher(vm)

        val p = selected
        if (p == null) {
            Panel { Dim("Здесь появятся наушники, когда вы подключите их хотя бы раз.") }
        } else {
            LastSeenPanel(p, perms, onRequestLocation) { vm.saveLocationNow(p.address) }
            RadarPanel(p, radar, onStart = { vm.startRadar(p.address) }, onStop = vm::stopRadar)
            PingPanel(p, pinging, onStart = { confirmPing = true }, onStop = vm::stopPing)
        }
    }

    val target = selected
    if (confirmPing && target != null) {
        AlertDialog(
            onDismissRequest = { confirmPing = false },
            title = { Text("Снимите наушники!") },
            text = {
                Text(
                    "Наушники будут пищать, громкость нарастает постепенно, но сигнал может быть громким. " +
                        "Не надевайте их, пока играет сигнал. Остановить можно в любой момент."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPing = false
                    vm.startPing(target.address)
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { confirmPing = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun LastSeenPanel(
    p: HeadphoneProfile,
    perms: PermState,
    onRequestLocation: () -> Unit,
    onSaveNow: () -> Unit,
) {
    val ctx = LocalContext.current
    val lat = p.lat
    val lon = p.lon
    Panel {
        SectionTitle("Где были в последний раз")
        Spacer(Modifier.height(6.dp))
        when {
            !perms.loc -> {
                Dim("Чтобы запоминать место, где наушники отключились, нужен доступ к геолокации. Данные остаются только на этом телефоне.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onRequestLocation) { Text("Разрешить геолокацию") }
            }

            lat == null || lon == null -> {
                Dim("Место пока не записано. Оно сохранится автоматически, когда наушники отключатся — или запомните текущее положение вручную.")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onSaveNow) { Text("Запомнить место сейчас") }
            }

            else -> {
                Text(
                    String.format(Locale.US, "%.5f, %.5f", lat, lon),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                val acc = p.accuracyM?.let { " · точность ≈ ${it.toInt()} м" } ?: ""
                Dim("Записано ${timeAgo(p.locTime)}$acc")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openMap(ctx, lat, lon, p.name) }) { Text("Открыть на карте") }
                    OutlinedButton(onClick = onSaveNow) { Text("Обновить") }
                }
            }
        }
    }
}

private fun openMap(ctx: android.content.Context, lat: Double, lon: Double, name: String) {
    val geo = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: ActivityNotFoundException) {
            // нет ни карт, ни браузера — ничего не делаем
        }
    }
}

@Composable
private fun RadarPanel(p: HeadphoneProfile, state: RadarState, onStart: () -> Unit, onStop: () -> Unit) {
    Panel {
        SectionTitle("Радар «тепло — холодно»")
        Spacer(Modifier.height(4.dp))
        Dim("Ходите по комнате с телефоном: чем ближе наушники, тем больше и ярче круг.")
        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val tracking = state as? RadarState.Tracking
            RadarView(proximity = tracking?.proximity ?: 0f, active = tracking != null)
            Spacer(Modifier.height(10.dp))

            val title = when {
                tracking != null -> when {
                    tracking.proximity > 0.8f -> "Горячо! Они совсем рядом"
                    tracking.proximity > 0.55f -> "Тепло"
                    tracking.proximity > 0.3f -> "Прохладно"
                    else -> "Холодно — далеко"
                }

                state is RadarState.Connecting -> "Подключаюсь к наушникам…"
                state is RadarState.Failed -> "Не получилось"
                else -> "Радар выключен"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            val sub = when {
                tracking != null -> {
                    val trend = when (tracking.trend) {
                        1 -> "теплее ↑"
                        -1 -> "холоднее ↓"
                        else -> "без изменений"
                    }
                    "$trend · сигнал ${tracking.rssi} дБм"
                }

                state is RadarState.Failed -> state.reason
                else -> null
            }
            if (sub != null) Dim(sub, Modifier.fillMaxWidth().padding(top = 2.dp))

            Spacer(Modifier.height(12.dp))
            if (state is RadarState.Idle || state is RadarState.Failed) {
                Button(onClick = onStart) { Text("Запустить радар") }
            } else {
                OutlinedButton(onClick = onStop) { Text("Остановить") }
            }
            if (!p.connected && state is RadarState.Idle) {
                Spacer(Modifier.height(6.dp))
                Dim("Наушники должны быть включены и находиться поблизости.", Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PingPanel(p: HeadphoneProfile, pinging: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Panel {
        SectionTitle("Позвонить в наушники")
        Spacer(Modifier.height(4.dp))
        Dim("Наушники начнут пищать — по звуку проще найти их в сумке или под диваном. Работает, пока они подключены к телефону.")
        Spacer(Modifier.height(12.dp))
        if (pinging) {
            Button(onClick = onStop) { Text("Остановить сигнал") }
        } else {
            OutlinedButton(onClick = onStart, enabled = p.connected) {
                Text(if (p.connected) "Проиграть сигнал" else "Сначала подключите наушники")
            }
        }
    }
}
