package com.tsrewrite.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import uniffi.tscore.identityUid
import com.tsrewrite.client.MyTeamSpeakProtocol.Writer

/** Session and decrypted account key are deliberately never written to preferences or backups. */
internal class MyTeamSpeakCloud(
    private val transport: (String, String, ByteArray) -> ByteArray = ::request,
    private val uid: (String) -> String = ::identityUid,
) : AutoCloseable {
    class CloudException(message: String) : Exception(message)
    class RemoteIdentity(val id: String, val name: String, val data: String, val nickname: String, val uid: String)
    class UploadResult(val added: Int, val skipped: Int)
    private class Account(val email: String, val session: String, val key: ByteArray)
    private class Snapshot(val globalVersion: String, val version: String, val identities: List<RemoteIdentity>)
    private val mutex = Mutex()
    private val deviceId = UUID.randomUUID().toString()
    @Volatile private var account: Account? = null

    suspend fun login(email: String, password: String, otp: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            close()
            val normalized = MyTeamSpeakProtocol.normalizedEmail(email)
            if (normalized.isBlank() || password.isEmpty()) throw CloudException("Enter your myTeamSpeak email and password.")
            val secret = password.toByteArray(Charsets.UTF_8)
            var key: ByteArray? = null
            try {
                val login = MyTeamSpeakProtocol.loginPassword(normalized, secret)
                val payload = Writer().text(1, normalized).text(2, login).number(3, 0).number(4, 0).number(5, 1)
                    .text(7, deviceId).text(9, "TeamSpeak Android (TS3)")
                if (otp.isNotBlank()) payload.text(6, otp.trim())
                val reply = call("authentication", "login", payload.build())
                when (reply.number(5)) {
                    200L -> Unit
                    208L, 209L, 210L, 211L -> throw CloudException("Enter a current two-factor authentication code and try again.")
                    202L -> throw CloudException("The email or password was not accepted.")
                    203L -> throw CloudException("Verify your myTeamSpeak email before signing in.")
                    else -> throw CloudException("myTeamSpeak could not sign in (${reply.number(5)}). Try again later.")
                }
                val session = reply.text(2)
                if (session.isBlank()) throw CloudException("myTeamSpeak did not return a session.")
                key = try { MyTeamSpeakProtocol.accountKey(normalized, secret, login, reply.text(4), reply.bytes(1)) }
                catch (_: Exception) { throw CloudException("Signed in, but cloud data could not be unlocked. Restore access using your recovery key in the official client first. Nothing was changed.") }
                val candidate = Account(normalized, session, key)
                // Only expose a ready account after a read-only identity fetch and integrity validation.
                fetch(candidate)
                currentCoroutineContext().ensureActive()
                account = candidate
                key = null
                normalized
            } finally { secret.fill(0); key?.fill(0) }
        }
    }

    suspend fun listIdentities(): List<RemoteIdentity> = withContext(Dispatchers.IO) {
        mutex.withLock { fetch(requireAccount()).identities }
    }

    suspend fun upload(identities: List<Identity>): UploadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = requireAccount()
            if (identities.isEmpty() || identities.size > 256) throw CloudException("Choose between 1 and 256 identities.")
            val selected = identities.distinctBy { uid(it.data) }
            val before = fetch(active)
            val existing = before.identities.map { it.uid }.toSet()
            val additions = selected.filter { uid(it.data) !in existing }
            if (additions.isEmpty()) return@withLock UploadResult(0, selected.size)
            val entries = Writer().number(1, 1).text(2, before.version)
            for (identity in additions) {
                val id = UUID.randomUUID().toString()
                val version = UUID.randomUUID().toString()
                val identityData = Writer().text(1, identity.data).text(2, identity.name).text(3, identity.nickname).number(5, 0).build()
                val plaintext = Writer().text(2, id).text(3, version).number(4, 0).text(5, NIL_UUID).number(6, 1)
                    .number(9, System.currentTimeMillis() / 1000).bytes(17, identityData).build()
                val blob = try { MyTeamSpeakProtocol.encryptItem(active.key, plaintext) }
                finally { plaintext.fill(0); identityData.fill(0) }
                // New UUIDs only. No replacement, deletion, account reset or fallback operations.
                entries.bytes(3, Writer().text(1, id).text(2, version).bytes(3, blob).build())
            }
            currentCoroutineContext().ensureActive()
            if (account !== active) throw CloudException("Sign in again before uploading.")
            val payload = Writer().text(1, active.session).bytes(2, entries.build()).text(3, before.globalVersion).number(4, 1).build()
            val response = try { call("synchronization", "synchronizeItems", payload) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { throw CloudException("Upload could not be confirmed. Refresh cloud identities before trying again; some may have arrived.") }
            when (response.number(1)) {
                300L, 301L -> Unit
                102L -> { close(); throw CloudException("Your session expired. Sign out and sign in again.") }
                303L -> throw CloudException("Your cloud identities changed elsewhere. Refresh them before trying again.")
                304L -> throw CloudException("Your myTeamSpeak identity limit was reached.")
                else -> throw CloudException("Upload was not confirmed (${response.number(1)}). Refresh cloud identities before retrying.")
            }
            val after = try { fetch(active) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { throw CloudException("The server received the upload, but verification failed. Refresh before retrying.") }
            val actual = after.identities.map { it.uid }.toSet()
            if (!actual.containsAll(existing) || additions.any { uid(it.data) !in actual }) {
                throw CloudException("Cloud contents changed during upload. Check them in the official client before trying again.")
            }
            UploadResult(additions.size, selected.size - additions.size)
        }
    }

    private fun requireAccount(): Account = account ?: throw CloudException("Sign in to myTeamSpeak first.")

    private suspend fun fetch(active: Account): Snapshot {
        val query = Writer().text(1, active.session).bytes(2, Writer().number(1, 1).text(2, NIL_UUID).build())
            .text(3, NIL_UUID).number(4, 1).build()
        val reply = call("synchronization", "requestServerItems", query)
        when (reply.number(1)) {
            300L, 301L, 302L -> Unit
            102L -> { close(); throw CloudException("Your session expired. Sign out and sign in again.") }
            else -> throw CloudException("Could not read cloud identities (${reply.number(1)}).")
        }
        val classes = reply.messages(2).filter { it.number(1) == 1L }
        if (classes.size != 1) throw CloudException("The server did not return an identity list. Nothing was changed.")
        val group = classes.single()
        val details = group.messages(3)
        if (details.size > 4096) throw CloudException("The cloud identity list is too large.")
        val result = mutableListOf<RemoteIdentity>()
        for (detail in details) {
            if (detail.number(4) != 0L) continue
            val plain = try { MyTeamSpeakProtocol.decryptItem(active.key, detail.bytes(3)) }
            catch (_: Exception) { throw CloudException("A cloud identity could not be decrypted. Nothing was changed. Check cloud recovery in the official client.") }
            try {
                val item = MyTeamSpeakProtocol.parse(plain)
                if (item.number(6) != 1L || item.text(2) != detail.text(1)) throw CloudException("Unexpected cloud identity format. Nothing was changed.")
                val identity = MyTeamSpeakProtocol.parse(item.bytes(17))
                val data = identity.text(1)
                val identityUid = try { uid(data) } catch (_: Exception) { throw CloudException("An invalid cloud identity was returned. Nothing was changed.") }
                result.add(RemoteIdentity(detail.text(1), identity.text(2).ifBlank { "Cloud identity" }, data, identity.text(3), identityUid))
            } finally { plain.fill(0) }
        }
        if (result.map { it.id }.distinct().size != result.size) throw CloudException("Duplicate cloud item identifiers. Nothing was changed.")
        val version = group.text(2).ifBlank { if (group.number(4) != 0L || reply.number(1) == 302L) NIL_UUID else throw CloudException("Cloud version missing.") }
        val global = reply.text(3).ifBlank { throw CloudException("Cloud version missing.") }
        return Snapshot(global, version, result)
    }

    private suspend fun call(endpoint: String, method: String, payload: ByteArray): MyTeamSpeakProtocol.Message {
        try {
            currentCoroutineContext().ensureActive()
            val reply = transport(endpoint, method, payload)
            return try {
                currentCoroutineContext().ensureActive()
                MyTeamSpeakProtocol.parse(reply)
            } finally { reply.fill(0) }
        } finally { payload.fill(0) }
    }

    override fun close() { account?.key?.fill(0); account = null }

    companion object {
        private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
        private fun request(endpoint: String, method: String, payload: ByteArray): ByteArray {
            require(endpoint == "authentication" || endpoint == "synchronization")
            val connection = URL("https://clientapi.myteamspeak.com/$endpoint").openConnection() as HttpsURLConnection
            val framed = MyTeamSpeakProtocol.frame(method, payload)
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/ts3cloud")
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "TeamSpeak-Android-TS3/0.1")
                connection.setFixedLengthStreamingMode(framed.size)
                connection.outputStream.use { it.write(framed) }
                if (connection.responseCode != 200) throw CloudException("myTeamSpeak returned HTTP ${connection.responseCode}. Try again later.")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size > MyTeamSpeakProtocol.MAX_MESSAGE - out.size()) throw CloudException("Cloud response too large.")
                        out.write(buffer, 0, size)
                    }
                    out.toByteArray()
                }
            } finally { framed.fill(0); connection.disconnect() }
        }
    }
}
