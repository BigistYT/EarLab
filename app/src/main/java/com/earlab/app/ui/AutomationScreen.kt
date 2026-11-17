package com.earlab.app.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AutomationScreen(vm: EarLabViewModel, perms: PermState, onRequestBasic: () -> Unit) {
    val selected by vm.selected.collectAsState()
    val presets by vm.presets.collectAsState()
    val serviceEnabled by vm.serviceEnabled.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Авто", "Что происходит при подключении")
        DeviceSwitcher(vm)

        val p = selected
        if (p == null) {
            Panel { Dim("Сначала подключите наушники — потом здесь появятся автоматизации для них.") }
        } else {
            val a = p.automation
            fun update(f: (com.earlab.app.data.Automation) -> com.earlab.app.data.Automation) =
                vm.updateAutomation(p.address, f)

            Panel {
                SectionTitle("При подключении «${p.name}»")
                Spacer(Modifier.height(12.dp))

                Text("Пресет звука")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = a.presetName == null,
                        onClick = { update { it.copy(presetName = null) } },
                        label = { Text("Не менять") },
                    )
                    presets.forEach { preset ->
                        FilterChip(
                            selected = a.presetName == preset.name,
                            onClick = { update { it.copy(presetName = preset.name) } },
                            label = { Text(preset.name) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Surface2)
                Spacer(Modifier.height(12.dp))

                ToggleRow(
                    "Выставить громкость", "Громкость музыки при каждом подключении",
                    a.volumePercent >= 0,
                    { on -> update { it.copy(volumePercent = if (on) 40 else -1) } },
                )
                if (a.volumePercent >= 0) {
                    Slider(
                        value = a.volumePercent.toFloat(),
                        onValueChange = { v -> update { it.copy(volumePercent = v.roundToInt()) } },
                        valueRange = 0f..100f,
                    )
                    Dim(
                        if (a.volumePercent > 70) "${a.volumePercent}% — высоковато для долгого прослушивания"
                        else "${a.volumePercent}%"
                    )
                }

                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Запускать музыку", "Отправляет команду «воспроизведение» плееру. Если плеер не отреагирует — запустите вручную.",
                    a.autoPlay,
                    { on -> update { it.copy(autoPlay = on) } },
                )
            }

            Panel {
                SectionTitle("Забота о наушниках и ушах")
                Spacer(Modifier.height(12.dp))
                Text(
                    if (a.lowBatteryAlert > 0) "Предупредить при заряде ≤ ${a.lowBatteryAlert}%" else "Предупреждение о низком заряде выключено"
                )
                Slider(
                    value = a.lowBatteryAlert.toFloat(),
                    onValueChange = { v -> update { it.copy(lowBatteryAlert = (v / 5).roundToInt() * 5) } },
                    valueRange = 0f..50f,
                    steps = 9,
                )

                Spacer(Modifier.height(8.dp))
                Text("Напоминать об отдыхе для ушей")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Выкл", 30 to "30 мин", 45 to "45 мин", 60 to "1 ч", 90 to "1.5 ч").forEach { (min, label) ->
                        FilterChip(
                            selected = a.breakReminderMin == min,
                            onClick = { update { it.copy(breakReminderMin = min) } },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Dim("Напоминание применится при следующем подключении.")
            }
        }

        Panel {
            SectionTitle("Фоновая служба")
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Следить за наушниками", "Нужна для автоматизаций, эквалайзера, уведомлений и истории заряда",
                serviceEnabled, vm::toggleService,
            )
            if (!perms.notif) {
                Spacer(Modifier.height(8.dp))
                Dim("Уведомления выключены — предупреждения о заряде не будут показаны.")
                OutlinedButton(onClick = onRequestBasic) { Text("Разрешить уведомления") }
            }
        }

        if (selected != null) {
            OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Забыть это устройство")
            }
        }
    }

    val target = selected
    if (confirmForget && target != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Забыть «${target.name}»?") },
            text = { Text("История заряда и статистика этого устройства будут удалены. Оно не появится снова, пока вы не добавите его вручную.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.forget(target.address)
                    confirmForget = false
                }) { Text("Забыть") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Отмена") } },
        )
    }
}
