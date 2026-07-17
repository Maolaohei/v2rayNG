package com.v2ray.ang.root

import android.content.Context

/**
 * Legacy ROOT dataplane: hev-socks5-tunnel + iptables MARK + local SOCKS.
 * Thin adapter over [RootProxyManager] so CoreRootService can be engine-agnostic.
 */
object HevRootDataPlane : RootDataPlane {
    override val engine: RootEngine = RootEngine.HEV

    override fun start(context: Context): RootProxyManager.RootError? =
        RootProxyManager.startDetailed(context)

    override fun stop(context: Context) {
        RootProxyManager.stop(context)
    }

    override fun ensure(context: Context): RootProxyManager.RootError? =
        RootProxyManager.ensureRunning(context)

    override fun isHealthy(context: Context, strict: Boolean): Boolean =
        RootProxyManager.isHealthy(context, strict)

    override fun isRuntimeLive(): Boolean = RootProxyManager.isRuntimeLive()

    override fun snapshot(context: Context): String {
        val healthy = runCatching { isHealthy(context) }.getOrDefault(false)
        val live = runCatching { isRuntimeLive() }.getOrDefault(false)
        val socks = runCatching { RootProxyManager.isLocalSocksReady() }.getOrDefault(false)
        val err = RootProxyManager.lastError
        return "engine=hev healthy=$healthy runtimeLive=$live socks=$socks lastError=$err"
    }
}
