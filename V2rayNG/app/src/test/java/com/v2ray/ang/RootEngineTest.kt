package com.v2ray.ang

import com.v2ray.ang.root.RootEngine
import com.v2ray.ang.root.RootTunFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootEngineTest {
    @Test
    fun fromPref_defaultsToHev() {
        assertEquals(RootEngine.HEV, RootEngine.fromPref(null))
        assertEquals(RootEngine.HEV, RootEngine.fromPref(""))
        assertEquals(RootEngine.HEV, RootEngine.fromPref("nope"))
        assertEquals(RootEngine.HEV, RootEngine.fromPref("hev"))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("xray_tun"))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("XRAY_TUN"))
    }

    @Test
    fun xrayTun_featureGate_offInPhase1() {
        assertFalse(RootTunFeature.isImplemented)
        assertFalse(RootTunFeature.canUseXrayTun())
    }

    @Test
    fun prefValues_stable() {
        assertEquals("hev", RootEngine.HEV.prefValue)
        assertEquals("xray_tun", RootEngine.XRAY_TUN.prefValue)
        assertEquals(AppConfig.ROOT_ENGINE_HEV, RootEngine.HEV.prefValue)
        assertEquals(AppConfig.ROOT_ENGINE_XRAY_TUN, RootEngine.XRAY_TUN.prefValue)
    }
}
