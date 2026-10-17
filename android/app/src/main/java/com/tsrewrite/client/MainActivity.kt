package com.tsrewrite.client

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tsrewrite.client.ui.BookmarksScreen
import com.tsrewrite.client.ui.IdentitiesScreen
import com.tsrewrite.client.ui.ServerScreen
import com.tsrewrite.client.ui.SettingsPage
import com.tsrewrite.client.ui.TsTheme
import uniffi.tscore.Status
import uniffi.tscore.VoiceMode

enum class Screen { Bookmarks, Identities, Settings }

class MainActivity : ComponentActivity() {
    /** A `ts3server://` link waiting to be shown as a prefilled bookmark. */
    val pendingLink = mutableStateOf<Bookmark?>(null)
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Voices play on the media stream (full band), so the volume keys control it.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(perms.toTypedArray())
        handleLink(intent)
        setContent { TsTheme { Root(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Volume keys act as push-to-talk while connected, if enabled. */
    @android.annotation.SuppressLint("RestrictedApi") // false positive: this is Activity's public override
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = app.store.settings.value
        val connected = app.session.status.value.first == Status.CONNECTED
        if (connected && s.volumeKeyPtt && s.voiceMode == VoiceMode.PUSH_TO_TALK &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.repeatCount == 0) app.session.client.setPushToTalk(event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "ts3server") return
        val port = uri.getQueryParameter("port")
        val host = uri.host ?: return
        pendingLink.value = Bookmark(
            label = host,
            address = if (port != null) "$host:$port" else host,
            nickname = uri.getQueryParameter("nickname") ?: "",
            serverPassword = uri.getQueryParameter("password") ?: "",
            channel = uri.getQueryParameter("channel") ?: "",
            channelPassword = uri.getQueryParameter("channelpassword") ?: "",
        )
    }

    @Composable
    private fun Root(app: App) {
        var screen by rememberSaveable { mutableStateOf(Screen.Bookmarks) }
        val status by app.session.status.collectAsState()
        val settings by app.store.settings.collectAsState()
        val connected = status.first != Status.DISCONNECTED
        LaunchedEffect(connected, settings.keepScreenOn) {
            if (connected && settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (connected) {
            ServerScreen(app.session, app.store, app.badges)
            return
        }
        BackHandler(screen != Screen.Bookmarks) { screen = Screen.Bookmarks }
        when (screen) {
            Screen.Bookmarks -> BookmarksScreen(app.store, app.session, app.updater, pendingLink, lastError = status.second,
                onIdentities = { screen = Screen.Identities }, onSettings = { screen = Screen.Settings })
            Screen.Identities -> IdentitiesScreen(app.store) { screen = Screen.Bookmarks }
            Screen.Settings -> SettingsPage(app.store, app.session) { screen = Screen.Bookmarks }
        }
    }
}
