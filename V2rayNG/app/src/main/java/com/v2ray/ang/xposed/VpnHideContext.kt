package com.v2ray.ang.xposed

import android.os.Binder
import android.os.Process

/**
 * Callback / parcel context for hidevpn.
 * During ConnectivityService callback dispatch, Binder calling uid is system_server.
 * Stash the real recipient uid so sanitize hooks still apply.
 */
object VpnHideContext {
    private val targetUid = ThreadLocal<Int?>()

    fun setTargetUid(uid: Int) {
        targetUid.set(uid)
    }

    fun peekTargetUid(): Int? = targetUid.get()

    fun consumeTargetUid(): Int? {
        val value = targetUid.get()
        targetUid.remove()
        return value
    }

    fun clear() {
        targetUid.remove()
    }

    fun effectiveCallerUid(binderUid: Int = Binder.getCallingUid()): Int {
        if (binderUid != Process.SYSTEM_UID && binderUid != 0) {
            return binderUid
        }
        return peekTargetUid() ?: binderUid
    }
}
