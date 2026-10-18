package com.tsrewrite.client

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent wire/crypto implementation; no official client library is loaded. */
internal object MyTeamSpeakProtocol {
    const val MAX_MESSAGE = 16 * 1024 * 1024

    class Message(private val fields: Map<Int, List<Any>>) {
        fun number(field: Int): Long = single(field)?.let { it as? Long ?: error("Invalid cloud field") } ?: 0L
        fun bytes(field: Int): ByteArray = single(field)?.let { it as? ByteArray ?: error("Invalid cloud field") } ?: byteArrayOf()
        fun text(field: Int): String = bytes(field).decodeToString(throwOnInvalidSequence = true)
        fun messages(field: Int): List<Message> = fields[field].orEmpty().map {
            parse(it as? ByteArray ?: error("Invalid cloud field"))
        }
        private fun single(field: Int): Any? {
            val values = fields[field].orEmpty()
            require(values.size <= 1) { "Duplicate cloud field" }
            return values.firstOrNull()
        }
    }

    class Writer {
        private val out = ByteArrayOutputStream()
        private fun varint(value: Long) {
            require(value >= 0)
            var v = value
            while (v > 127) { out.write(((v and 127) or 128).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun number(field: Int, value: Long): Writer = apply { varint(field.toLong() shl 3); varint(value) }
        fun bytes(field: Int, value: ByteArray): Writer = apply {
            require(value.size <= MAX_MESSAGE)
            varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); out.write(value)
        }
        fun text(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = out.toByteArray().also { require(it.size <= MAX_MESSAGE) }
    }

    fun parse(data: ByteArray): Message {
        require(data.size <= MAX_MESSAGE) { "Cloud response too large" }
        var position = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(position < data.size) { "Truncated cloud response" }
                val b = data[position++].toInt() and 255
                require(shift < 63 || b <= 1) { "Invalid cloud integer" }
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return result
            }
            error("Invalid cloud integer")
        }
        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= data.size - position) { "Truncated cloud response" }
            return data.copyOfRange(position, position + count).also { position += count }
        }
        val fields = mutableMapOf<Int, MutableList<Any>>()
        var count = 0
        while (position < data.size) {
            require(++count <= 50000) { "Too many cloud fields" }
            val tag = varint()
            require(tag > 0 && tag ushr 3 in 1..536870911) { "Invalid cloud field" }
            val value: Any = when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> { take(8); continue }
                2 -> {
                    val size = varint()
                    require(size in 0..MAX_MESSAGE.toLong()) { "Invalid cloud field size" }
                    take(size.toInt())
                }
                5 -> { take(4); continue }
                else -> error("Unsupported cloud wire type")
            }
            fields.getOrPut((tag ushr 3).toInt()) { mutableListOf() }.add(value)
        }
        return Message(fields)
    }

    fun frame(method: String, payload: ByteArray): ByteArray {
        require(method.length in 1..255 && method.all { it.code in 33..126 })
        require(payload.size <= MAX_MESSAGE)
        return byteArrayOf(method.length.toByte()) + method.toByteArray(Charsets.US_ASCII) + payload
    }

    // Byte-based PBKDF2 avoids provider-dependent conversion of non-ASCII passwords.
    fun derive(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        require(password.isNotEmpty() && length in 1..64)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(9999) {
            val next = mac.doFinal(u)
            u.fill(0); u = next
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        u.fill(0)
        return result.copyOf(length).also { result.fill(0) }
    }

    fun loginPassword(email: String, password: ByteArray): String {
        val salt = normalizedEmail(email).toByteArray() + "ts3Login".toByteArray() + password
        val derived = try { derive(password, salt, 48) } finally { salt.fill(0) }
        return try { Base64.getEncoder().encodeToString(derived) } finally { derived.fill(0) }
    }

    fun normalizedEmail(email: String): String = email.trim().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

    fun accountKey(email: String, password: ByteArray, loginPassword: String, uuid: String, envelope: ByteArray): ByteArray {
        require(envelope.size == 61) { "Unsupported account key. Restore cloud access in the official client first." }
        val key = when (envelope[0].toInt()) {
            2 -> {
                val salt = normalizedEmail(email).toByteArray() + "ts3Encryption".toByteArray() + password
                try { derive(password, salt, 32) } finally { salt.fill(0) }
            }
            1 -> {
                val salt = sha512((uuid + "ts3PassPhrase" + loginPassword).toByteArray())
                val encoded = loginPassword.toByteArray()
                try { derive(encoded, salt, 32) } finally { salt.fill(0); encoded.fill(0) }
            }
            else -> error("Unsupported account encryption version")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.copyOfRange(17, 29)))
            cipher.doFinal(envelope.copyOfRange(29, 61) + envelope.copyOfRange(1, 17)).also { require(it.size == 32) }
        } finally { key.fill(0) }
    }

    fun encryptItem(key: ByteArray, plaintext: ByteArray, iv: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): ByteArray =
        iv + cryptItem(key, iv, plaintext) + sha512(plaintext)

    fun decryptItem(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size in 80..MAX_MESSAGE) { "Invalid encrypted identity" }
        val plaintext = cryptItem(key, blob.copyOfRange(0, 16), blob.copyOfRange(16, blob.size - 64))
        if (!MessageDigest.isEqual(sha512(plaintext), blob.copyOfRange(blob.size - 64, blob.size))) {
            plaintext.fill(0)
            error("Cloud identity integrity check failed")
        }
        return plaintext
    }

    private fun cryptItem(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && iv.size == 16 && data.size <= MAX_MESSAGE - 80)
        val counter = iv.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val result = ByteArray(data.size)
        fun adjust(indices: IntProgression, delta: Int) {
            for (i in indices) {
                val value = (counter[i].toInt() and 255) + delta
                counter[i] = value.toByte()
                if (value in 0..255) break
            }
        }
        for (offset in data.indices step 16) {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            val stream = cipher.doFinal(ByteArray(16))
            for (i in 0 until minOf(16, data.size - offset)) result[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            stream.fill(0)
            // Match the native counter recurrence, including carry boundaries: decBE(incLE(incBE(C))).
            adjust(15 downTo 0, 1); adjust(0..15, 1); adjust(15 downTo 0, -1)
        }
        counter.fill(0)
        return result
    }

    private fun sha512(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(value)
}
