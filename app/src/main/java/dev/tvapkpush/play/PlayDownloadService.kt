package dev.tvapkpush.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.tvapkpush.MainActivity
import dev.tvapkpush.PushApplication

class PlayDownloadService : Service() {
    private val play get() = (application as PushApplication).play
    private val main = Handler(Looper.getMainLooper())
    private val observer: (PlayState) -> Unit = ::render
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val shown = mutableMapOf<String, DownloadTask>()
    private var lastNotification = 0L
    private var startId = 0
    private var stopping = false
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        private fun changed() { main.post { if (!stopping) play.networkChanged() } }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "应用下载", NotificationManager.IMPORTANCE_LOW))
        notifications.cancel(RESULT)
        startForeground(NOTIFICATION, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APKPush:download").apply {
            setReferenceCounted(false)
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        stopping = false
        startForeground(NOTIFICATION, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val action = when (intent?.action) {
            CANCEL -> DownloadAction.CANCEL
            PAUSE -> DownloadAction.PAUSE
            RESUME -> DownloadAction.RESUME
            else -> null
        }
        play.serviceStarted(observer, intent?.getStringExtra("task"), action)
        return START_NOT_STICKY
    }

    private fun render(state: PlayState) {
        if (stopping) return
        if (state.downloading && !wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
        else if (!state.downloading && wakeLock.isHeld) wakeLock.release()
        val now = android.os.SystemClock.elapsedRealtime()
        val changed = state.tasks.map { it.id to it.phase } != shown.values.map { it.id to it.phase }
        if (changed || now - lastNotification >= 1000 || !state.backgroundDownload) {
            lastNotification = now
            if (canNotify()) {
                for (id in shown.keys - state.tasks.map { it.id }.toSet()) {
                    val completed = state.downloads.firstOrNull { it.libraryId == id }
                    if (completed == null) notifications.cancel(id, RESULT)
                    else notifications.notify(id, RESULT, builder(completed.label, "下载完成", false).build())
                }
                state.tasks.forEach { task ->
                    if (shown[task.id] != task) notifications.notify(task.id, RESULT, notification(task))
                }
                notifications.notify(NOTIFICATION, summary(state))
            }
            shown.clear()
            state.tasks.forEach { shown[it.id] = it }
        }
        if (!state.busy && !state.backgroundDownload) {
            stopping = true
            play.serviceStopped(observer)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun builder(title: String, message: String, ongoing: Boolean): Notification.Builder {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra("show_downloads", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(message).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing).setShowWhen(false)
            .setGroup(CHANNEL).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setCategory(Notification.CATEGORY_PROGRESS)
    }

    private fun summary(state: PlayState? = null): Notification {
        val running = state?.tasks?.count { it.phase.inFlight } ?: 0
        val queued = state?.tasks?.count { it.phase == DownloadPhase.QUEUED } ?: 0
        val message = if (running + queued == 0) "正在整理下载" else "$running 项下载中 · $queued 项等待"
        return builder("APK Push", message, true).setGroupSummary(true).build()
    }

    private fun notification(task: DownloadTask): Notification {
        val progress = if (task.total > 0) " · ${task.bytes * 100 / task.total}%" else ""
        return builder(task.label, task.message + progress, task.phase.needsService).apply {
            if (task.phase.inFlight) {
                if (task.total > 0) setProgress(1000, (task.bytes * 1000 / task.total).toInt().coerceIn(0, 1000), false)
                else setProgress(0, 0, true)
            }
            fun action(title: String, command: String) {
                val intent = PendingIntent.getForegroundService(this@PlayDownloadService, 1,
                    Intent(this@PlayDownloadService, PlayDownloadService::class.java).setAction(command)
                        .setData(Uri.parse("apkpush://download/${task.id}")).putExtra("task", task.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                addAction(Notification.Action.Builder(null, title, intent).build())
            }
            if (task.phase.canPause) action("暂停", PAUSE)
            else if (task.phase.canRetry) action(if (task.phase == DownloadPhase.PAUSED) "继续" else "重试", RESUME)
            if (task.phase != DownloadPhase.CANCELLED) action("取消", CANCEL)
        }.build()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        stopping = true
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        play.serviceStopped(observer)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "play-downloads"
        private const val NOTIFICATION = 21
        internal const val RESULT = 22
        private const val CANCEL = "dev.tvapkpush.CANCEL_DOWNLOAD"
        private const val PAUSE = "dev.tvapkpush.PAUSE_DOWNLOAD"
        private const val RESUME = "dev.tvapkpush.RESUME_DOWNLOAD"
    }
}
