package com.v2ray.ang.dto

data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSystemApp: Boolean,
    var isSelected: Int,
    /**
     * Application UID as reported by the platform, including the user id offset.
     *
     * Packages that declare the same `android:sharedUserId` (for example the media provider,
     * MTP and the download manager all at 10052) share this value, and Android's per-app VPN
     * rules can only be applied to the whole shared group.
     */
    val uid: Int = -1
)
