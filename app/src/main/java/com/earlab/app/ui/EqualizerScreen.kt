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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.earlab.app.data.EqDefaults
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(vm: EarLabViewModel) {
    val eq by vm.eq.collectAsState()
    val presets by vm.presets.collectAsState()
    val available by vm.effectsAvailable.collectAsState()
    val running by vm.serviceRunning.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    val currentCustom = presets.firstOrNull { it.name == eq.presetName && !it.builtIn }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Звук", "Эквалайзер и эффекты")

        if (!running) {
            Panel {
                SectionTitle("Служба выключена")
                Spacer(Modifier.height(4.dp))
                Dim("Эффекты применяет фоновая служба. Включите её на вкладке «Авто».")
            }
        } else if (available == false) {
            Panel {
                SectionTitle("Глобальный эквалайзер недоступен")
                Spacer(Modifier.height(4.dp))
                Dim(
                    "Эта версия Android или прошивка не даёт менять звук всей системы. " +
                        "Эффекты всё равно подхватят плееры, которые открывают сессию эффектов (например, VLC)."
                )
            }
        }

        Panel {
            ToggleRow("Эквалайзер", "Включить обработку звука", eq.enabled, vm::setEqEnabled)
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(
                    selected = eq.presetName == preset.name,
                    onClick = { vm.applyPreset(preset.name) },
                    label = { Text(preset.name) },
                )
            }
        }

        Panel {
            SectionTitle("Кривая")
            Dim("Тяните точки вверх и вниз. Частоты — в герцах.")
            Spacer(Modifier.height(8.dp))
            EqEditor(eq.curve, eq.enabled, onChange = vm::setBand)
        }

        Panel {
            LabeledSlider("Бас", eq.bass, vm::setBass, "Усиление низких частот")
            Spacer(Modifier.height(10.dp))
            LabeledSlider("Объём", eq.virtualizer, vm::setVirtualizer, "Расширяет стереобазу — лучше работает в наушниках")
            Spacer(Modifier.height(10.dp))
            LabeledSlider("Громкость+", eq.loudness, vm::setLoudness, "До +8 дБ. Осторожно: громко — вредно для слуха")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showSave = true }) { Text("Сохранить как пресет") }
            if (currentCustom != null) {
                OutlinedButton(onClick = { vm.deletePreset(currentCustom.name) }) { Text("Удалить") }
            }
            OutlinedButton(onClick = { vm.applyPreset(EqDefaults.BUILT_IN.first().name) }) { Text("Сброс") }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Название пресета") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        vm.savePreset(name)
                        showSave = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun LabeledSlider(title: String, value: Float, onChange: (Float) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = Teal)
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    Dim(hint)
}
