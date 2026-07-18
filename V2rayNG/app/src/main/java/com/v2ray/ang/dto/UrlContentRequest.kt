package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    /** Optional JSON object of extra request headers, e.g. {"Authorization":"Bearer x"}. */
    val requestHeaders: String? = null
)