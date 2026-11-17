package com.earlab.app.ui

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlab.app.data.BatterySample
import com.earlab.app.data.EqDefaults
import kotlin.math.abs

/** Карточка-«панель» — основной контейнер экранов. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Surface1)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = TextDim)
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Dim(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Dim(subtitle)
        }
        trailing()
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Переключатель наушников, если их несколько. */
@Composable
fun DeviceSwitcher(vm: EarLabViewModel) {
    val profiles by vm.profiles.collectAsState()
    val selected by vm.selected.collectAsState()
    if (profiles.size < 2) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { p ->
            FilterChip(
                selected = p.address == selected?.address,
                onClick = { vm.select(p.address) },
                label = { Text((if (p.connected) "● " else "○ ") + p.name) },
            )
        }
    }
}

fun batteryColor(percent: Int): Color = when {
    percent < 0 -> TextDim
    percent <= 15 -> Red
    percent <= 35 -> Amber
    else -> Teal
}

@Composable
fun BatteryRing(percent: Int, modifier: Modifier = Modifier, diameter: Dp = 200.dp) {
    val target = if (percent in 0..100) percent / 100f else 0f
    val progress by animateFloatAsState(target, tween(900), label = "battery")
    val color = batteryColor(percent)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.08f
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Surface2, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (percent >= 0) {
                drawArc(color, 135f, 270f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (percent >= 0) "$percent%" else "—",
                fontSize = (diameter.value * 0.22f).sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text("заряд", style = MaterialTheme.typography.labelMedium, color = TextDim)
        }
    }
}

@Composable
fun HistoryChart(samples: List<BatterySample>, modifier: Modifier = Modifier, hours: Int = 48) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val now = System.currentTimeMillis()
        val from = now - hours * 3_600_000L
        fun x(t: Long) = (t - from).toFloat() / (now - from) * w
        fun y(p: Int) = h - p / 100f * h

        for (p in intArrayOf(0, 50, 100)) {
            drawLine(Color(0xFF2B3563), Offset(0f, y(p)), Offset(w, y(p)), strokeWidth = 1.dp.toPx())
        }

        // разбиваем на отрезки: большие паузы (наушники были выключены) не соединяем линией
        val segments = ArrayList<MutableList<BatterySample>>()
        for (s in samples.filter { it.time >= from }.sortedBy { it.time }) {
            val cur = segments.lastOrNull()
            if (cur == null || s.time - cur.last().time > 45 * 60_000L) segments.add(mutableListOf(s)) else cur.add(s)
        }
        for (seg in segments) {
            if (seg.size == 1) {
                drawCircle(Teal, 3.dp.toPx(), Offset(x(seg[0].time), y(seg[0].percent)))
                continue
            }
            val line = Path()
            val fill = Path()
            seg.forEachIndexed { i, s ->
                val px = x(s.time)
                val py = y(s.percent)
                if (i == 0) {
                    line.moveTo(px, py)
                    fill.moveTo(px, h)
                    fill.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    fill.lineTo(px, py)
                }
            }
            fill.lineTo(x(seg.last().time), h)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Teal.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(line, Teal, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun WeekBars(values: List<Long>, labels: List<String>) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((80.dp * (v.toFloat() / max)).coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i == values.lastIndex) Teal else Violet.copy(alpha = 0.6f))
                )
                Spacer(Modifier.height(6.dp))
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
        }
    }
}

/**
 * Редактор кривой эквалайзера: тяните точки вверх-вниз.
 * Значения нормализованы (-1..1), реальные дБ зависят от диапазона полос устройства.
 */
@Composable
fun EqEditor(
    curve: List<Float>,
    enabled: Boolean,
    onChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    var active by remember { mutableIntStateOf(-1) }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFF8C95B8.toInt()
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(Unit) {
                val padX = 26.dp.toPx()
                val top = 14.dp.toPx()
                val bottom = 34.dp.toPx()
                fun nodeX(i: Int) = padX + (size.width - 2 * padX) * i / (EqDefaults.BANDS - 1)
                fun valueAt(y: Float): Float {
                    val plotH = size.height - top - bottom
                    return (1f - 2f * ((y - top) / plotH)).coerceIn(-1f, 1f)
                }
                detectDragGestures(
                    onDragStart = { o ->
                        var best = 0
                        var bestD = Float.MAX_VALUE
                        for (i in 0 until EqDefaults.BANDS) {
                            val d = abs(o.x - nodeX(i))
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        active = best
                        change(best, valueAt(o.y))
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { pointer, _ ->
                        pointer.consume()
                        if (active >= 0) change(active, valueAt(pointer.position.y))
                    },
                )
            }
    ) {
        val padX = 26.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = 34.dp.toPx()
        val plotH = size.height - top - bottom
        fun nx(i: Int) = padX + (size.width - 2 * padX) * i / (EqDefaults.BANDS - 1)
        fun ny(v: Float) = top + (1f - v) / 2f * plotH

        for (k in 0..4) {
            val v = 1f - k * 0.5f
            val c = if (k == 2) Color(0xFF4B5788) else Color(0xFF2B3563)
            drawLine(c, Offset(0f, ny(v)), Offset(size.width, ny(v)), strokeWidth = 1.dp.toPx())
        }

        val a = if (enabled) 1f else 0.4f
        val pts = curve.take(EqDefaults.BANDS).mapIndexed { i, v -> Offset(nx(i), ny(v)) }
        if (pts.size >= 2) {
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val mx = (p0.x + p1.x) / 2f
                path.cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
            }
            drawPath(
                path,
                Brush.horizontalGradient(listOf(Teal, Violet)),
                alpha = a,
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        labelPaint.textSize = 11.sp.toPx()
        pts.forEachIndexed { i, p ->
            val r = if (i == active) 13.dp.toPx() else 9.dp.toPx()
            drawCircle(Bg, r + 3.dp.toPx(), p)
            drawCircle(if (i == active) Violet else Teal, r, p, alpha = a)
            drawContext.canvas.nativeCanvas.drawText(
                EqDefaults.FREQ_LABELS[i], nx(i), size.height - 8.dp.toPx(), labelPaint,
            )
        }
    }
}

/** Пульсирующий «радар» для поиска наушников. */
@Composable
fun RadarView(proximity: Float, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "pulse",
    )
    val color = lerp(Violet, Teal, proximity)
    Canvas(modifier.size(220.dp)) {
        val c = center
        val maxR = size.minDimension / 2f
        for (k in 1..3) {
            drawCircle(Color(0xFF2B3563), maxR * k / 3f, c, style = Stroke(1.dp.toPx()))
        }
        if (active) {
            drawCircle(color.copy(alpha = (1f - pulse) * 0.5f), maxR * (0.2f + 0.8f * pulse), c)
            drawCircle(color, maxR * (0.12f + 0.55f * proximity), c, alpha = 0.9f)
        } else {
            drawCircle(Surface2, maxR * 0.12f, c)
        }
    }
}

/** Небольшой кликабельный текст-кнопка в строке. */
@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        color = Teal,
        style = MaterialTheme.typography.labelLarge,
    )
}
