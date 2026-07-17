package com.v2ray.ang.root

/**
 * Gates the experimental ROOT xray_tun engine.
 * Phase 1: API/config plumbing only ? open-fd path is not implemented yet.
 * Flip [isImplemented] when RootTun native open + CoreRootService wiring ship.
 */
object RootTunFeature {
    /** Set true only after FD open + startLoop(fd) path is wired and smoke-tested. */
    const val isImplemented: Boolean = false

    fun canUseXrayTun(): Boolean = isImplemented
}
