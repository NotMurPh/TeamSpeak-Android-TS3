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
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        // Every startForegroundService() call needs its own startForeground().
        goForeground()
        if (intent?.action == ACTION_DISCONNECT) (application as App).session.disconnect()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val app = application as App
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.session.bookmark?.label?.ifBlank { null } ?: app.session.bookmark?.address ?: "Connected")
            .setContentText("Connected")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .setOngoing(true)
            .build()
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
    }
}
