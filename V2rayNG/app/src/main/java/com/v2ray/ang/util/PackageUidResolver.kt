package com.v2ray.ang.util

import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import java.util.concurrent.ConcurrentHashMap

object PackageUidResolver {

    // In-process cache to avoid resolving the same package UID repeatedly.
    private val packageUidCache = ConcurrentHashMap<String, String>()

    // UID -> every package reporting it, so shared-UID groups stay consistent across callers.
    private val uidPackageCache = ConcurrentHashMap<String, List<String>>()

    val packageUidMap: Map<String, String>
        get() = packageUidCache

    /**
     * Package names collapsed to distinct UIDs.
     *
     * Packages sharing one UID collapse to a single entry: the UID list is consumed by
     * root-mode iptables rules and routing `process` rules, where duplicates are noise.
     */
    fun packageNamesToUids(context: Context, packageNames: List<String>): List<String> =
        packageUidGroups(context, packageNames).keys.toList()

    /**
     * Package names grouped by UID, each group holding the whole UID.
     *
     * Android matches VPN and routing rules per UID, so one listed package always affects its
     * siblings. Callers use this to report the real coverage instead of a single name.
     */
    fun packageUidGroups(
        context: Context,
        packageNames: List<String>
    ): LinkedHashMap<String, List<String>> = UidGroups.groupByUid(
        packageNames = packageNames,
        resolveUid = { packageName -> resolveUid(context, packageName) },
        packagesForUid = { uid -> packagesForUid(context, uid) }
    )

    /** Expands package names to every package sharing their UID, preserving input order. */
    fun expandToUidGroups(context: Context, packageNames: List<String>): List<String> =
        UidGroups.expandByUid(
            packageNames = packageNames,
            resolveUid = { packageName -> resolveUid(context, packageName) },
            packagesForUid = { uid -> packagesForUid(context, uid) }
        )

    fun uidsToPackageNames(uids: List<String>): List<String> {
        if (uids.isEmpty()) return emptyList()
        return packageUidCache.filterValues { it in uids }.keys.toList()
    }

    fun uidToPackageName(uid: String): String? {
        return packageUidCache.entries.firstOrNull { it.value == uid }?.key
    }

    private fun resolveUid(context: Context, packageName: String): String? {
        packageUidCache[packageName]?.let { return it }

        // Special token for connections whose UID cannot be resolved (mapped to -1)
        if (packageName == AppConfig.UNIDENTIFIED_PACKAGE) {
            val uid = "-1"
            LogUtil.d(AppConfig.TAG, "Special package: $packageName -> UID: $uid")
            packageUidCache[packageName] = uid
            return uid
        }

        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0).toString()
            LogUtil.d(AppConfig.TAG, "Package: $packageName -> UID: $uid")
            packageUidCache[packageName] = uid
            uid
        } catch (_: PackageManager.NameNotFoundException) {
            LogUtil.w(AppConfig.TAG, "Package not found: $packageName")
            null
        }
    }

    /** Every installed package reporting [uid], or null when the platform knows none. */
    private fun packagesForUid(context: Context, uid: String): List<String>? {
        uidPackageCache[uid]?.let { return it }
        val appId = uid.toIntOrNull() ?: return null
        return try {
            context.packageManager.getPackagesForUid(appId)
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.also { uidPackageCache[uid] = it }
        } catch (_: Exception) {
            null
        }
    }
}
