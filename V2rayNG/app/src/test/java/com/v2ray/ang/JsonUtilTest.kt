package com.v2ray.ang

import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonUtilTest {

    @Test
    fun test_toJsonPretty_returnsFormattedJson() {
        val map = mapOf("key" to "value", "number" to 42)
        val result = JsonUtil.toJsonPretty(map)
        assertNotNull(result)
        assertTrue(result!!.contains("key"))
        assertTrue(result.contains("value"))
        assertTrue(result.contains("\n"))
    }

    @Test
    fun test_toJsonPretty_nullReturnsNull() {
        assertNull(JsonUtil.toJsonPretty(null))
    }

    @Test
    fun test_toJsonPretty_integerNotDouble() {
        data class Config(val port: Int, val enabled: Boolean)
        val config = Config(8080, true)
        val result = JsonUtil.toJsonPretty(config)
        assertNotNull(result)
        assertTrue(result!!.contains("8080"))
    }

    @Test
    fun test_toJsonRoundTrip() {
        val original = mapOf("a" to 1, "b" to "hello", "c" to true)
        val json = JsonUtil.toJson(original)
        assertNotNull(json)
        assertTrue(json.contains("\"a\":1"))
        assertTrue(json.contains("\"b\":\"hello\""))
        assertTrue(json.contains("\"c\":true"))
    }

    @Test
    fun test_fromJsonSafe_invalidJsonReturnsNull() {
        val result = JsonUtil.fromJsonSafe("not json", Map::class.java)
        assertNull(result)
    }
}
