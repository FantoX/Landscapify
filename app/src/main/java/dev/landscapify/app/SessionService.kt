package dev.landscapify.app

import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java)
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}

/** Keeps the orientation window alive only while the selected app is in use. */
class SessionService : Service() {
    private val prefs by lazy { getSharedPreferences("landscapify", 0) }
    @Volatile private var stopRequested = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Landscape sessions", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRequested = true
            worker?.interrupt()
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) {
            @Suppress("DEPRECATION")
            val receiver: ResultReceiver? = intent?.getParcelableExtra(RECEIVER)
            receiver?.send(FAILED, Bundle().apply {
                putString(MESSAGE, "Another landscape session is already active.")
            })
            return START_NOT_STICKY
        }
        showForeground("Starting landscape session")
        running = true
        stopRequested = false
        worker = Thread({
            try {
                @Suppress("DEPRECATION")
                val receiver: ResultReceiver? = intent?.getParcelableExtra(RECEIVER)
                startSession(intent?.getStringExtra(PACKAGE), receiver)
            } finally {
                running = false
                worker = null
                stopSelf(startId)
            }
        }, "landscape-session").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startSession(packageName: String?, receiver: ResultReceiver?) {
        var readySent = false
        try {
            check(packageName != null && PACKAGE_PATTERN.matches(packageName)) { "Invalid app package." }
            activePackage = packageName
            check(!AppCatalog(application as Application).paused) { "Landscape forcing is paused." }
            check(!prefs.contains("pending_restore")) {
                "An older session still has a recovery record. Restore it with the previous version before updating."
            }
            check(hasUsageAccess(this)) { "Grant Usage Access before launching an app." }
            check(OrientationService.isConnected()) {
                "Enable Landscapify in Accessibility settings before launching an app."
            }
            check(packageManager.getLaunchIntentForPackage(packageName) != null) {
                "This app is no longer installed or launchable."
            }
            OrientationService.setLandscape(true)
            if (stopRequested) error("Session cancelled.")
            showForeground("Landscape session active")
            receiver?.send(READY, Bundle.EMPTY)
            readySent = true
            watch(packageName)
        } catch (error: Exception) {
            if (!stopRequested) {
                Log.e("LandscapifySession", "Session ended", error)
                val message = error.message ?: "The landscape session could not start."
                prefs.edit().putString("last_error", message).apply()
                if (!readySent) receiver?.send(FAILED, Bundle().apply { putString(MESSAGE, message) })
            }
        } finally {
            runCatching { if (OrientationService.isConnected()) OrientationService.setLandscape(false) }
                .onFailure { Log.e("LandscapifySession", "Could not remove orientation window", it) }
            activePackage = null
        }
    }

    private fun watch(packageName: String) {
        val usage = getSystemService(UsageStatsManager::class.java)
        var lastEventTime = System.currentTimeMillis() - 30_000
        var foreground: String? = null
        var sawTarget = false
        var awaySince = 0L
        val launchDeadline = SystemClock.elapsedRealtime() + 12_000
        while (!stopRequested) {
            check(hasUsageAccess(this)) { "Usage Access was removed; the session ended." }
            check(OrientationService.isConnected()) { "Accessibility access was removed; the session ended." }
            val now = System.currentTimeMillis()
            val events = usage.queryEvents(maxOf(lastEventTime, now - 10_000), now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.timeStamp < lastEventTime) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foreground = event.packageName
                        lastEventTime = event.timeStamp + 1
                    }
                    UsageEvents.Event.KEYGUARD_SHOWN -> {
                        foreground = null
                        lastEventTime = event.timeStamp + 1
                    }
                }
            }
            val elapsed = SystemClock.elapsedRealtime()
            if (foreground == packageName) {
                awaySince = 0L
                if (!sawTarget) {
                    sawTarget = true
                    Thread.sleep(1200)
                    if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                        AppCatalog(application as Application).markUnsupported(packageName)
                        error("${packageName} did not rotate to landscape on this device.")
                    }
                }
            } else if (sawTarget) {
                if (awaySince == 0L) awaySince = elapsed
                if (elapsed - awaySince >= 3000) return
            } else if (elapsed > launchDeadline) {
                error("The app did not open; the landscape session ended.")
            }
            Thread.sleep(1500)
        }
    }

    private fun showForeground(text: String) {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_landscape)
            .setContentTitle("Landscapify")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Stop session",
                PendingIntent.getService(this, 1, Intent(this, SessionService::class.java).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRequested = true
        worker?.interrupt()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRequested = true
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "landscape_sessions"
        private const val NOTIFICATION_ID = 41
        private const val PACKAGE = "package"
        private const val RECEIVER = "receiver"
        private const val MESSAGE = "message"
        private const val START = "start"
        private const val STOP = "stop"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        const val READY = 1
        const val FAILED = 2
        @Volatile var running = false
            private set
        @Volatile private var activePackage: String? = null

        fun start(context: Context, packageName: String, receiver: ResultReceiver) {
            ContextCompat.startForegroundService(context,
                Intent(context, SessionService::class.java).setAction(START)
                    .putExtra(PACKAGE, packageName).putExtra(RECEIVER, receiver))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(STOP))
        }

        fun stopIfActive(context: Context, packageName: String) {
            if (activePackage == packageName) stop(context)
        }

        fun failureMessage(bundle: Bundle?): String = bundle?.getString(MESSAGE)
            ?: "The landscape session could not start."
    }
}
