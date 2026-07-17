package com.v2ray.ang

import com.v2ray.ang.root.RootConnectivitySmoke
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootConnectivitySmokeTest {
    @Test
    fun result_shape() {
        val ok = RootConnectivitySmoke.Result(true, "socks-connect 1.1.1.1:443", 12)
        assertTrue(ok.ok)
        assertTrue(ok.detail.contains("1.1.1.1"))
        val bad = RootConnectivitySmoke.Result(false, "socks-closed", 3)
        assertFalse(bad.ok)
    }
}
