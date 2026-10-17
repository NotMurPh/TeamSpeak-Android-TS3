package com.tsrewrite.client

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A release as described by the `update.json` asset the release workflow publishes. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val certificateSha256: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo, val sameKey: Boolean) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Downloaded and verified; waiting for Android's install confirmation. */
    data class Installing(val info: UpdateInfo) : UpdateState
    data class SavedToDownloads(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/** Checks GitHub for a newer release and installs it, in-app or from the Downloads folder. */
class Updater(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "update")

    val installedVersion: String get() = packageInfo().versionName ?: "?"

    /** [manual] also reports "up to date" and errors; the launch check stays quiet unless there is an update. */
    fun check(manual: Boolean = false) {
        if (job?.isActive == true) return
        if (!manual && _state.value != UpdateState.Idle) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val info = fetchInfo()
                if (info.versionCode > installedCode()) UpdateState.Available(info, info.certificateSha256 == installedCertificate())
                else { dir.deleteRecursively(); UpdateState.UpToDate }
            } catch (e: Exception) {
                Log.w(TAG, "update check", e)
                if (manual) UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}", null)
                else UpdateState.Idle
            }
        }
    }

    /** Downloads into the app's cache, verifies the checksum and hands the APK to Android's installer. */
    fun downloadAndInstall(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val apk = File(dir, "update-${info.versionCode}.apk")
                if (!apk.isFile || sha256(apk) != info.sha256) download(info, apk)
                _state.value = UpdateState.Installing(info)
                install(apk)
            } catch (e: Exception) {
                Log.w(TAG, "update download", e)
                _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", info)
            }
        }
    }

    /** Lets the system download manager save the APK to Downloads, to install from there. */
    fun saveToDownloads(info: UpdateInfo) {
        val uri = Uri.parse(info.downloadUrl)
        if (Build.VERSION.SDK_INT < 29) {
            // Writing to the public Downloads folder needs a storage permission before Android 10; the browser has it.
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val request = DownloadManager.Request(uri)
                .setTitle("TS Client ${info.versionName}")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TS-Client-${info.versionCode}.apk")
            context.getSystemService(DownloadManager::class.java).enqueue(request)
        }
        _state.value = UpdateState.SavedToDownloads(info)
    }

    fun dismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /** Called by [InstallResultReceiver] with the installer's outcome. */
    internal fun onInstallResult(intent: Intent) {
        val info = (_state.value as? UpdateState.Installing)?.info
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> { dir.deleteRecursively(); _state.value = UpdateState.UpToDate }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install status $status: $message")
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && info != null)
                    UpdateState.Available(info, true) // the user tapped Cancel; offer it again
                else UpdateState.Failed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "This copy of the app is signed differently. Export a backup, uninstall it, then install the update from Downloads."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                        else -> "Install failed${message?.let { ": $it" } ?: "."}"
                    }, info,
                )
            }
        }
    }

    /** Android only installs from apps the user allowed; returns false after opening that setting. */
    fun ensureInstallAllowed(): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    private fun fetchInfo(): UpdateInfo {
        val conn = open(UPDATE_URL)
        val json = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
        val info = UpdateInfo(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            downloadUrl = json.getString("downloadUrl"),
            sha256 = json.getString("sha256").lowercase(),
            certificateSha256 = json.optString("certificateSha256").lowercase(),
        )
        check(json.getInt("schemaVersion") == 1) { "unknown update format" }
        check(json.getString("applicationId") == context.packageName) { "update is for another app" }
        check(json.getInt("minSdk") <= Build.VERSION.SDK_INT) { "update needs a newer Android version" }
        check(json.getString("abi") in Build.SUPPORTED_ABIS) { "update is for another processor type" }
        check(info.downloadUrl.startsWith(DOWNLOAD_PREFIX)) { "unexpected download location" }
        check(info.sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid checksum" }
        return info
    }

    private fun download(info: UpdateInfo, apk: File) {
        dir.deleteRecursively(); dir.mkdirs()
        val part = File(dir, apk.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(info.downloadUrl)
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var shown = -1
            _state.value = UpdateState.Downloading(info, 0f)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != shown) { shown = pct; _state.value = UpdateState.Downloading(info, pct.coerceAtLeast(0) / 100f) }
                    }
                }
            }
        } finally { conn.disconnect() }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != info.sha256) { part.delete(); error("the download is corrupted (checksum mismatch)") }
        check(part.renameTo(apk)) { "couldn't store the download" }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName); setSize(apk.length()) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "TS-Client/${installedVersion}")
            if (responseCode !in 200..299) { disconnect(); error("server answered $responseCode") }
        }

    private fun packageInfo(flags: Int = 0): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    private fun installedCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) packageInfo().longVersionCode else @Suppress("DEPRECATION") packageInfo().versionCode.toLong()

    /** SHA-256 of this app's signing certificate, to tell whether an update can install over it. */
    @Suppress("DEPRECATION")
    private fun installedCertificate(): String? = runCatching {
        val cert = if (Build.VERSION.SDK_INT >= 28)
            packageInfo(PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.singleOrNull()
        else packageInfo(PackageManager.GET_SIGNATURES).signatures?.singleOrNull()
        cert?.toByteArray()?.let { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "Updater"
        private const val REPO = "NotMurPh/teamspeak-android-ts3"
        private const val UPDATE_URL = "https://github.com/$REPO/releases/latest/download/update.json"
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as App).updater.onInstallResult(intent)
    }
}
