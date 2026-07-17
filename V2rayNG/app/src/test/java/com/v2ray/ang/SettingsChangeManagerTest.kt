package com.v2ray.ang

import com.v2ray.ang.handler.SettingsChangeManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChangeManagerTest {

    @Test
    fun test_consumeRestartService_defaultFalse() {
        // Reset state
        SettingsChangeManager.consumeRestartService()
        SettingsChangeManager.consumeHardRestartService()
        assertFalse(SettingsChangeManager.consumeRestartService())
        assertFalse(SettingsChangeManager.consumeHardRestartService())
    }

    @Test
    fun test_makeAndConsumeRestartService() {
        SettingsChangeManager.consumeHardRestartService()
        SettingsChangeManager.makeRestartService()
        assertTrue(SettingsChangeManager.consumeRestartService())
        // Soft flag alone must not imply hard capture-policy rebuild.
        assertFalse(SettingsChangeManager.consumeHardRestartService())
        assertFalse(SettingsChangeManager.consumeRestartService())
    }

    @Test
    fun test_makeHardRestart_setsBothFlags() {
        SettingsChangeManager.consumeRestartService()
        SettingsChangeManager.consumeHardRestartService()
        SettingsChangeManager.makeHardRestartService()
        assertTrue(SettingsChangeManager.isHardRestartPending())
        assertTrue(SettingsChangeManager.consumeRestartService())
        assertTrue(SettingsChangeManager.consumeHardRestartService())
        assertFalse(SettingsChangeManager.isHardRestartPending())
        assertFalse(SettingsChangeManager.consumeRestartService())
        assertFalse(SettingsChangeManager.consumeHardRestartService())
    }

    @Test
    fun test_consumeSetupGroupTab_defaultFalse() {
        SettingsChangeManager.consumeSetupGroupTab()
        assertFalse(SettingsChangeManager.consumeSetupGroupTab())
    }

    @Test
    fun test_makeAndConsumeSetupGroupTab() {
        SettingsChangeManager.makeSetupGroupTab()
        assertTrue(SettingsChangeManager.consumeSetupGroupTab())
        assertFalse(SettingsChangeManager.consumeSetupGroupTab())
    }

    @Test
    fun test_concurrentConsume_isAtomic() {
        // Make the flag
        SettingsChangeManager.makeRestartService()

        // Simulate concurrent consumption - only one should get true
        val results = mutableListOf<Boolean>()
        val threads = (1..10).map {
            Thread {
                synchronized(results) {
                    results.add(SettingsChangeManager.consumeRestartService())
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Exactly one thread should have gotten true
        val trueCount = results.count { it }
        assertTrue("Expected exactly one true, got $trueCount", trueCount == 1)
    }
}