package com.v2ray.ang.root

/**
 * JNI bridge for opening a root TUN fd in the current process.
 * Loaded only when ROOT xray_tun path is attempted.
 */
internal object RootTunNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("root_tun")
            loaded = true
        }
    }

    external fun nativeOpenTun(ifname: String, helperPath: String, suBinary: String): Int
    external fun nativeCloseTun(fd: Int)
    external fun nativeIsTunFd(fd: Int): Boolean
}
