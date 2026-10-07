package com.v2ray.ang.dto.entities

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val testDelayMillis: Long = 0L,
    /**
     * Remarks of the subscription this server belongs to, resolved by the
     * ViewModel while the row list is built. Only shown in the "all" group,
     * but resolved once per distinct subscription instead of once per row.
     */
    val subscriptionRemarks: String = "",
)
