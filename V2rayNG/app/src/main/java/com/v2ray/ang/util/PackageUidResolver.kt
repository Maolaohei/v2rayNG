package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves package names to kernel UIDs for ROOT iptables owner-match rules.
 * Expands related UIDs (same-uid packages / sharedUserId siblings when available)
 * so multi-process clients are less likely to leak past per-app capture.
 *
 * Note: ApplicationInfo.sharedUserId is gone from public stubs on recent compileSdk,
 * so sharedUserId is read reflectively when still present at runtime.
 */
object PackageUidResolver {

    // In-process cache to avoid resolving the same package UID repeatedly.
    private val packageUidCache = ConcurrentHashMap<String, String>()

    val packageUidMap: Map<String, String>
        get() = packageUidCache

    fun packageNamesToUids(context: Context, packageNames: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        packageNames.forEach { pkg ->
            // Primary UID (cached).
            val primary = packageUidCache[pkg] ?: resolveUid(context, pkg)?.also { uid ->
                packageUidCache[pkg] = uid
            }
            if (primary != null) out.add(primary)
            // Some clients (e.g. multi-process / sharedUserId) may emit traffic from sibling UIDs.
            resolveRelatedUids(context, pkg).forEach { out.add(it) }
        }
        val list = out.toList()
        if (packageNames.isNotEmpty()) {
            LogUtil.i(
                AppConfig.TAG,
                "PackageUidResolver: ${packageNames.joinToString()} -> uids=[${list.joinToString()}]"
            )
        }
        return list
    }

    private fun resolveRelatedUids(context: Context, packageName: String): List<String> {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val uids = linkedSetOf(appInfo.uid.toString())

            // Packages already sharing this UID (split APKs / multi-package same uid).
            try {
                pm.getPackagesForUid(appInfo.uid)?.forEach { siblingPkg ->
                    try {
                        uids.add(pm.getApplicationInfo(siblingPkg, 0).uid.toString())
                    } catch (_: Exception) {
                        // ignore missing sibling
                    }
                }
            } catch (_: Exception) {
                // older / restricted PackageManager
            }

            // sharedUserId siblings (API field removed from public stubs; reflect when present).
            val shared = readSharedUserId(appInfo)
            if (!shared.isNullOrBlank()) {
                try {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0).forEach { info ->
                        if (readSharedUserId(info) == shared) {
                            uids.add(info.uid.toString())
                        }
                    }
                } catch (_: Exception) {
                    // ignore enumeration failures
                }
            }
            uids.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ApplicationInfo.sharedUserId was removed from public SDK stubs used by recent
     * compileSdk. Keep best-effort access for older/runtime fields without breaking compile.
     */
    private fun readSharedUserId(appInfo: ApplicationInfo): String? {
        return try {
            val field = ApplicationInfo::class.java.getField("sharedUserId")
            field.get(appInfo) as? String
        } catch (_: Exception) {
            try {
                val method = ApplicationInfo::class.java.getMethod("getSharedUserId")
                method.invoke(appInfo) as? String
            } catch (_: Exception) {
                null
            }
        }
    }

    fun uidsToPackageNames(uids: List<String>): List<String> {
        if (uids.isEmpty()) return emptyList()
        return packageUidCache.filterValues { it in uids }.keys.toList()
    }

    fun uidToPackageName(uid: String): String? {
        return packageUidCache.entries.firstOrNull { it.value == uid }?.key
    }

    private fun resolveUid(context: Context, packageName: String): String? {
        // Special token for connections whose UID cannot be resolved (mapped to -1)
        if (packageName == AppConfig.UNIDENTIFIED_PACKAGE) {
            val uid = "-1"
            LogUtil.d(AppConfig.TAG, "Special package: $packageName -> UID: $uid")
            return uid
        }

        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0).toString()
            LogUtil.d(AppConfig.TAG, "Package: $packageName -> UID: $uid")
            uid
        } catch (_: PackageManager.NameNotFoundException) {
            LogUtil.w(AppConfig.TAG, "Package not found: $packageName")
            null
        }
    }
}