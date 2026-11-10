package com.suso.miiptv.data

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Avisos de la guía con la app cerrada (móvil y tablet): un minuto antes de que empiece un programa con recordatorio,
 * Android muestra un aviso; al tocarlo se abre la app, que ofrece ver el canal.
 * En la tele no se usa: Android TV no enseña avisos de otras apps, allí sigue el aviso dentro de la app.
 */
object ReminderAlarms {
    private const val ACTION = "com.suso.miiptv.AVISO"
    private const val CHANNEL = "avisos"
    private const val LEAD = 60_000L

    /** Hay alguna pantalla de la app a la vista (entonces avisa la propia app y no hace falta el aviso de Android). */
    @Volatile
    var foreground = false

    private fun intent(ctx: Context, id: String, title: String = "", channel: String = "", start: Long = 0): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION)
            .setData(android.net.Uri.parse("pepino://aviso/" + android.net.Uri.encode(id)))
            .putExtra("id", id).putExtra("t", title).putExtra("c", channel).putExtra("s", start)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, i, flags)
    }

    /** Deja programadas las alarmas de los recordatorios que hay ahora y quita las de los que ya no están. */
    fun sync(ctx: Context) {
        if (!Device.touch || !Repo.isReady()) return
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val sp = ctx.getSharedPreferences("avisos", Context.MODE_PRIVATE)
            val before = sp.getStringSet("ids", emptySet()) ?: emptySet()
            val now = System.currentTimeMillis()
            val list = Repo.prefs.reminders().filter { it.start > now }
            val ids = list.map { it.id }.toSet()
            (before - ids).forEach { am.cancel(intent(ctx, it)) }
            for (r in list) {
                val pi = intent(ctx, r.id, r.title, r.channel.name, r.start)
                val at = (r.start - LEAD).coerceAtLeast(now + 1000)
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                when {
                    exact && Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    exact -> am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                    Build.VERSION.SDK_INT >= 23 -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    else -> am.set(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }
            sp.edit().putStringSet("ids", ids).apply()
        }.onFailure { ErrorLog.once("avisos", "No se pudieron programar los avisos: ${it.message}") }
    }

    /** Android 13 o superior: la primera vez que pones un recordatorio, pide permiso para mostrar avisos. */
    fun askPermission(ctx: Context) {
        if (!Device.touch || Build.VERSION.SDK_INT < 33) return
        val act = ctx as? Activity ?: return
        if (act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71) }
    }

    internal fun show(ctx: Context, id: String, title: String, channel: String, start: Long) {
        if (foreground) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Avisos de programas", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Te avisa cuando empieza un programa con recordatorio" },
            )
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, id.hashCode(), open, flags)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(start))
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
        b.setSmallIcon(com.suso.miiptv.R.drawable.ic_stat_aviso)
            .setContentTitle("Empieza «$title»")
            .setContentText("$hm · $channel · Toca para verlo")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setWhen(start)
            .setShowWhen(true)
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF268332.toInt()).setCategory(android.app.Notification.CATEGORY_REMINDER)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 26) b.setPriority(android.app.Notification.PRIORITY_HIGH).setDefaults(android.app.Notification.DEFAULT_ALL)
        runCatching { nm.notify(id.hashCode(), b.build()) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Al reiniciar el aparato Android borra las alarmas: se vuelven a poner.
            ReminderAlarms.sync(ctx)
            return
        }
        val id = intent.getStringExtra("id") ?: return
        // Solo si el recordatorio sigue puesto (puede haberse quitado después de programar la alarma).
        if (Repo.isReady() && !Repo.prefs.hasReminder(id)) return
        ReminderAlarms.show(ctx, id, intent.getStringExtra("t").orEmpty(), intent.getStringExtra("c").orEmpty(), intent.getLongExtra("s", 0))
    }
}
