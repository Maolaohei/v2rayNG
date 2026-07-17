package com.v2ray.ang.root

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot post-start readiness check for ROOT xray_tun.
 *
 * Design goals (avoid fragmented UX):
 * - Single probe after a successful dataplane start, not a second watchdog
 * - Uses local SOCKS (app UID is intentionally bypassed by policy routing)
 * - Soft failure only logs; never tears down a live session by itself
 *
 * Scope note: this proves core local SOCKS connectivity (CONNECT through Xray),
 * not full app-UID MARK -> tun system capture. Treat soft-fail as a signal log only.
 */
object RootConnectivitySmoke {
    data class Result(
        val ok: Boolean,
        val detail: String,
        val elapsedMs: Long,
    )

    private val lastProbeAtMs = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 15_000L

    fun maybeProbeAfterStart(force: Boolean = false): Result? {
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastProbeAtMs.get()
            if (last > 0L && now - last < MIN_INTERVAL_MS) return null
        }
        lastProbeAtMs.set(now)
        val result = probe()
        if (result.ok) {
            LogUtil.i(AppConfig.TAG, "RootSmoke: ok ${result.detail} (${result.elapsedMs}ms)")
        } else {
            LogUtil.w(AppConfig.TAG, "RootSmoke: soft-fail ${result.detail} (${result.elapsedMs}ms)")
        }
        return result
    }

    fun probe(timeoutMs: Int = 2500): Result {
        val started = System.currentTimeMillis()
        val port = SettingsManager.getSocksPort()
        if (port <= 0) {
            return Result(false, "socks-port-invalid", System.currentTimeMillis() - started)
        }

        // 1) local SOCKS accept
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(AppConfig.LOOPBACK, port), timeoutMs.coerceAtMost(800))
            }
        } catch (e: Exception) {
            return Result(false, "socks-closed:${e.javaClass.simpleName}", System.currentTimeMillis() - started)
        }

        // 2) SOCKS5 CONNECT through core (does not rely on app-UID iptables capture)
        val target = resolveProbeTarget()
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(AppConfig.LOOPBACK, port), timeoutMs.coerceAtMost(800))
                val out = s.getOutputStream()
                val input = s.getInputStream()
                // greeting: VER=5, NMETHODS=1, METHOD=0 (no-auth)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val greet = readExact(input, 2)
                if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                    return Result(
                        false,
                        "socks-auth-reject:${greet[0].toInt() and 0xff}/${greet[1].toInt() and 0xff}",
                        System.currentTimeMillis() - started
                    )
                }
                val req = buildConnectRequest(target.host, target.port)
                out.write(req)
                out.flush()
                val head = readExact(input, 4)
                // VER REP RSV ATYP
                if (head[0] != 0x05.toByte()) {
                    return Result(false, "socks-bad-ver", System.currentTimeMillis() - started)
                }
                val rep = head[1].toInt() and 0xff
                if (rep != 0x00) {
                    return Result(false, "socks-connect-rep=$rep host=${target.host}:${target.port}", System.currentTimeMillis() - started)
                }
                // consume bind addr
                when (head[3].toInt() and 0xff) {
                    0x01 -> readExact(input, 4 + 2)
                    0x03 -> {
                        val n = input.read()
                        if (n > 0) readExact(input, n + 2) else readExact(input, 2)
                    }
                    0x04 -> readExact(input, 16 + 2)
                    else -> { /* best effort */ }
                }
                Result(true, "socks-connect ${target.host}:${target.port}", System.currentTimeMillis() - started)
            }
        } catch (e: Exception) {
            Result(false, "socks-connect-ex:${e.javaClass.simpleName}:${e.message}", System.currentTimeMillis() - started)
        }
    }

    private data class Target(val host: String, val port: Int)

    private fun resolveProbeTarget(): Target {
        // Prefer a stable IP:PORT so DNS is not required for the smoke itself.
        // 1.1.1.1:443 is widely reachable; fall back to delay-test host if needed later.
        return Target("1.1.1.1", 443)
    }

    private fun buildConnectRequest(host: String, port: Int): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        // VER CMD RSV ATYP DOMAIN LEN HOST PORT
        val packet = ByteArray(4 + 1 + hostBytes.size + 2)
        packet[0] = 0x05
        packet[1] = 0x01 // CONNECT
        packet[2] = 0x00
        packet[3] = 0x03 // DOMAIN
        packet[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, packet, 5, hostBytes.size)
        val portOff = 5 + hostBytes.size
        packet[portOff] = ((port ushr 8) and 0xff).toByte()
        packet[portOff + 1] = (port and 0xff).toByte()
        // For pure IPv4 host use ATYP=1 for slightly better compatibility.
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            val parts = host.split('.').map { it.toInt().coerceIn(0, 255) }
            val ipPacket = ByteArray(4 + 4 + 2)
            ipPacket[0] = 0x05
            ipPacket[1] = 0x01
            ipPacket[2] = 0x00
            ipPacket[3] = 0x01
            ipPacket[4] = parts[0].toByte()
            ipPacket[5] = parts[1].toByte()
            ipPacket[6] = parts[2].toByte()
            ipPacket[7] = parts[3].toByte()
            ipPacket[8] = ((port ushr 8) and 0xff).toByte()
            ipPacket[9] = (port and 0xff).toByte()
            return ipPacket
        }
        return packet
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("socks short read $off/$n")
            off += r
        }
        return buf
    }
}
