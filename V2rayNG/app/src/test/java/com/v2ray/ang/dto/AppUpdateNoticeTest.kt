package com.v2ray.ang.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNoticeTest {

    private fun notice(
        version: String = "2.4.0",
        url: String = "https://example.invalid/app.apk",
        notes: String = "",
    ) = AppUpdateNotice(version = version, url = url, notes = notes)

    @Test
    fun aNewerVersionWithALinkIsOffered() {
        assertTrue(notice().isNewerThan("2.3.8"))
    }

    @Test
    fun theSameVersionIsNotOffered() {
        assertFalse(notice(version = "2.3.8").isNewerThan("2.3.8"))
    }

    @Test
    fun anOlderVersionIsNotOffered() {
        assertFalse(notice(version = "2.3.7").isNewerThan("2.3.8"))
    }

    @Test
    fun aNoticeWithoutALinkIsNotOffered() {
        assertFalse(notice(url = "").isNewerThan("2.3.8"))
    }

    @Test
    fun aNoticeWithoutAVersionIsNotOffered() {
        assertFalse(notice(version = "").isNewerThan("2.3.8"))
    }

    @Test
    fun aMissingTrailingPartIsTheSameVersion() {
        assertEquals(0, AppUpdateNotice.compareVersions("2.4", "2.4.0"))
    }

    @Test
    fun aLeadingVIsIgnored() {
        assertTrue(AppUpdateNotice.compareVersions("v2.4.0", "2.3.8") > 0)
    }

    @Test
    fun aNonNumericPartIsTreatedAsZero() {
        assertEquals(0, AppUpdateNotice.compareVersions("2.4.beta", "2.4.0"))
    }

    @Test
    fun thePatchPartDecidesTheOrder() {
        assertTrue(AppUpdateNotice.compareVersions("2.3.9", "2.3.8") > 0)
        assertTrue(AppUpdateNotice.compareVersions("2.3.8", "2.3.9") < 0)
    }

    @Test
    fun anEmptyNoticeDoesNotCrashTheComparison() {
        assertEquals(0, AppUpdateNotice.compareVersions("", ""))
    }
}