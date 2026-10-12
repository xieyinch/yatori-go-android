package app.yatori.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import mobilecore.Mobilecore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YatoriForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "YatoriTaskMonitor").apply { isDaemon = true }
        }

    @Volatile
    private var statusFuture: ScheduledFuture<*>? = null

    @Volatile
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val restartedAfterActiveTask = intent == null && prefs.getBoolean(KEY_HAD_ACTIVE_TASK, false)

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification("正在读取任务状态…"))
            foregroundStarted = true
        }

        if (statusFuture == null || statusFuture?.isCancelled == true) {
            statusFuture = statusExecutor.scheduleWithFixedDelay(
                { refreshStatus(restartedAfterActiveTask) },
                if (intent?.action == ACTION_STOP_ALL) 5 else 0,
                STATUS_REFRESH_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        if (intent?.action == ACTION_STOP_ALL) {
            statusExecutor.execute {
                stopAllTasks()
                refreshStatus(restartedAfterActiveTask)
            }
        }
        return START_STICKY
    }

    private fun refreshStatus(restartedAfterActiveTask: Boolean) {
        try {
            EngineRegistry.ensureInitialized(File(filesDir, "yatori").absolutePath)

            val root = JSONObject(Mobilecore.getTaskStatusesJSON())
            if (!root.optBoolean("ok")) {
                throw IllegalStateException(root.optString("error", "任务状态读取失败"))
            }
            val tasks = root.optJSONArray("data") ?: JSONArray()
            var running = 0
            var paused = 0
            for (i in 0 until tasks.length()) {
                when (tasks.optJSONObject(i)?.optString("state")) {
                    "running" -> running++
                    "paused" -> paused++
                }
            }

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_HAD_ACTIVE_TASK, running > 0 || paused > 0).apply()

            if (running > 0 || paused > 0) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val summary = buildList {
                    if (running > 0) add("运行中 $running")
                    if (paused > 0) add("已暂停 $paused")
                }.joinToString(" · ")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("$summary · 更新于 $time"))
                return
            }

            if (restartedAfterActiveTask) {
                notifyInterrupted()
                Log.w(TAG, "process restarted while a task session was active; task state cannot be restored")
                prefs.edit().putBoolean(KEY_HAD_ACTIVE_TASK, false).apply()
            }
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "task status refresh failed", t)
            val detail = (t.message ?: t.javaClass.simpleName).take(80)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("任务状态暂不可读 · $detail"))
        }
    }

    private fun notification(status: String): Notification {
        val stopIntent = Intent(this, YatoriForegroundService::class.java).setAction(ACTION_STOP_ALL)
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPending = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Yatori 任务状态")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止全部", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun notifyInterrupted() {
        val openPending = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Yatori 检测到进程重启")
            .setContentText("上次任务状态无法确认，请打开应用查看日志")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(INTERRUPTION_NOTIFICATION_ID, notice)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Yatori tasks", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun stopAllTasks() {
        if (!EngineRegistry.initialized) return
        try {
            val arr: JSONArray = JSONObject(Mobilecore.getTaskStatusesJSON()).optJSONArray("data") ?: return
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("state") == "running") {
                    Mobilecore.stopTask(item.optString("uid"))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stop all tasks failed", t)
        }
    }

    override fun onDestroy() {
        statusFuture?.cancel(false)
        statusExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_REFRESH = "app.yatori.android.REFRESH"
        const val ACTION_STOP_ALL = "app.yatori.android.STOP_ALL"
        private const val TAG = "YatoriAndroid"
        private const val PREFS_NAME = "yatori_runtime"
        private const val KEY_HAD_ACTIVE_TASK = "had_active_task"
        private const val CHANNEL_ID = "yatori_tasks"
        private const val NOTIFICATION_ID = 42
        private const val INTERRUPTION_NOTIFICATION_ID = 43
        private const val STATUS_REFRESH_SECONDS = 5L
    }
}
