package com.v2ray.ang.root

/**
 * Gates the experimental ROOT xray_tun engine.
 * When true, pref_root_engine=xray_tun is honored; default engine remains hev.
 */
object RootTunFeature {
    /** Wired: native open + startLoop(fd) + rules-only install. Still experimental. */
    const val isImplemented: Boolean = true

    fun canUseXrayTun(): Boolean = isImplemented
}
