package com.v2ray.ang

import com.v2ray.ang.root.RootDataPlanes
import com.v2ray.ang.root.RootEngine
import com.v2ray.ang.root.RootTunFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootEngineTest {
    @Test
    fun fromPref_alwaysXrayTun() {
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref(null))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref(""))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("nope"))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("hev"))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("xray_tun"))
        assertEquals(RootEngine.XRAY_TUN, RootEngine.fromPref("XRAY_TUN"))
    }

    @Test
    fun xrayTun_isDefaultRootEngine() {
        assertTrue(RootTunFeature.isImplemented)
        assertTrue(RootTunFeature.canUseXrayTun())
        assertEquals(RootEngine.XRAY_TUN, RootDataPlanes.effectiveEngine())
    }

    @Test
    fun prefValues_stable() {
        assertEquals("xray_tun", RootEngine.XRAY_TUN.prefValue)
        assertEquals(AppConfig.ROOT_ENGINE_XRAY_TUN, RootEngine.XRAY_TUN.prefValue)
    }
}
