package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Target ROOT dataplane: root-created TUN fd -> Bray-Core tun inbound (gVisor).
 * Phase 1: not implemented. [RootDataPlanes.effectiveEngine] must not select this
 * until [RootTunFeature.isImplemented] is true.
 */
object XrayTunRootDataPlane : RootDataPlane {
    override val engine: RootEngine = RootEngine.XRAY_TUN

    override fun start(context: Context): RootProxyManager.RootError? {
        LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun start requested but not implemented")
        return RootProxyManager.RootError.UNKNOWN
    }

    override fun stop(context: Context) {
        // No-op until fd/rules ownership is wired.
        LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun stop (noop stub)")
    }

    override fun ensure(context: Context): RootProxyManager.RootError? {
        LogUtil.w(AppConfig.TAG, "RootDataPlane: xray_tun ensure (noop stub)")
        return RootProxyManager.RootError.REPAIR_BACKED_OFF
    }

    override fun isHealthy(context: Context, strict: Boolean): Boolean = false

    override fun isRuntimeLive(): Boolean = false

    override fun snapshot(context: Context): String =
        "engine=xray_tun implemented=false healthy=false"
}
