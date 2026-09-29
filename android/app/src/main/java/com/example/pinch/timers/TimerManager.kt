package com.example.pinch.timers

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.pinch.MainActivity
import com.example.pinch.R
import com.example.pinch.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

data class KitchenTimer(
    val id: String,
    val label: String,
    val recipeTitle: String,
    val recipeId: String?,
    val durationSeconds: Int,
    val endAtMillis: Long,
    val isFinished: Boolean = false,
) {
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Int =
        max(0, ((endAtMillis - now + 999) / 1000).toInt())
}

/**
 * Running kitchen timers. While Pinch is open a finished timer chimes in the app; if the
 * app is in the background an alarm posts a notification instead.
 */
class TimerManager(private val context: Context, private val scope: CoroutineScope) {
    private val _timers = MutableStateFlow<List<KitchenTimer>>(emptyList())
    val timers: StateFlow<List<KitchenTimer>> = _timers.asStateFlow()
    private val jobs = HashMap<String, Job>()
    private val chimes = HashMap<String, Job>()

    init {
        createChannel(context)
    }

    fun start(seconds: Int, label: String, recipe: Recipe?) {
        if (seconds <= 0) return
        val timer = KitchenTimer(
            id = UUID.randomUUID().toString(), label = label, recipeTitle = recipe?.displayTitle ?: "",
            recipeId = recipe?.id, durationSeconds = seconds, endAtMillis = System.currentTimeMillis() + seconds * 1000L,
        )
        _timers.update { it + timer }
        schedule(timer)
    }

    fun addMinute(id: String) {
        val timer = _timers.value.firstOrNull { it.id == id } ?: return
        val base = if (timer.isFinished) System.currentTimeMillis() else timer.endAtMillis
        chimes.remove(id)?.cancel()
        val updated = timer.copy(endAtMillis = base + 60_000, isFinished = false)
        _timers.update { list -> list.map { if (it.id == id) updated else it } }
        schedule(updated)
    }

    fun cancel(id: String) {
        chimes.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
        cancelAlarm(id)
        NotificationManagerCompat.from(context).cancel(id.hashCode())
        _timers.update { list -> list.filter { it.id != id } }
    }

    private fun schedule(timer: KitchenTimer) {
        jobs.remove(timer.id)?.cancel()
        jobs[timer.id] = scope.launch {
            delay(max(0, timer.endAtMillis - System.currentTimeMillis()))
            finish(timer.id)
        }
        scheduleAlarm(timer)
    }

    private fun finish(id: String) {
        val timer = _timers.value.firstOrNull { it.id == id && !it.isFinished } ?: return
        _timers.update { list -> list.map { if (it.id == id) it.copy(isFinished = true) else it } }
        // In the background the alarm's notification does the ringing.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            cancelAlarm(id)
            chime(id)
            vibrate(context)
        } else {
            showNotification(context, timer.id, timer.label, timer.recipeTitle)
        }
    }

    /** A few chimes, like a kitchen timer. Cancelling the job silences it immediately. */
    private fun chime(id: String) {
        chimes.remove(id)?.cancel()
        chimes[id] = scope.launch {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return@launch
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            try {
                repeat(3) {
                    ringtone.play()
                    delay(2200)
                    ringtone.stop()
                    delay(400)
                }
            } finally {
                ringtone.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)?.cancel()
                }
                chimes.remove(id)
            }
        }
    }

    private fun alarmIntent(timerId: String, label: String = "", recipeTitle: String = ""): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java)
            .setAction(TimerAlarmReceiver.ACTION)
            .putExtra(TimerAlarmReceiver.EXTRA_ID, timerId)
            .putExtra(TimerAlarmReceiver.EXTRA_LABEL, label)
            .putExtra(TimerAlarmReceiver.EXTRA_RECIPE, recipeTitle)
        return PendingIntent.getBroadcast(context, timerId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleAlarm(timer: KitchenTimer) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(timer.id, timer.label, timer.recipeTitle)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAtMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAtMillis, pending)
        }
    }

    private fun cancelAlarm(id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(id))
    }

    companion object {
        const val CHANNEL_ID = "timers"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.timer_channel_description)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun showNotification(context: Context, id: String, label: String, recipeTitle: String) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("Time's up: $label")
                .setContentText(recipeTitle.ifEmpty { "Your timer is done." })
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                // The in-app timer and the backup alarm may both post; ring once.
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        }

        fun vibrate(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
        }
    }
}

/** Posts the "time's up" notification when a timer ends while Pinch is in the background. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        TimerManager.showNotification(
            context,
            intent.getStringExtra(EXTRA_ID) ?: return,
            intent.getStringExtra(EXTRA_LABEL).orEmpty(),
            intent.getStringExtra(EXTRA_RECIPE).orEmpty(),
        )
    }

    companion object {
        const val ACTION = "com.example.pinch.TIMER_DONE"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_RECIPE = "recipe"
    }
}
