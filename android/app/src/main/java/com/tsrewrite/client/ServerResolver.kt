package com.tsrewrite.client

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves a TS3 address with the system DNS (the Rust resolver cannot read Android's DNS config):
 * IP literal -> `_ts3._udp` SRV -> TSDNS (`_tsdns._tcp` SRV, then domain:41144) -> A/AAAA with port 9987.
 */
object ServerResolver {
    private const val TAG = "ServerResolver"
    private const val DEFAULT_PORT = 9987
    private const val TYPE_SRV = 33
    private val executor = Executors.newCachedThreadPool()

    /** Returns "ip:port" (IPv6 in brackets), or the input unchanged when it cannot be resolved. */
    fun resolve(address: String): String {
        val (host, port) = split(address.trim())
        if (isIpLiteral(host)) return address.trim()
        try {
            if (port == null) {
                srv("_ts3._udp.$host")?.let { (target, p) -> ip(target)?.let { return fmt(it, p) } }
                tsdns(host)?.let { return it }
            }
            ip(host)?.let { return fmt(it, port ?: DEFAULT_PORT) }
        } catch (e: Exception) {
            Log.w(TAG, "resolve $address", e)
        }
        return address.trim()
    }

    private fun split(a: String): Pair<String, Int?> {
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            return a.substring(1, end) to a.substring(end + 1).removePrefix(":").toIntOrNull()
        }
        if (a.count { it == ':' } == 1) return a.substringBefore(':') to a.substringAfter(':').toIntOrNull()
        return a to null
    }

    private fun isIpLiteral(h: String) = h.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) || h.contains(':')

    private fun fmt(a: InetAddress, port: Int) = if (a is Inet6Address) "[${a.hostAddress}]:$port" else "${a.hostAddress}:$port"

    private fun ip(host: String): InetAddress? =
        runCatching { InetAddress.getAllByName(host).sortedBy { if (it is Inet6Address) 1 else 0 }.firstOrNull() }.getOrNull()

    /** TSDNS: ask the TSDNS server for `host`; it answers "ip:port" or "404". */
    private fun tsdns(host: String): String? {
        val servers = mutableListOf<Pair<String, Int>>()
        srv("_tsdns._tcp.$host")?.let { servers += it }
        val parts = host.split('.')
        if (parts.size >= 2) servers += parts.takeLast(2).joinToString(".") to 41144
        for ((server, port) in servers) {
            val answer = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(server, port), 2000)
                    s.soTimeout = 2000
                    s.getOutputStream().write(host.toByteArray())
                    s.getInputStream().readBytes().decodeToString().trim()
                }
            }.getOrNull() ?: continue
            if (answer.isNotEmpty() && answer != "404" && !answer.startsWith("No match")) {
                val (h, p) = split(answer)
                ip(h)?.let { return fmt(it, p ?: DEFAULT_PORT) }
            }
        }
        return null
    }

    /** First SRV record (lowest priority) as target host and port. */
    @android.annotation.SuppressLint("WrongConstant") // rawQuery takes any record type; lint only knows A/AAAA/HTTPS
    private fun srv(name: String): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < 29) return null
        val future = CompletableFuture<ByteArray?>()
        DnsResolver.getInstance().rawQuery(null, name, DnsResolver.CLASS_IN, TYPE_SRV, DnsResolver.FLAG_EMPTY,
            executor, CancellationSignal(), object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) { future.complete(if (rcode == 0) answer else null) }
                override fun onError(error: DnsResolver.DnsException) { future.complete(null) }
            })
        val msg = runCatching { future.get(5, TimeUnit.SECONDS) }.getOrNull() ?: return null
        return parseSrv(msg).minByOrNull { it.first }?.let { it.second to it.third }
    }

    /** Parses SRV answers from a DNS response: (priority, target, port). */
    private fun parseSrv(msg: ByteArray): List<Triple<Int, String, Int>> {
        val b = ByteBuffer.wrap(msg)
        val qd = b.getShort(4).toInt() and 0xffff
        val an = b.getShort(6).toInt() and 0xffff
        var pos = 12
        repeat(qd) { pos = skipName(msg, pos) + 4 }
        val out = mutableListOf<Triple<Int, String, Int>>()
        repeat(an) {
            pos = skipName(msg, pos)
            val type = b.getShort(pos).toInt() and 0xffff
            val len = b.getShort(pos + 8).toInt() and 0xffff
            val data = pos + 10
            if (type == TYPE_SRV) {
                val prio = b.getShort(data).toInt() and 0xffff
                val port = b.getShort(data + 4).toInt() and 0xffff
                out += Triple(prio, readName(msg, data + 6), port)
            }
            pos = data + len
        }
        return out
    }

    private fun skipName(m: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val l = m[p].toInt() and 0xff
            if (l == 0) return p + 1
            if (l and 0xc0 == 0xc0) return p + 2
            p += l + 1
        }
    }

    private fun readName(m: ByteArray, start: Int): String {
        val labels = mutableListOf<String>()
        var p = start
        var jumps = 0
        while (jumps < 20) {
            val l = m[p].toInt() and 0xff
            when {
                l == 0 -> break
                l and 0xc0 == 0xc0 -> { p = ((l and 0x3f) shl 8) or (m[p + 1].toInt() and 0xff); jumps++ }
                else -> { labels += String(m, p + 1, l); p += l + 1 }
            }
        }
        return labels.joinToString(".")
    }
}
