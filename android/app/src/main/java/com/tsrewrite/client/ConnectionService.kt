package com.tsrewrite.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the connection and audio alive while the app is in the background; stops itself on disconnect. */
class ConnectionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var media: MediaSession? = null
    private var headsetTalking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        goForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsclient:connection").apply { acquire() }
        // Wi-Fi power saving lets the radio doze between beacons, which adds 100+ ms and heavy jitter to
        // voice; like call apps, keep it awake while connected (setting "Low-latency Wi-Fi").
        scope.launch {
            app.store.settings.map { it.lowLatencyWifi }.distinctUntilChanged().collect { on -> setWifiLock(on) }
        }
        app.session.startAudio()
        // Keep the notification's Mute/Deafen buttons in step with the app.
        scope.launch {
            app.session.inputMuted.combine(app.session.outputMuted) { a, b -> a to b }.distinctUntilChanged().drop(1).collect {
                getSystemService(NotificationManager::class.java).notify(1, notification())
            }
        }
        // Headset/Bluetooth media button toggles push-to-talk when enabled.
        if (app.store.settings.value.headsetButtonPtt) {
            media = MediaSession(this, "tsclient").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        @Suppress("DEPRECATION") val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                            headsetTalking = !headsetTalking
                            app.session.client.setPushToTalk(headsetTalking)
                        }
                        return true
                    }
                })
                isActive = true
            }
        }
        // Stopping only from here avoids stopService() racing startForeground(). It follows "a connection is
        // wanted", not the status: switching servers or reconnecting passes through "disconnected" and must
        // keep voice running. A connect started meanwhile sends a newer start id, which keeps the service.
        scope.launch {
            app.session.active.first { !it }
            stopSelf(lastStartId)
        }
    }

    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val session = (application as App).session
        when (intent?.action) {
            // Taps on the notification's icons: the state flows below repost it at once with the new look.
            ACTION_DISCONNECT -> session.disconnect()
            ACTION_MUTE -> session.toggleInputMuted()
            ACTION_DEAFEN -> session.toggleOutputMuted()
            else -> {
                // Every startForegroundService() call needs its own startForeground().
                goForeground()
            }
        }
        return START_NOT_STICKY
    }

    private fun tap(name: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, ConnectionService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    /** Server and state, with mute / deafen / disconnect icons on the right (red while muted, like the voice dock). */
    private fun notification(): Notification {
        val session = (application as App).session
        val mic = session.inputMuted.value
        val sound = session.outputMuted.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = session.bookmark?.label?.ifBlank { null } ?: session.bookmark?.address ?: "Connected"
        val text = when {
            sound -> "Deafened"
            mic -> "Microphone muted"
            else -> "Connected"
        }
        val normal = getColor(R.color.notif_icon)
        val red = getColor(R.color.notif_red)
        val view = RemoteViews(packageName, R.layout.notification_voice).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_text, text)
            setImageViewResource(R.id.notif_mute, if (mic) R.drawable.ic_notif_mic_off else R.drawable.ic_notif_mic)
            setInt(R.id.notif_mute, "setColorFilter", if (mic) red else normal)
            setContentDescription(R.id.notif_mute, if (mic) "Unmute microphone" else "Mute microphone")
            setOnClickPendingIntent(R.id.notif_mute, tap(ACTION_MUTE, 2))
            setImageViewResource(R.id.notif_deafen, if (sound) R.drawable.ic_notif_headset_off else R.drawable.ic_notif_headset)
            setInt(R.id.notif_deafen, "setColorFilter", if (sound) red else normal)
            setContentDescription(R.id.notif_deafen, if (sound) "Undeafen" else "Deafen")
            setOnClickPendingIntent(R.id.notif_deafen, tap(ACTION_DEAFEN, 3))
            setInt(R.id.notif_disconnect, "setColorFilter", red)
            setOnClickPendingIntent(R.id.notif_disconnect, tap(ACTION_DISCONNECT, 1))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(view)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        val n = notification()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val type = if (android.os.Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        media?.release()
        val app = application as App
        app.session.stopAudio()
        wakeLock?.release()
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun setWifiLock(on: Boolean) {
        if (!on) { wifiLock?.let { if (it.isHeld) it.release() }; return }
        val lock = wifiLock ?: applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.let { wm ->
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm.createWifiLock(mode, "tsclient:voice").apply { setReferenceCounted(false) }
        }?.also { wifiLock = it }
        lock?.acquire()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val ACTION_DISCONNECT = "disconnect"
        private const val ACTION_MUTE = "mute"
        private const val ACTION_DEAFEN = "deafen"
    }
}
