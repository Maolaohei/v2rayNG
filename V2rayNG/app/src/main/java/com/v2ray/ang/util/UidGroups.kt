package com.v2ray.ang.util

import com.v2ray.ang.dto.AppInfo

/**
 * One UID and every installed package that reports it.
 *
 * Android's per-app VPN rules are UID-scoped: `VpnService.Builder` turns a package name into a
 * UID before it reaches the kernel, so a selection can only ever take effect for a whole group.
 * This model is the single source of truth shared by the app list, the stored selection, and the
 * service that configures the tunnel.
 */
data class UidGroup(
    val uid: Int,
    val packages: List<String>
) {
    val isShared: Boolean get() = packages.size > 1
}

object UidGroups {

    /** Sentinel used by [AppInfo] for entries that never resolved to an installed package. */
    const val UNRESOLVED_UID = -1

    /** uid -> group, with packages sorted so persisted output stays stable. */
    fun of(apps: Collection<AppInfo>): Map<Int, UidGroup> =
        apps.filter { it.uid != UNRESOLVED_UID }
            .groupBy { it.uid }
            .mapValues { (uid, members) ->
                UidGroup(uid, members.map { it.packageName }.distinct().sorted())
            }

    fun packageToUid(apps: Collection<AppInfo>): Map<String, Int> =
        apps.associate { it.packageName to it.uid }

    /**
     * Expands a selection to every package that would actually be affected.
     *
     * Packages without a resolvable UID are kept as-is: they are configured by name and cannot
     * pull siblings in.
     */
    fun effectivePackages(
        selected: Set<String>,
        groups: Map<Int, UidGroup>,
        pkgToUid: Map<String, Int>
    ): Set<String> {
        if (selected.isEmpty()) return emptySet()
        if (groups.isEmpty()) return selected

        return buildSet(selected.size) {
            addAll(selected)
            selected.forEach { packageName ->
                val group = groups[pkgToUid[packageName] ?: UNRESOLVED_UID] ?: return@forEach
                addAll(group.packages)
            }
        }
    }

    /** Whole-group completion: any partially selected shared UID becomes fully selected. */
    fun normalize(
        selected: Set<String>,
        groups: Map<Int, UidGroup>,
        pkgToUid: Map<String, Int>
    ): Set<String> = effectivePackages(selected, groups, pkgToUid)

    /** Shared UID groups that are only partially selected, i.e. states the kernel cannot express. */
    fun splitGroups(
        selected: Set<String>,
        groups: Map<Int, UidGroup>
    ): List<UidGroup> = groups.values.filter { group ->
        group.isShared &&
            group.packages.any { it in selected } &&
            group.packages.any { it !in selected }
    }

    /** Candidates for inversion: shared UID packages are skipped so no group can be split. */
    fun invertiblePackages(
        displayed: Collection<String>,
        groups: Map<Int, UidGroup>,
        pkgToUid: Map<String, Int>
    ): List<String> = displayed.filter { packageName ->
        val group = groups[pkgToUid[packageName] ?: UNRESOLVED_UID]
        group == null || !group.isShared
    }
}
