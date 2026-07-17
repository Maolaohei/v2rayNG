package com.v2ray.ang.root

import android.content.Context

/**
 * Single owner for ROOT system traffic capture after core is up.
 * CoreRootService should talk to [RootDataPlanes.current], not engine details.
 */
interface RootDataPlane {
    val engine: RootEngine

    /** Full install (or no-op rebuild policy decided by implementation). */
    fun start(context: Context): RootProxyManager.RootError?

    fun stop(context: Context)

    /** Graduated repair; must not thrash a live dataplane. */
    fun ensure(context: Context): RootProxyManager.RootError?

    fun isHealthy(context: Context, strict: Boolean = false): Boolean

    /** Cheap liveness for UI/network paths (no heavy su when possible). */
    fun isRuntimeLive(): Boolean

    fun snapshot(context: Context): String

    /** Remaining repair backoff; 0 means repair allowed. */
    fun repairBackoffRemainingMs(): Long = 0L
}

object RootDataPlanes {
    /** ROOT always uses xray_tun. Non-ROOT callers should not use this. */
    fun effectiveEngine(): RootEngine = RootEngine.XRAY_TUN

    fun current(): RootDataPlane = XrayTunRootDataPlane
}
