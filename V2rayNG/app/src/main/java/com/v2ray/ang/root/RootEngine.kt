package com.v2ray.ang.root

/**
 * ROOT data-plane engine selection.
 *
 * - [HEV]: legacy hev-socks5-tunnel + local SOCKS (current default).
 * - [XRAY_TUN]: root-created TUN fd fed into Bray-Core native TUN inbound (target).
 */
enum class RootEngine(val prefValue: String) {
    HEV("hev"),
    XRAY_TUN("xray_tun");

    companion object {
        fun fromPref(raw: String?): RootEngine {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.prefValue == v } ?: HEV
        }
    }
}
