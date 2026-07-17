package com.v2ray.ang.xposed.hooks.hidevpn

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.HookErrorStore
import com.v2ray.ang.xposed.PrivilegeSettingsStore
import com.v2ray.ang.xposed.hooks.SafeMethodHook
import com.v2ray.ang.xposed.hooks.XHook
import java.io.FileDescriptor
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HookNetworkInterfaceGetName(private val classLoader: ClassLoader) : XHook {
    private companion object {
        private const val SOURCE = "HookNetworkInterfaceGetName"
        private const val MAX_NAME_LEN = 15
        private const val MAX_SUFFIX = 63
        private const val NLMSG_HEADER_LEN = 16
        private const val IFINFO_MSG_LEN = 16
        private const val NLA_HEADER_LEN = 4
        private const val RTM_NEWLINK = 16
        private const val IFLA_IFNAME = 3
        private const val NLM_F_REQUEST = 0x1
        private const val NLM_F_ACK = 0x4
        private const val NLMSG_ERROR = 2
        private const val IFF_UP = 0x1
    }

    private val netlinkSocketAddressClass by lazy { Class.forName("android.system.NetlinkSocketAddress") }
    private val netlinkSocketAddressCtor by lazy {
        netlinkSocketAddressClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    private val seq = AtomicInteger(1)
    private val jniHooked = AtomicBoolean(false)

    override fun injectHook() {
        // IMPORTANT:
        // This module loads in system_server. NetworkInterface.getName/getDisplayName are
        // local methods; there is no reliable per-app Binder UID here. Masking them
        // rewrites what system_server itself sees when managing VpnService (stop/start,
        // route cleanup) and causes stuck VPN OFF + broken connectivity.
        // Target-app name scrubbing must stay in ConnectivityService/LinkProperties hooks
        // (per calling uid), not global NetworkInterface presentation rewrites.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                hookJniGetNameApi33Plus()
            } else {
                hookJniGetNameLegacy()
            }
            jniHooked.set(true)
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "jniGetName hook failed, will retry via loadClass: ${e.message}", e)
            hookClassLoaderForVpn()
        }
        // Do NOT install public getName/getDisplayName mask in system_server.
        HookErrorStore.i(SOURCE, "NetworkInterface public name mask disabled (unsafe in system_server)")
    }

    private fun hookJniGetNameApi33Plus() {
        val vpnClass = findVpnClass()
        val depsClass = findClassCandidates(
            listOf("${vpnClass.name}\$Dependencies"),
            listOf(vpnClass.classLoader, classLoader, classLoader.parent, ClassLoader.getSystemClassLoader()),
        ) ?: throw ClassNotFoundException("${vpnClass.name}\$Dependencies")
        XposedApi.findAndHookMethod(
            depsClass,
            "jniGetName",
            vpnClass,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    processJniGetNameResult(param)
                }
            },
        )
        HookErrorStore.i(SOURCE, "Hooked ${depsClass.name}.jniGetName (API 33+)")
    }

    private fun hookJniGetNameLegacy() {
        val cls = findVpnClass()
        XposedApi.findAndHookMethod(
            cls,
            "jniGetName",
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    processJniGetNameResult(param)
                }
            },
        )
        HookErrorStore.i(SOURCE, "Hooked ${cls.name}.jniGetName (legacy)")
    }

    private fun hookClassLoaderForVpn() {
        try {
            XposedApi.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : SafeMethodHook("$SOURCE.loadClass") {
                    override fun afterHook(param: SafeMethodHook.HookParam) {
                        if (jniHooked.get()) return
                        val name = param.args[0] as? String ?: return
                        if (!name.endsWith(".connectivity.Vpn") && name != "com.android.server.connectivity.Vpn") {
                            return
                        }
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                hookJniGetNameApi33Plus()
                            } else {
                                hookJniGetNameLegacy()
                            }
                            jniHooked.set(true)
                            HookErrorStore.i(SOURCE, "jniGetName hooked after loadClass($name)")
                        } catch (e: Throwable) {
                            HookErrorStore.w(SOURCE, "retry jniGetName after loadClass failed: ${e.message}", e)
                        }
                    }
                },
            )
            HookErrorStore.i(SOURCE, "Hooked ClassLoader.loadClass for Vpn jniGetName retry")
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "ClassLoader fallback for Vpn failed: ${e.message}", e)
        }
    }

    private fun processJniGetNameResult(param: SafeMethodHook.HookParam) {
        // SFA-style create-time rename only:
        // Vpn.jniGetName runs when system_server first names the iface. Netlink-renaming
        // here keeps a single truth for system_server (no getName mask, no eager live rename).
        // Do NOT reintroduce public NetworkInterface.getName/getDisplayName masking.
        val result = param.result
        if (result !is String) {
            if (result != null) {
                HookErrorStore.e(SOURCE, "jniGetName returned unexpected type: ${result.javaClass.name}")
            }
            return
        }
        if (!PrivilegeSettingsStore.shouldRenameInterface()) return
        if (!isTunInterface(result)) return
        val prefix = PrivilegeSettingsStore.interfacePrefix()
        val renamed = renameInterface(result, prefix) ?: return
        param.result = renamed
    }

    private fun findVpnClass(): Class<*> {
        val names = listOf(
            "com.android.server.connectivity.Vpn",
            "android.net.connectivity.com.android.server.connectivity.Vpn",
        )
        val loaders = listOf(
            classLoader,
            classLoader.parent,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
            ClassLoader.getSystemClassLoader()?.parent,
        )
        return findClassCandidates(names, loaders)
            ?: throw ClassNotFoundException(names.joinToString())
    }

    private fun findClassCandidates(names: List<String>, loaders: List<ClassLoader?>): Class<*>? {
        for (name in names) {
            for (loader in loaders) {
                try {
                    val cls = if (loader != null) {
                        Class.forName(name, false, loader)
                    } else {
                        Class.forName(name)
                    }
                    HookErrorStore.i(SOURCE, "resolved class $name via ${loader?.javaClass?.name ?: "boot"}")
                    return cls
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun isTunInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("tap")
    }

    private fun renameInterface(oldName: String, prefix: String): String? {
        val oldIndex = getInterfaceIndex(oldName)
        if (oldIndex <= 0) {
            HookErrorStore.e(SOURCE, "rename interface: old name not found (old=$oldName)")
            return null
        }
        val newName = findAvailableName(prefix)
        if (newName == null) {
            HookErrorStore.e(SOURCE, "rename interface: no available name (prefix=$prefix)")
            return null
        }
        if (newName == oldName) {
            return oldName
        }
        if (!renameWithNetlink(oldIndex, newName)) {
            HookErrorStore.e(SOURCE, "rename failed: $oldName -> $newName")
            return null
        }
        val newIndex = getInterfaceIndex(newName)
        if (newIndex <= 0) {
            HookErrorStore.e(
                SOURCE,
                "rename interface: new name not found (old=$oldName index=$oldIndex)",
            )
            return null
        }
        HookErrorStore.i(SOURCE, "rename interface: $oldName -> $newName")
        return newName
    }

    private fun getInterfaceIndex(name: String): Int = Os.if_nametoindex(name)

    private fun findAvailableName(prefix: String): String? {
        val base = prefix.trim()
        if (base.isEmpty()) {
            return null
        }
        for (i in 0..MAX_SUFFIX) {
            val candidate = buildInterfaceName(base, i) ?: return null
            if (getInterfaceIndex(candidate) == 0) {
                return candidate
            }
        }
        return null
    }

    private fun buildInterfaceName(prefix: String, suffix: Int): String? {
        val suffixText = suffix.toString()
        val maxPrefixLen = MAX_NAME_LEN - suffixText.length
        if (maxPrefixLen <= 0) {
            return null
        }
        val trimmed = if (prefix.length > maxPrefixLen) {
            prefix.substring(0, maxPrefixLen)
        } else {
            prefix
        }
        return trimmed + suffixText
    }

    private fun renameWithNetlink(index: Int, newName: String): Boolean {
        val fd = openNetlinkSocket()
        try {
            val renameResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, newName, 0, 0, seq.getAndIncrement()),
                OsConstants.EBUSY,
            ) ?: return false
            if (renameResult == 0) {
                return true
            }
            if (renameResult != OsConstants.EBUSY) {
                HookErrorStore.e(SOURCE, "rename interface: netlink ack errno=$renameResult")
                return false
            }
            val downResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, null, 0, IFF_UP, seq.getAndIncrement()),
            ) ?: return false
            if (downResult != 0) {
                HookErrorStore.e(SOURCE, "rename interface: set down failed errno=$downResult")
                return false
            }
            val retryResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, newName, 0, 0, seq.getAndIncrement()),
            ) ?: return false
            if (retryResult != 0) {
                HookErrorStore.e(SOURCE, "rename interface: retry failed errno=$retryResult")
                return false
            }
            val upResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, null, IFF_UP, IFF_UP, seq.getAndIncrement()),
            )
            if (upResult != null && upResult != 0) {
                HookErrorStore.w(SOURCE, "rename interface: set up failed errno=$upResult")
            }
            return true
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "rename interface: netlink exception", e)
            return false
        } finally {
            try {
                Os.close(fd)
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "close netlink socket failed", e)
            }
        }
    }

    private fun openNetlinkSocket(): FileDescriptor {
        val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, OsConstants.NETLINK_ROUTE)
        Os.setsockoptTimeval(
            fd,
            OsConstants.SOL_SOCKET,
            OsConstants.SO_RCVTIMEO,
            StructTimeval.fromMillis(200),
        )
        val address = buildNetlinkAddress()
        Os.connect(fd, address)
        return fd
    }

    private fun buildNetlinkAddress(): SocketAddress = netlinkSocketAddressCtor.newInstance(0, 0) as SocketAddress

    private fun buildLinkMessage(index: Int, ifName: String?, flags: Int, change: Int, seq: Int): ByteArray {
        val nameBytes = ifName?.let { (it + "\u0000").toByteArray(Charsets.US_ASCII) }
        val attrLen = if (nameBytes != null) NLA_HEADER_LEN + nameBytes.size else 0
        val attrAligned = align(attrLen)
        val totalLength = NLMSG_HEADER_LEN + IFINFO_MSG_LEN + attrAligned
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.nativeOrder())
        buffer.putInt(totalLength)
        buffer.putShort(RTM_NEWLINK.toShort())
        buffer.putShort((NLM_F_REQUEST or NLM_F_ACK).toShort())
        buffer.putInt(seq)
        buffer.putInt(Os.getpid())
        buffer.put(OsConstants.AF_UNSPEC.toByte())
        buffer.put(0.toByte())
        buffer.putShort(0)
        buffer.putInt(index)
        buffer.putInt(flags)
        buffer.putInt(change)
        if (nameBytes != null) {
            buffer.putShort(attrLen.toShort())
            buffer.putShort(IFLA_IFNAME.toShort())
            buffer.put(nameBytes)
            val pad = attrAligned - attrLen
            repeat(pad) {
                buffer.put(0.toByte())
            }
        }
        return buffer.array()
    }

    private fun align(length: Int): Int = (length + 3) and -4

    private fun sendNetlinkMessage(fd: FileDescriptor, message: ByteArray, suppressErrno: Int? = null): Int? {
        Os.write(fd, message, 0, message.size)
        val ack = readNetlinkAck(fd)
        if (ack == null) {
            HookErrorStore.e(SOURCE, "rename interface: netlink ack missing")
            return null
        }
        if (ack.errno != 0 && ack.errno != suppressErrno) {
            HookErrorStore.e(
                SOURCE,
                "rename interface: netlink ack errno=${ack.errno} seq=${ack.seq} pid=${ack.pid}",
            )
        }
        return ack.errno
    }

    private data class NetlinkAck(val errno: Int, val seq: Int, val pid: Int)

    private fun readNetlinkAck(fd: FileDescriptor): NetlinkAck? {
        val buffer = ByteArray(4096)
        val length = Os.read(fd, buffer, 0, buffer.size)
        if (length <= 0 || length < NLMSG_HEADER_LEN) {
            return null
        }
        val byteBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.nativeOrder())
        val msgLen = byteBuffer.int
        val msgType = byteBuffer.short.toInt() and 0xFFFF
        byteBuffer.short
        val msgSeq = byteBuffer.int
        val msgPid = byteBuffer.int
        if (msgLen < NLMSG_HEADER_LEN || msgLen > length) {
            return null
        }
        if (msgType != NLMSG_ERROR) {
            return NetlinkAck(0, msgSeq, msgPid)
        }
        if (byteBuffer.remaining() < 4) {
            return null
        }
        val error = byteBuffer.int
        val errno = if (error == 0) 0 else -error
        return NetlinkAck(errno, msgSeq, msgPid)
    }
}