package com.v2ray.ang.root

/**
 * ROOT always uses xray_tun once the open-fd path is available.
 * hev is not a ROOT engine anymore (VPN hev / LAN sharing still use hev binaries separately).
 */
object RootTunFeature {
    const val isImplemented: Boolean = true

    fun canUseXrayTun(): Boolean = isImplemented
}
