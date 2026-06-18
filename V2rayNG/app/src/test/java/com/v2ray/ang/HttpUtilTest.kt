package com.v2ray.ang

import com.v2ray.ang.util.HttpUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class HttpUtilTest {

    @Test
    fun testIdnToASCII() {
        val regularUrl = "https://example.com/path"
        assertEquals(regularUrl, HttpUtil.toIdnUrl(regularUrl))

        val nonAsciiUrl = "https://例子.测试/path"
        val expectedNonAscii = "https://xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedNonAscii, HttpUtil.toIdnUrl(nonAsciiUrl))

        val mixedUrl = "https://例子.com/测试"
        val expectedMixed = "https://xn--fsqu00a.com/测试"
        assertEquals(expectedMixed, HttpUtil.toIdnUrl(mixedUrl))

        val basicAuthUrl = "https://user:password@example.com/path"
        assertEquals(basicAuthUrl, HttpUtil.toIdnUrl(basicAuthUrl))

        val basicAuthNonAscii = "https://user:password@例子.测试/path"
        val expectedBasicAuthNonAscii = "https://user:password@xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedBasicAuthNonAscii, HttpUtil.toIdnUrl(basicAuthNonAscii))

        val nonAsciiAuth = "https://用户:密码@example.com/path"
        assertEquals(nonAsciiAuth, HttpUtil.toIdnUrl(nonAsciiAuth))
    }

    @Test
    fun test_toIdnDomain_pureAscii() {
        assertEquals("example.com", HttpUtil.toIdnDomain("example.com"))
    }

    @Test
    fun test_toIdnDomain_ipAddress() {
        assertEquals("192.168.1.1", HttpUtil.toIdnDomain("192.168.1.1"))
        assertEquals("::1", HttpUtil.toIdnDomain("::1"))
    }

    @Test
    fun test_toIdnDomain_nonAscii() {
        val result = HttpUtil.toIdnDomain("例子.测试")
        assertEquals("xn--fsqu00a.xn--0zwm56d", result)
    }
}
