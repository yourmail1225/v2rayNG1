package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAffiliationInfoTest {

    @Test
    fun oldFormatJsonDefaultsNewLockFields() {
        val info = JsonUtil.fromJsonSafe("{\"testDelayMillis\":120,\"locked\":true}", ServerAffiliationInfo::class.java)

        assertTrue(info != null)
        assertEquals(120L, info!!.testDelayMillis)
        assertTrue(info.locked)
        assertFalse(info.persistentLock)
        assertEquals(0L, info.expiryEpochMinute)
        assertEquals(0L, info.dataLimitBytes)
        assertEquals(0L, info.usedBytes)
        assertEquals(0L, info.startEpochMinute)
    }

    @Test
    fun newFormatJsonRoundTripsAllFields() {
        val info = ServerAffiliationInfo(
            testDelayMillis = 55,
            locked = true,
            expiryEpochMinute = 29_146_230L,
            dataLimitBytes = 1_048_576,
            usedBytes = 524_288,
            startEpochMinute = 29_100_000L,
        )
        val parsed = JsonUtil.fromJsonSafe(JsonUtil.toJson(info), ServerAffiliationInfo::class.java)

        assertTrue(parsed != null)
        assertEquals(55L, parsed!!.testDelayMillis)
        assertTrue(parsed.locked)
        assertEquals(29_146_230L, parsed.expiryEpochMinute)
        assertEquals(1_048_576L, parsed.dataLimitBytes)
        assertEquals(524_288L, parsed.usedBytes)
        assertEquals(29_100_000L, parsed.startEpochMinute)
    }
}