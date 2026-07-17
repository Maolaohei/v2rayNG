package com.v2ray.ang.root

/**
 * ROOT dataplane engines.
 *
 * Device-wide ROOT capture now always uses [XRAY_TUN] (root TUN fd -> Bray-Core).
 * [HEV] remains only as a historical pref value and is mapped to [XRAY_TUN].
 * hev-socks5-tunnel is still used by VPN-mode optional LAN sharing, not by ROOT.
 */
enum class RootEngine(val prefValue: String) {
    XRAY_TUN("xray_tun"),
    @Deprecated("ROOT no longer uses hev; kept for pref migration only")
    HEV("hev");

    companion object {
        fun fromPref(raw: String?): RootEngine {
            val v = raw?.trim()?.lowercase().orEmpty()
            // Any legacy/unknown value resolves to the only supported ROOT engine.
            return if (v == HEV.prefValue || v == XRAY_TUN.prefValue || v.isEmpty()) {
                XRAY_TUN
            } else {
                XRAY_TUN
            }
        }
    }
}
