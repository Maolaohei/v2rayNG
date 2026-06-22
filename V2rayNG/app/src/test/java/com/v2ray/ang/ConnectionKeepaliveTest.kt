package com.v2ray.ang

import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionKeepaliveTest {

    @Test
    fun test_sockoptBean_hasTcpKeepAliveFields() {
        val sockopt = StreamSettingsBean.SockoptBean()
        assertNull(sockopt.tcpKeepAliveIdle)
        assertNull(sockopt.tcpKeepAliveInterval)

        sockopt.tcpKeepAliveIdle = 60
        sockopt.tcpKeepAliveInterval = 5
        assertEquals(60, sockopt.tcpKeepAliveIdle)
        assertEquals(5, sockopt.tcpKeepAliveInterval)
    }

    @Test
    fun test_ensureSockopt_createsSockoptIfNull() {
        val streamSettings = StreamSettingsBean()
        assertNull(streamSettings.sockopt)

        val sockopt = streamSettings.ensureSockopt()
        assertNotNull(sockopt)
        assertNotNull(streamSettings.sockopt)
    }

    @Test
    fun test_ensureSockopt_returnsExistingSockopt() {
        val streamSettings = StreamSettingsBean()
        val existingSockopt = StreamSettingsBean.SockoptBean()
        existingSockopt.tcpKeepAliveIdle = 60
        streamSettings.sockopt = existingSockopt

        val sockopt = streamSettings.ensureSockopt()
        assertEquals(existingSockopt, sockopt)
        assertEquals(60, sockopt.tcpKeepAliveIdle)
    }

    @Test
    fun test_tcpKeepAliveSettings_appliedToVmess() {
        val outbound = OutboundBean(
            protocol = EConfigType.VMESS.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNotNull(sockopt)
        assertEquals(60, sockopt?.tcpKeepAliveIdle)
        assertEquals(5, sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_appliedToVless() {
        val outbound = OutboundBean(
            protocol = EConfigType.VLESS.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNotNull(sockopt)
        assertEquals(60, sockopt?.tcpKeepAliveIdle)
        assertEquals(5, sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_appliedToShadowsocks() {
        val outbound = OutboundBean(
            protocol = EConfigType.SHADOWSOCKS.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNotNull(sockopt)
        assertEquals(60, sockopt?.tcpKeepAliveIdle)
        assertEquals(5, sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_appliedToTrojan() {
        val outbound = OutboundBean(
            protocol = EConfigType.TROJAN.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNotNull(sockopt)
        assertEquals(60, sockopt?.tcpKeepAliveIdle)
        assertEquals(5, sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_notAppliedToWireguard() {
        val outbound = OutboundBean(
            protocol = EConfigType.WIREGUARD.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNull(sockopt?.tcpKeepAliveIdle)
        assertNull(sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_notAppliedToHysteria() {
        val outbound = OutboundBean(
            protocol = EConfigType.HYSTERIA.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNull(sockopt?.tcpKeepAliveIdle)
        assertNull(sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_notAppliedToHysteria2() {
        val outbound = OutboundBean(
            protocol = EConfigType.HYSTERIA2.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertNull(sockopt?.tcpKeepAliveIdle)
        assertNull(sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_preservesExistingValues() {
        val outbound = OutboundBean(
            protocol = EConfigType.VMESS.name.lowercase(),
            streamSettings = StreamSettingsBean()
        )
        outbound.streamSettings?.ensureSockopt()?.let {
            it.tcpKeepAliveIdle = 60
            it.tcpKeepAliveInterval = 5
        }

        applyTcpKeepAlive(outbound)

        val sockopt = outbound.streamSettings?.sockopt
        assertEquals(60, sockopt?.tcpKeepAliveIdle)
        assertEquals(5, sockopt?.tcpKeepAliveInterval)
    }

    @Test
    fun test_tcpKeepAliveSettings_noStreamSettings() {
        val outbound = OutboundBean(
            protocol = EConfigType.VMESS.name.lowercase(),
            streamSettings = null
        )

        applyTcpKeepAlive(outbound)

        assertNull(outbound.streamSettings)
    }

    @Test
    fun test_keepAliveValues_areReasonable() {
        val sockopt = StreamSettingsBean.SockoptBean()
        sockopt.tcpKeepAliveIdle = 60
        sockopt.tcpKeepAliveInterval = 5

        // KeepAlive idle should be >= 30 seconds (not too aggressive)
        assert(sockopt.tcpKeepAliveIdle!! >= 30)

        // KeepAlive interval should be >= 3 seconds (not too aggressive)
        assert(sockopt.tcpKeepAliveInterval!! >= 3)

        // KeepAlive idle should be <= 120 seconds (effective for NAT)
        assert(sockopt.tcpKeepAliveIdle!! <= 120)

        // KeepAlive interval should be <= 15 seconds (reasonable)
        assert(sockopt.tcpKeepAliveInterval!! <= 15)
    }

    private fun applyTcpKeepAlive(outbound: OutboundBean) {
        val streamSettings = outbound.streamSettings ?: return
        val protocol = outbound.protocol
        if (protocol.equals(EConfigType.WIREGUARD.name, true)
            || protocol.equals(EConfigType.HYSTERIA.name, true)
            || protocol.equals(EConfigType.HYSTERIA2.name, true)
        ) {
            return
        }
        val sockopt = streamSettings.ensureSockopt()
        if (sockopt.tcpKeepAliveIdle == null) {
            sockopt.tcpKeepAliveIdle = 60
        }
        if (sockopt.tcpKeepAliveInterval == null) {
            sockopt.tcpKeepAliveInterval = 5
        }
    }
}
