package com.v2ray.ang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectionLogicTest {

    private lateinit var tracker: SelectionTracker

    @Before
    fun setup() {
        tracker = SelectionTracker()
    }

    /**
     * Pure-logic replica of MainRecyclerAdapter selection tracking.
     * No Android/Gradle dependencies — can run with plain JDK + JUnit jar.
     */
    private class SelectionTracker {
        var selectedGuid: String? = null
        val notifiedPositions = mutableListOf<Int>()
        val guids = mutableListOf<String>()

        fun setSelectedGuid(guid: String?) {
            val oldGuid = selectedGuid
            selectedGuid = guid
            if (oldGuid != null) {
                val oldIdx = guids.indexOf(oldGuid)
                if (oldIdx >= 0) notifiedPositions.add(oldIdx)
            }
            if (guid != null) {
                val newIdx = guids.indexOf(guid)
                if (newIdx >= 0) notifiedPositions.add(newIdx)
            }
        }

        fun setSelectServer(fromPosition: Int, toPosition: Int) {
            if (fromPosition >= 0 && fromPosition < guids.size) {
                notifiedPositions.add(fromPosition)
            }
            if (toPosition >= 0 && toPosition < guids.size) {
                notifiedPositions.add(toPosition)
            }
        }

        fun clearNotifications() {
            notifiedPositions.clear()
        }
    }

    @Test
    fun test_selectNewServer_notifiesBothPositions() {
        tracker.guids.addAll(listOf("A", "B", "C"))
        tracker.selectedGuid = "A"

        tracker.clearNotifications()
        tracker.setSelectedGuid("B")

        assertEquals(2, tracker.notifiedPositions.size)
        assertEquals(0, tracker.notifiedPositions[0])
        assertEquals(1, tracker.notifiedPositions[1])
        assertEquals("B", tracker.selectedGuid)
    }

    @Test
    fun test_selectSameServer_noNotifications() {
        tracker.guids.addAll(listOf("A", "B"))
        tracker.selectedGuid = "A"

        tracker.clearNotifications()
        tracker.setSelectedGuid("A")

        assertEquals(0, tracker.notifiedPositions.size)
        assertEquals("A", tracker.selectedGuid)
    }

    @Test
    fun test_selectWhenNoPreviousSelection_onlyNotifiesNewPosition() {
        tracker.guids.addAll(listOf("A", "B"))
        tracker.selectedGuid = null

        tracker.clearNotifications()
        tracker.setSelectedGuid("B")

        assertEquals(1, tracker.notifiedPositions.size)
        assertEquals(1, tracker.notifiedPositions[0])
        assertEquals("B", tracker.selectedGuid)
    }

    @Test
    fun test_deselectServer_onlyNotifiesOldPosition() {
        tracker.guids.addAll(listOf("A", "B"))
        tracker.selectedGuid = "A"

        tracker.clearNotifications()
        tracker.setSelectedGuid(null)

        assertEquals(1, tracker.notifiedPositions.size)
        assertEquals(0, tracker.notifiedPositions[0])
        assertNull(tracker.selectedGuid)
    }

    @Test
    fun test_selectServerNotInList_onlyNotifiesOldPosition() {
        tracker.guids.addAll(listOf("A", "B"))
        tracker.selectedGuid = "A"

        tracker.clearNotifications()
        tracker.setSelectedGuid("Z")

        assertEquals(1, tracker.notifiedPositions.size)
        assertEquals(0, tracker.notifiedPositions[0])
        assertEquals("Z", tracker.selectedGuid)
    }

    @Test
    fun test_switchBetweenServers_multipleTimes() {
        tracker.guids.addAll(listOf("A", "B", "C"))

        tracker.setSelectedGuid("A")
        assertEquals("A", tracker.selectedGuid)

        tracker.clearNotifications()
        tracker.setSelectedGuid("B")
        assertEquals(2, tracker.notifiedPositions.size)
        assertEquals(0, tracker.notifiedPositions[0])
        assertEquals(1, tracker.notifiedPositions[1])

        tracker.clearNotifications()
        tracker.setSelectedGuid("C")
        assertEquals(2, tracker.notifiedPositions.size)
        assertEquals(1, tracker.notifiedPositions[0])
        assertEquals(2, tracker.notifiedPositions[1])

        tracker.clearNotifications()
        tracker.setSelectedGuid("A")
        assertEquals(2, tracker.notifiedPositions.size)
        assertEquals(2, tracker.notifiedPositions[0])
        assertEquals(0, tracker.notifiedPositions[1])
    }

    @Test
    fun test_emptyList_selectServer_noNotifications() {
        tracker.selectedGuid = null

        tracker.clearNotifications()
        tracker.setSelectedGuid("A")

        assertEquals(0, tracker.notifiedPositions.size)
        assertEquals("A", tracker.selectedGuid)
    }

    @Test
    fun test_setSelectServer_withNegativePosition_noCrash() {
        tracker.guids.addAll(listOf("A", "B"))
        tracker.selectedGuid = "A"

        tracker.clearNotifications()
        tracker.setSelectServer(-1, 1)

        assertEquals(1, tracker.notifiedPositions.size)
        assertEquals(1, tracker.notifiedPositions[0])
    }

    @Test
    fun test_setSelectServer_withOutOfBoundsPosition_noCrash() {
        tracker.guids.addAll(listOf("A", "B"))

        tracker.clearNotifications()
        tracker.setSelectServer(0, 99)

        assertEquals(1, tracker.notifiedPositions.size)
        assertEquals(0, tracker.notifiedPositions[0])
    }

    @Test
    fun test_crossFragment_sync_oldNotInNewList() {
        val adapter1 = SelectionTracker()
        val adapter2 = SelectionTracker()

        adapter1.guids.addAll(listOf("A", "B"))
        adapter2.guids.addAll(listOf("C", "D"))

        adapter1.setSelectedGuid("B")
        assertEquals("B", adapter1.selectedGuid)

        adapter2.setSelectedGuid(adapter1.selectedGuid)
        assertEquals("B", adapter2.selectedGuid)
        assertEquals(0, adapter2.notifiedPositions.size)
    }

    @Test
    fun test_crossFragment_sync_switchBackAndForth() {
        val adapter1 = SelectionTracker()
        val adapter2 = SelectionTracker()

        adapter1.guids.addAll(listOf("A", "B"))
        adapter2.guids.addAll(listOf("C", "D"))

        adapter1.setSelectedGuid("B")
        adapter2.setSelectedGuid(adapter1.selectedGuid)

        adapter2.setSelectedGuid("D")
        adapter1.setSelectedGuid(adapter2.selectedGuid)

        assertEquals("D", adapter1.selectedGuid)
        assertEquals(1, adapter1.notifiedPositions.size)
        assertEquals(1, adapter1.notifiedPositions[0])
    }

    @Test
    fun test_selectAfterRemove_oldServerGone() {
        tracker.guids.addAll(listOf("A", "B", "C"))
        tracker.selectedGuid = "A"

        tracker.guids.remove("A")
        tracker.clearNotifications()
        tracker.setSelectedGuid("B")

        assertEquals(0, tracker.notifiedPositions[0])
        assertEquals(1, tracker.notifiedPositions.size)
    }

    @Test
    fun test_rapidSelection_noDuplicateNotifications() {
        tracker.guids.addAll(listOf("A", "B", "C"))

        tracker.setSelectedGuid("A")
        tracker.setSelectedGuid("B")
        tracker.setSelectedGuid("C")

        assertEquals("C", tracker.selectedGuid)

        tracker.clearNotifications()
        tracker.setSelectedGuid("A")

        assertEquals(2, tracker.notifiedPositions.size)
        assertEquals(2, tracker.notifiedPositions[0])
        assertEquals(0, tracker.notifiedPositions[1])
    }
}
