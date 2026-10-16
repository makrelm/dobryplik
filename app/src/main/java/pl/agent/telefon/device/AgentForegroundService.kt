package pl.agent.telefon.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.agent.telefon.MainActivity
import pl.agent.telefon.R
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.AgentStatus
import pl.agent.telefon.core.Prefs
import pl.agent.telefon.core.RuntimeState

/** Powiadomienia: stały status z przyciskami sterującymi oraz alerty (pytania i zatwierdzenia). */
object Notifs {
    const val CH_STATUS = "agent_status"
    const val CH_ALERT = "agent_alert"
    const val ID_STATUS = 1
    const val ID_ALERT = 2
    const val ID_RESULT = 3

    const val ACTION_PAUSE = "pl.agent.telefon.PAUSE"
    const val ACTION_RESUME = "pl.agent.telefon.RESUME"
    const val ACTION_STOP = "pl.agent.telefon.STOP"
    const val ACTION_APPROVE = "pl.agent.telefon.APPROVE"
    const val ACTION_REJECT = "pl.agent.telefon.REJECT"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Stan agenta", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Pytania i zatwierdzenia", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun control(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, ControlReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun status(ctx: Context, s: RuntimeState): Notification {
        val text = s.stepText.ifBlank { s.message }.ifBlank { "Gotowy do pracy" }
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Agent: ${s.status.label}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (s.status.hasTask) {
            if (s.status == AgentStatus.PAUSED) {
                b.addAction(0, "Wznów", control(ctx, ACTION_RESUME))
            } else {
                b.addAction(0, "Pauza", control(ctx, ACTION_PAUSE))
            }
            b.addAction(0, "Stop", control(ctx, ACTION_STOP))
        }
        return b.build()
    }

    fun alert(ctx: Context, title: String, text: String, approval: Boolean) {
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
        if (approval) {
            b.addAction(0, "Zatwierdź", control(ctx, ACTION_APPROVE))
            b.addAction(0, "Odrzuć", control(ctx, ACTION_REJECT))
        }
        safeNotify(ctx, ID_ALERT, b.build())
    }

    fun clearAlert(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_ALERT)
    }

    fun result(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        safeNotify(ctx, ID_RESULT, n)
    }

    private fun safeNotify(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // brak zgody na powiadomienia — nic nie robimy
        }
    }
}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifs.ACTION_PAUSE -> AgentRuntime.pause()
            Notifs.ACTION_RESUME -> AgentRuntime.resume()
            Notifs.ACTION_STOP -> AgentRuntime.stop("Zatrzymano przyciskiem w powiadomieniu.")
            Notifs.ACTION_APPROVE -> AgentRuntime.approve(true)
            Notifs.ACTION_REJECT -> AgentRuntime.approve(false)
        }
    }
}

/**
 * Usługa pierwszoplanowa z widocznym powiadomieniem: utrzymuje proces przy życiu podczas długiej pracy
 * i (po zgodzie użytkownika) jest właścicielem sesji MediaProjection.
 */
class AgentForegroundService : Service() {

    companion object {
        const val ACTION_START = "pl.agent.telefon.START"
        const val ACTION_CAPTURE = "pl.agent.telefon.CAPTURE"
        const val ACTION_STOP_CAPTURE = "pl.agent.telefon.STOP_CAPTURE"
        const val ACTION_CHECK = "pl.agent.telefon.CHECK"

        fun ensureRunning(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_START)
            )
        }

        fun startCapture(ctx: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AgentForegroundService::class.java)
                    .setAction(ACTION_CAPTURE).putExtra("code", resultCode).putExtra("data", data)
            )
        }

        fun stopCapture(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_STOP_CAPTURE))
            } catch (e: Exception) {
                ScreenCapture.stop()
            }
        }

        /** Usługa sama się zatrzyma, gdy nie ma zadania ani przechwytywania. */
        fun requestCheck(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_CHECK))
            } catch (e: Exception) {
                // proces w tle bez uruchomionej usługi — nic do sprawdzania
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(false)
        scope.launch {
            AgentRuntime.state.collect { st ->
                val key = st.status.name + "|" + st.stepText + "|" + st.message
                if (key != lastKey) {
                    lastKey = key
                    getSystemService(NotificationManager::class.java).notify(Notifs.ID_STATUS, Notifs.status(this@AgentForegroundService, st))
                }
                syncWakeLock(st.status.hasTask)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        when (i.action) {
            ACTION_CAPTURE -> {
                val code = i.getIntExtra("code", 0)
                val data = IntentCompat.getParcelableExtra(i, "data", Intent::class.java)
                if (data != null) {
                    goForeground(true) // typ mediaProjection dopiero PO uzyskaniu zgody użytkownika
                    ScreenCapture.start(applicationContext, code, data)
                }
            }
            ACTION_STOP_CAPTURE -> {
                ScreenCapture.stop()
                goForeground(false)
                maybeStop()
            }
            ACTION_CHECK -> maybeStop()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(withProjection: Boolean) {
        val n = Notifs.status(this, AgentRuntime.state.value)
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(Notifs.ID_STATUS, n, type)
        } else if (withProjection) {
            startForeground(Notifs.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(Notifs.ID_STATUS, n)
        }
    }

    private fun maybeStop() {
        val st = AgentRuntime.state.value
        if (!st.status.hasTask && !ScreenCapture.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Opcjonalne utrzymywanie ekranu włączonego podczas zadania (ustawienie w aplikacji). */
    private fun syncWakeLock(taskActive: Boolean) {
        val want = taskActive && Prefs.keepScreenOnEnabled()
        if (want && wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "agent:screen")
            wl.acquire(60 * 60 * 1000L)
            wakeLock = wl
        } else if (!want && wakeLock != null) {
            try { wakeLock?.release() } catch (e: Exception) { }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
        ScreenCapture.stop()
        if (AgentRuntime.state.value.status.hasTask) {
            AgentRuntime.stop("Usługa pierwszoplanowa została zatrzymana przez system.")
        }
        super.onDestroy()
    }
}
