package com.tsrewrite.client

import com.tsrewrite.client.MyTeamSpeakProtocol.Writer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MyTeamSpeakCloudTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val email = "person@example.com"
    private val password = "test-password"
    private val wrappingKey = hex("2b9613d9545a63021037e9fbdc4a9628c76a7ac5f2b8018b6a6e59a8844c972e")
    private val accountKey = ByteArray(32) { it.toByte() }

    @Test fun passwordDerivationMatchesIndependentVectors() {
        assertEquals("zMWGDkRoNJt8vBLh19OIo0qV6PJXxk2NGLcqW3UXlB55KF9Hs+XvXN1cx2sSfoSX",
            MyTeamSpeakProtocol.loginPassword("PERSON@example.com", password.toByteArray()))
        assertArrayEquals(hex("bac70d0bf831669c09003687e42854652b99e02270fc861598801f97e541abe557315992736f96fc730cd3292f8b5da5"),
            Base64.getDecoder().decode(MyTeamSpeakProtocol.loginPassword(email, "p\u00e4ssword".toByteArray())))
    }

    private fun keyEnvelope(): ByteArray {
        val iv = ByteArray(12) { (it + 10).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(accountKey)
        return byteArrayOf(2) + encrypted.copyOfRange(32, 48) + iv + encrypted.copyOfRange(0, 32)
    }

    @Test fun accountKeyUnwrapAuthenticatesTheEnvelope() {
        val envelope = keyEnvelope()
        val login = MyTeamSpeakProtocol.loginPassword(email, password.toByteArray())
        assertArrayEquals(accountKey, MyTeamSpeakProtocol.accountKey(email, password.toByteArray(), login, "account", envelope))
        envelope[1] = (envelope[1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.accountKey(email, password.toByteArray(), login, "account", envelope) }
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.accountKey(email, "wrong".toByteArray(), login, "account", keyEnvelope()) }
    }

    @Test fun encryptedItemMatchesIndependentVectorsIncludingCounterCarry() {
        val plaintext = "Identity fixture with more than one block".toByteArray()
        val vectors = listOf(
            "000102030405060708090a0b0c0d0e0f130a61397c9205efd0483c4576b6d4f72ab8c281385682398774b927f8c49c30e154559d7a6124b6903b8166f8d3144ba15abb3c9ab0780fa0e604b61f5778abeea1dedff8fcf413e1e6500ca242c39f14280c02a81c9d3a88ceae566164fd170c9ebe2f018dc1370d",
            "fffffffffffffffffffffffffffffffe2a81d16cc1773ca4da8b90a6edb95521c9ee8d6924871db521e2310f35ee39cefb4397df27c0b131783b8166f8d3144ba15abb3c9ab0780fa0e604b61f5778abeea1dedff8fcf413e1e6500ca242c39f14280c02a81c9d3a88ceae566164fd170c9ebe2f018dc1370d",
        )
        for (vector in vectors) {
            val blob = hex(vector)
            assertArrayEquals(blob, MyTeamSpeakProtocol.encryptItem(accountKey, plaintext, blob.copyOfRange(0, 16)))
            assertArrayEquals(plaintext, MyTeamSpeakProtocol.decryptItem(accountKey, blob))
            blob[20] = (blob[20].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { MyTeamSpeakProtocol.decryptItem(accountKey, blob) }
        }
    }

    @Test fun rpcFramingAndDefensiveProtobufParsing() {
        assertArrayEquals(byteArrayOf(5) + "login".toByteArray() + byteArrayOf(8, 1), MyTeamSpeakProtocol.frame("login", byteArrayOf(8, 1)))
        val parsed = MyTeamSpeakProtocol.parse(Writer().number(1, 300).text(2, "example").build())
        assertEquals(300L, parsed.number(1)); assertEquals("example", parsed.text(2))
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.parse(byteArrayOf(0)) }
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.parse(byteArrayOf(10, 127)) }
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.parse(ByteArray(12) { 255.toByte() }) }
        assertThrows(Exception::class.java) { MyTeamSpeakProtocol.parse(Writer().text(1, "a").text(1, "b").build()).text(1) }
    }

    private inner class Server {
        val items = mutableMapOf("existing" to "existing-identity")
        var uploads = 0
        var collision = false
        var corrupt = false
        var otpRequired = false
        var failUpload = false
        var expired = false
        fun request(endpoint: String, method: String, payload: ByteArray): ByteArray {
            val request = MyTeamSpeakProtocol.parse(payload)
            if (method == "login") {
                assertEquals("authentication", endpoint)
                assertEquals(email, request.text(1))
                assertNotEquals(password, request.text(2))
                assertEquals(1L, request.number(5))
                if (otpRequired) return Writer().number(5, 208).build()
                return Writer().number(5, 200).text(2, "synthetic-session").text(4, "synthetic-account").bytes(1, keyEnvelope()).build()
            }
            assertEquals("synchronization", endpoint)
            assertEquals("synthetic-session", request.text(1))
            if (expired) return Writer().number(1, 102).build()
            assertEquals(1, request.messages(2).size)
            assertEquals(1L, request.messages(2).single().number(1))
            if (method == "synchronizeItems") {
                uploads++
                if (failUpload) throw java.io.IOException("Simulated timeout")
                if (collision) return Writer().number(1, 303).build()
                assertEquals("global-version", request.text(3))
                for (detail in request.messages(2).single().messages(3)) {
                    assertEquals(0L, detail.number(4))
                    assertFalse(items.containsKey(detail.text(1)))
                    val plain = MyTeamSpeakProtocol.decryptItem(accountKey, detail.bytes(3))
                    val item = MyTeamSpeakProtocol.parse(plain)
                    assertEquals(1L, item.number(6))
                    assertEquals(0L, item.number(4))
                    assertEquals(detail.text(1), item.text(2))
                    assertEquals(detail.text(2), item.text(3))
                    items[detail.text(1)] = MyTeamSpeakProtocol.parse(item.bytes(17)).text(1)
                }
                return Writer().number(1, 300).build()
            }
            assertEquals("requestServerItems", method)
            val group = Writer().number(1, 1).text(2, "class-version")
            for ((id, data) in items) {
                val plain = Writer().text(2, id).number(6, 1).bytes(17, Writer().text(1, data).text(2, "Fixture").text(3, "Nickname").build()).build()
                val blob = MyTeamSpeakProtocol.encryptItem(accountKey, plain)
                if (corrupt) blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
                group.bytes(3, Writer().text(1, id).text(2, "item-version").bytes(3, blob).build())
            }
            return Writer().number(1, 301).text(3, "global-version").bytes(2, group.build()).build()
        }
        fun client() = MyTeamSpeakCloud(::request) { it }
    }

    @Test fun loginAndDownloadNeverUpload() = runBlocking {
        val server = Server()
        server.client().use { cloud ->
            assertEquals(email, cloud.login(email, password, ""))
            assertEquals("existing-identity", cloud.listIdentities().single().data)
            assertEquals(0, server.uploads)
        }
    }

    @Test fun uploadsOnlySelectionAndSkipsDuplicates() = runBlocking {
        val server = Server()
        server.client().use { cloud ->
            cloud.login(email, password, "")
            val selected = listOf(Identity(name = "Selected", data = "new-identity"), Identity(name = "Duplicate", data = "existing-identity"))
            val result = cloud.upload(selected)
            assertEquals(1, result.added); assertEquals(1, result.skipped)
            assertEquals(setOf("existing-identity", "new-identity"), server.items.values.toSet())
            assertEquals(0, cloud.upload(selected).added)
            assertEquals(1, server.uploads)
        }
    }

    @Test fun collisionAndTimeoutAreNotAutomaticallyRetried() = runBlocking {
        val server = Server()
        server.client().use { cloud ->
            cloud.login(email, password, "")
            server.collision = true
            try { cloud.upload(listOf(Identity(name = "New", data = "new"))); fail("Expected collision") } catch (_: MyTeamSpeakCloud.CloudException) { }
            assertEquals(1, server.uploads)
            assertEquals(listOf("existing-identity"), server.items.values.toList())
            server.collision = false; server.failUpload = true
            try { cloud.upload(listOf(Identity(name = "New", data = "new"))); fail("Expected timeout") } catch (_: MyTeamSpeakCloud.CloudException) { }
            assertEquals(2, server.uploads)
        }
    }

    @Test fun corruptCloudDataPreventsLoginAndUpload() = runBlocking {
        val server = Server().apply { corrupt = true }
        server.client().use { cloud ->
            try { cloud.login(email, password, ""); fail("Expected integrity failure") } catch (_: MyTeamSpeakCloud.CloudException) { }
            try { cloud.upload(listOf(Identity(name = "New", data = "new"))); fail("Expected logged out") } catch (_: MyTeamSpeakCloud.CloudException) { }
            assertEquals(0, server.uploads)
        }
    }

    @Test fun otpFailureAndLogoutDoNotLeaveAUsableSession() = runBlocking {
        val server = Server().apply { otpRequired = true }
        server.client().use { cloud ->
            try { cloud.login(email, password, ""); fail("Expected OTP required") } catch (_: MyTeamSpeakCloud.CloudException) { }
            server.otpRequired = false
            cloud.login(email, password, "123456")
            cloud.close()
            try { cloud.listIdentities(); fail("Expected logged out") } catch (_: MyTeamSpeakCloud.CloudException) { }
            assertEquals(0, server.uploads)
        }
    }

    @Test fun expiredSessionRequiresAnotherLogin() = runBlocking {
        val server = Server()
        server.client().use { cloud ->
            cloud.login(email, password, "")
            server.expired = true
            try { cloud.listIdentities(); fail("Expected expiration") } catch (_: MyTeamSpeakCloud.CloudException) { }
            server.expired = false
            try { cloud.listIdentities(); fail("Expected discarded session") } catch (_: MyTeamSpeakCloud.CloudException) { }
            assertEquals(0, server.uploads)
        }
    }
}
