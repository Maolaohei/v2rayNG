package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.handler.SettingsManager

/**
 * Single owner for ROOT system traffic capture after core is up.
 * CoreRootService should talk to [RootDataPlanes.current], not hev/xray details.
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
}

object RootDataPlanes {
    /**
     * Effective engine: configured xray_tun only when feature-ready; otherwise hev.
     */
    fun effectiveEngine(): RootEngine {
        if (!SettingsManager.isRootMode()) return RootEngine.HEV
        val configured = SettingsManager.configuredRootEngine()
        if (configured == RootEngine.XRAY_TUN && RootTunFeature.canUseXrayTun()) {
            return RootEngine.XRAY_TUN
        }
        return RootEngine.HEV
    }

    fun current(): RootDataPlane = when (effectiveEngine()) {
        RootEngine.XRAY_TUN -> XrayTunRootDataPlane
        RootEngine.HEV -> HevRootDataPlane
    }
}
