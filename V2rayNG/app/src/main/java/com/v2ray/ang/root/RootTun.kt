package com.v2ray.ang.root

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns a root-created TUN fd for the xray_tun ROOT engine.
 *
 * The fd must be opened (or received via SCM_RIGHTS) inside the daemon process that later
 * calls CoreServiceManager.startCoreLoop / StartLoop(config, tunFd). Go does not close the
 * Android TUN fd on stop; Kotlin owns close order: rules down -> stop core -> [close].
 */
object RootTun {
    data class Handle(
        val fd: Int,
        val ifname: String,
        val pfd: ParcelFileDescriptor,
    )

    @Volatile
    private var current: Handle? = null
    private val heldFd = AtomicInteger(-1)

    @Synchronized
    fun open(context: Context, ifname: String = AppConfig.ROOT_TUN_NAME): Handle {
        close()
        RootTunNative.ensureLoaded()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val helper = File(nativeDir, "libroot_tun_helper.so")
        val helperAlt = File(nativeDir, "root_tun_helper")
        val helperPath = when {
            helper.exists() -> helper.absolutePath
            helperAlt.exists() -> helperAlt.absolutePath
            else -> throw IllegalStateException("root_tun_helper missing under $nativeDir")
        }

        // Extracted helper .so sometimes lacks +x on OEM packagers.
        runCatching {
            File(helperPath).setExecutable(true, false)
            RootShell.exec("chmod 755 '$helperPath'", timeoutSeconds = 5)
        }

        val fd = RootTunNative.nativeOpenTun(ifname, helperPath, "su")
        if (fd < 0) {
            throw IllegalStateException("nativeOpenTun failed: $fd")
        }
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val handle = Handle(fd = pfd.fd, ifname = ifname, pfd = pfd)
        current = handle
        heldFd.set(handle.fd)
        LogUtil.i(AppConfig.TAG, "RootTun: opened ifname=$ifname fd=${handle.fd}")
        return handle
    }

    /** Current process-local TUN fd, or -1 if none. */
    fun currentFd(): Int = heldFd.get()

    fun current(): Handle? = current

    fun isOpen(): Boolean = heldFd.get() >= 0

    @Synchronized
    fun close() {
        val handle = current
        current = null
        heldFd.set(-1)
        if (handle == null) return
        try {
            handle.pfd.close()
            LogUtil.i(AppConfig.TAG, "RootTun: closed fd=${handle.fd}")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "RootTun: close fd=${handle.fd} failed: ${e.message}")
        }
    }
}
