package com.earlab.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.earlab.app.R
import com.earlab.app.data.HeadphoneProfile
import com.earlab.app.ui.MainActivity
import com.earlab.app.util.Perms

object Notifier {
    private const val CH_SERVICE = "earlab_service"
    private const val CH_ALERTS = "earlab_alerts"
    const val ID_SERVICE = 1
    private const val ID_LOW_BASE = 100
    private const val ID_BREAK_BASE = 200

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Работа в фоне", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока EarLab следит за наушниками"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Низкий заряд и напоминания дать ушам отдохнуть"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_headphones)
            .setContentTitle("EarLab")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateService(ctx: Context, text: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_SERVICE, serviceNotification(ctx, text))
    }

    fun lowBattery(ctx: Context, p: HeadphoneProfile, level: Int) {
        if (!Perms.notifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_headphones)
            .setContentTitle("${p.name}: заряд $level%")
            .setContentText("Пора поставить наушники на зарядку")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_LOW_BASE + (p.address.hashCode() and 0xFF), n)
    }

    fun breakReminder(ctx: Context, name: String, minutes: Int, address: String) {
        if (!Perms.notifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_headphones)
            .setContentTitle("Дайте ушам отдохнуть")
            .setContentText("$name играют уже около $minutes мин. Сделайте перерыв на несколько минут.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_BREAK_BASE + (address.hashCode() and 0xFF), n)
    }
}
