package com.v2ray.ang.util

import com.v2ray.ang.dto.AppInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UidGroupsTest {

    private fun app(packageName: String, uid: Int) =
        AppInfo(appName = packageName, packageName = packageName, isSystemApp = true, isSelected = 0, uid = uid)

    // The case from the bug report: media provider, MTP, download manager and the download UI
    // all report uid 10052 (android.uid.media).
    private val mediaGroup = listOf(
        app("com.android.providers.media", 10052),
        app("com.android.mtp", 10052),
        app("com.android.providers.downloads", 10052),
        app("com.android.providers.downloads.ui", 10052),
        app("com.example.browser", 10123),
        app("com.example.chat", 10124)
    )

    @Test
    fun `of groups packages that share a uid`() {
        val groups = UidGroups.of(mediaGroup)

        assertEquals(3, groups.size)
        val media = groups.getValue(10052)
        assertTrue(media.isShared)
        assertEquals(4, media.packages.size)
        assertTrue("com.android.providers.downloads" in media.packages)
        assertFalse(groups.getValue(10123).isShared)
    }

    @Test
    fun `of deduplicates repeated package names and skips unresolved uids`() {
        val groups = UidGroups.of(
            listOf(
                app("com.android.mtp", 10052),
                app("com.android.mtp", 10052),
                AppInfo("ghost", "com.example.ghost", false, 0, UidGroups.UNRESOLVED_UID)
            )
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("com.android.mtp"), groups.getValue(10052).packages)
    }

    @Test
    fun `effectivePackages expands a single selected member to the whole group`() {
        val groups = UidGroups.of(mediaGroup)
        val pkgToUid = UidGroups.packageToUid(mediaGroup)

        val effective = UidGroups.effectivePackages(
            selected = setOf("com.android.mtp"),
            groups = groups,
            pkgToUid = pkgToUid
        )

        assertEquals(4, effective.size)
        assertTrue("com.android.providers.downloads" in effective)
        assertFalse("com.example.browser" in effective)
    }

    @Test
    fun `effectivePackages keeps ungrouped and unknown packages untouched`() {
        val groups = UidGroups.of(mediaGroup)
        val pkgToUid = UidGroups.packageToUid(mediaGroup)

        val effective = UidGroups.effectivePackages(
            selected = setOf("com.example.browser", "com.example.uninstalled"),
            groups = groups,
            pkgToUid = pkgToUid
        )

        assertEquals(setOf("com.example.browser", "com.example.uninstalled"), effective)
    }

    @Test
    fun `effectivePackages returns empty for an empty selection`() {
        assertTrue(
            UidGroups.effectivePackages(emptySet(), UidGroups.of(mediaGroup), emptyMap()).isEmpty()
        )
    }

    @Test
    fun `normalize completes a half selected shared group`() {
        val groups = UidGroups.of(mediaGroup)
        val pkgToUid = UidGroups.packageToUid(mediaGroup)

        val normalized = UidGroups.normalize(
            selected = setOf("com.android.providers.media", "com.android.mtp"),
            groups = groups,
            pkgToUid = pkgToUid
        )

        assertEquals(4, normalized.size)
        assertTrue(normalized.containsAll(groups.getValue(10052).packages))
    }

    @Test
    fun `normalize is idempotent for an already complete selection`() {
        val groups = UidGroups.of(mediaGroup)
        val pkgToUid = UidGroups.packageToUid(mediaGroup)
        val complete = groups.getValue(10052).packages.toSet() + "com.example.chat"

        val once = UidGroups.normalize(complete, groups, pkgToUid)
        val twice = UidGroups.normalize(once, groups, pkgToUid)

        assertEquals(complete, once)
        assertEquals(once, twice)
    }

    @Test
    fun `splitGroups reports only partially selected shared groups`() {
        val groups = UidGroups.of(mediaGroup)

        val splits = UidGroups.splitGroups(
            selected = setOf("com.android.providers.media", "com.android.mtp", "com.example.chat"),
            groups = groups
        )

        assertEquals(1, splits.size)
        assertEquals(10052, splits.single().uid)
    }

    @Test
    fun `splitGroups ignores ungrouped packages and complete groups`() {
        val groups = UidGroups.of(mediaGroup)

        val splits = UidGroups.splitGroups(
            selected = groups.getValue(10052).packages.toSet() + "com.example.chat",
            groups = groups
        )

        assertTrue(splits.isEmpty())
    }

    @Test
    fun `invertiblePackages skips every member of a shared group`() {
        val groups = UidGroups.of(mediaGroup)
        val pkgToUid = UidGroups.packageToUid(mediaGroup)
        val displayed = mediaGroup.map { it.packageName }

        val invertible = UidGroups.invertiblePackages(displayed, groups, pkgToUid)

        assertEquals(listOf("com.example.browser", "com.example.chat"), invertible)
    }

    @Test
    fun `invertiblePackages keeps packages whose uid did not resolve`() {
        val invertible = UidGroups.invertiblePackages(
            displayed = listOf("com.example.browser"),
            groups = emptyMap(),
            pkgToUid = mapOf("com.example.browser" to UidGroups.UNRESOLVED_UID)
        )

        assertEquals(listOf("com.example.browser"), invertible)
    }

    // Routing rules are stored as package names, so they need the same grouping without an
    // AppInfo list: one listed package must report every sibling its UID covers.

    private val uidByName = mediaGroup.associate { it.packageName to it.uid.toString() }
    private val namesByUid = mediaGroup.groupBy({ it.uid.toString() }) { it.packageName }
    private val resolveName: (String) -> String? = { uidByName[it] }
    private val namesForUid: (String) -> List<String>? = { namesByUid[it] }

    @Test
    fun `groupByUid expands one listed package to its whole uid`() {
        val groups = UidGroups.groupByUid(
            packageNames = listOf("com.android.providers.media"),
            resolveUid = resolveName,
            packagesForUid = namesForUid
        )

        assertEquals(listOf("10052"), groups.keys.toList())
        assertEquals(4, groups.getValue("10052").size)
        assertTrue("com.android.providers.downloads" in groups.getValue("10052"))
    }

    @Test
    fun `groupByUid collapses sibling packages into one uid entry`() {
        val groups = UidGroups.groupByUid(
            packageNames = listOf(
                "com.android.providers.media",
                "com.android.mtp",
                "com.example.browser"
            ),
            resolveUid = resolveName,
            packagesForUid = namesForUid
        )

        assertEquals(listOf("10052", "10123"), groups.keys.toList())
        assertEquals(1, groups.values.count { it.size > 1 })
    }

    @Test
    fun `groupByUid drops packages without a uid`() {
        val groups = UidGroups.groupByUid(
            packageNames = listOf("com.example.missing"),
            resolveUid = resolveName,
            packagesForUid = namesForUid
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupByUid falls back to the listed package when siblings are unknown`() {
        val groups = UidGroups.groupByUid(
            packageNames = listOf("com.example.browser"),
            resolveUid = resolveName,
            packagesForUid = { null }
        )

        assertEquals(listOf("com.example.browser"), groups.getValue("10123"))
    }

    @Test
    fun `expandByUid adds every sibling and keeps input order`() {
        val expanded = UidGroups.expandByUid(
            packageNames = listOf("com.android.providers.media", "com.example.browser"),
            resolveUid = resolveName,
            packagesForUid = namesForUid
        )

        assertEquals("com.android.providers.media", expanded.first())
        assertEquals(5, expanded.size)
        assertTrue("com.android.providers.downloads.ui" in expanded)
        assertEquals(expanded.distinct(), expanded)
    }

    @Test
    fun `expandByUid keeps names that never resolve`() {
        val expanded = UidGroups.expandByUid(
            packageNames = listOf("com.example.missing"),
            resolveUid = resolveName,
            packagesForUid = namesForUid
        )

        assertEquals(listOf("com.example.missing"), expanded)
    }
}
