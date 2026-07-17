package com.v2ray.ang.xposed

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.NetworkInterface
import java.util.Collections

/**
 * SFA-aligned VPN detection probe for the current process.
 *
 * This only reports what *this package* sees via framework / native APIs.
 * Per-app hidevpn effect on other packages must still be verified on those targets.
 */
data class DetectionResult(
    val frameworkDetected: List<String>,
    val nativeDetected: Boolean,
    val frameworkInterfaces: List<String>,
    val nativeInterfaces: List<String>,
    val httpProxy: String?,
)

object VpnDetectionTest {

    fun runDetection(context: Context): DetectionResult {
        val frameworkDetected = LinkedHashSet<String>()
        val frameworkInterfaces = LinkedHashSet<String>()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DetectionResult(emptyList(), false, emptyList(), emptyList(), null)

        @Suppress("DEPRECATION")
        val activeInfo = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        if (activeInfo?.type == ConnectivityManager.TYPE_VPN) {
            frameworkDetected += "ActiveNetworkInfo"
        }

        @Suppress("DEPRECATION")
        val vpnInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN)
        if (vpnInfo != null && vpnInfo.isConnected) {
            frameworkDetected += "NetworkInfo"
        }

        val vpnNetwork = runCatching {
            val method = cm.javaClass.getMethod(
                "getNetworkForType",
                Int::class.javaPrimitiveType,
            )
            @Suppress("DEPRECATION")
            method.invoke(cm, ConnectivityManager.TYPE_VPN) as? Network
        }.getOrNull()
        if (vpnNetwork != null) {
            frameworkDetected += "NetworkForType"
        }

        val networks = cm.allNetworks ?: emptyArray()
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val hasVpnTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            // Missing NOT_VPN alone is noisy on some ROMs; only count with TRANSPORT_VPN.
            if (hasVpnTransport) {
                frameworkDetected += "NetworkCapabilities"
            } else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                // Keep as weak signal for debugging, but do not mark hard VPN detection alone.
                frameworkDetected += "MissingNotVpn"
            }
            val lp = cm.getLinkProperties(network)
            if (isVpnInterface(lp?.interfaceName)) {
                lp?.interfaceName?.let(frameworkInterfaces::add)
                frameworkDetected += "LinkProperties"
            }
        }

        val activeLinkProperties = readActiveLinkProperties(cm)
        if (isVpnInterface(activeLinkProperties?.interfaceName)) {
            activeLinkProperties?.interfaceName?.let(frameworkInterfaces::add)
            frameworkDetected += "LinkProperties"
        }

        val nativeInterfaces = checkNetworkInterfaces()
        val httpProxy = readHttpProxy(cm)
        return DetectionResult(
            frameworkDetected = frameworkDetected.toList(),
            nativeDetected = nativeInterfaces.isNotEmpty(),
            frameworkInterfaces = frameworkInterfaces.toList(),
            nativeInterfaces = nativeInterfaces,
            httpProxy = httpProxy,
        )
    }

    private fun checkNetworkInterfaces(): List<String> {
        val list = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Throwable) {
            return emptyList()
        }
        val matches = ArrayList<String>()
        for (iface in list) {
            val name = iface.name ?: continue
            val isUp = runCatching { iface.isUp }.getOrElse { false }
            if (!isUp) continue
            if (isVpnInterface(name)) {
                matches.add(name)
            }
        }
        return matches
    }

    private fun isVpnInterface(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("tap")
    }

    private fun readHttpProxy(cm: ConnectivityManager): String? {
        val defaultProxy = try {
            val method = cm.javaClass.getMethod("getDefaultProxy")
            method.invoke(cm) as? android.net.ProxyInfo
        } catch (_: Throwable) {
            null
        }
        val activeLinkProperties = readActiveLinkProperties(cm)
        val networks = cm.allNetworks ?: emptyArray()
        val proxies = buildList {
            add(formatProxyInfo(defaultProxy))
            add(formatProxyInfo(readProxyFromLinkProperties(activeLinkProperties)))
            for (network in networks) {
                add(formatProxyInfo(readProxyFromLinkProperties(cm.getLinkProperties(network))))
            }
        }
        return proxies.firstOrNull { !it.isNullOrEmpty() }
    }

    private fun readActiveLinkProperties(cm: ConnectivityManager): LinkProperties? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()
    }

    private fun readProxyFromLinkProperties(lp: LinkProperties?): android.net.ProxyInfo? {
        if (lp == null) return null
        return try {
            val method = lp.javaClass.getMethod("getHttpProxy")
            method.invoke(lp) as? android.net.ProxyInfo
        } catch (_: Throwable) {
            try {
                val field = lp.javaClass.getDeclaredField("mHttpProxy")
                field.isAccessible = true
                field.get(lp) as? android.net.ProxyInfo
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun formatProxyInfo(proxyInfo: android.net.ProxyInfo?): String? {
        if (proxyInfo == null) return null
        return try {
            val host = proxyInfo.host
            val port = proxyInfo.port
            if (!host.isNullOrEmpty() && port > 0) {
                return "$host:$port"
            }
            val pac = proxyInfo.pacFileUrl?.toString()
            if (!pac.isNullOrEmpty()) pac else null
        } catch (_: Throwable) {
            null
        }
    }
}
