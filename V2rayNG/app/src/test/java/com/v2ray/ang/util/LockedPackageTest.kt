package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedPackageTest {

    @Test
    fun encodeEmptyListReturnsEmptyString() {
        assertEquals("", LockedPackage.encode(emptyList()))
    }

    @Test
    fun parseEmptyStringReturnsEmptyResult() {
        val result = LockedPackage.parse("")
        assertEquals(0, result.entries.size)
        assertEquals("", result.remaining.trim())
    }

    @Test
    fun roundTripSingleSimpleEntry() {
        val text = LockedPackage.encode(listOf(LockedPackage.LockedEntry("vmess://AAAA")))
        val parsed = LockedPackage.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals("vmess://AAAA", parsed.entries[0].content)
        assertEquals(0L, parsed.entries[0].expiryEpochMinute)
        assertEquals(0L, parsed.entries[0].dataLimitBytes)
        assertEquals("", parsed.remaining.trim())
    }

    @Test
    fun roundTripPreservesLockSettings() {
        val text = LockedPackage.encode(
            listOf(
                LockedPackage.LockedEntry(
                    content = "vmess://AAAA",
                    expiryEpochMinute = 29_146_230L,
                    dataLimitBytes = 1_048_576L,
                )
            )
        )
        val parsed = LockedPackage.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals("vmess://AAAA", parsed.entries[0].content)
        assertEquals(29_146_230L, parsed.entries[0].expiryEpochMinute)
        assertEquals(1_048_576L, parsed.entries[0].dataLimitBytes)
    }

    @Test
    fun entryWithoutLockConditionsDefaultsToZero() {
        val parsed = LockedPackage.parse(
            LockedPackage.HEADER + "\n" +
                """[{"content":"ss://BBBB"}]""" + "\n" +
                LockedPackage.FOOTER
        )

        assertEquals(1, parsed.entries.size)
        assertEquals("ss://BBBB", parsed.entries[0].content)
        assertEquals(0L, parsed.entries[0].expiryEpochMinute)
        assertEquals(0L, parsed.entries[0].dataLimitBytes)
    }

    @Test
    fun legacyStringArrayFormatStillParses() {
        // Preceding released format: a plain array of config strings.
        val text = LockedPackage.HEADER + "\n" +
            """["vmess://AAAA","ss://BBBB"]""" + "\n" +
            LockedPackage.FOOTER

        val parsed = LockedPackage.parse(text)

        assertEquals(2, parsed.entries.size)
        assertEquals("vmess://AAAA", parsed.entries[0].content)
        assertEquals("ss://BBBB", parsed.entries[1].content)
        assertEquals(0L, parsed.entries[0].expiryEpochMinute)
        assertEquals(0L, parsed.entries[1].dataLimitBytes)
    }

    @Test
    fun invalidJsonBlockYieldsNoEntries() {
        val text = LockedPackage.HEADER + "\nnot-json\n" + LockedPackage.FOOTER
        val parsed = LockedPackage.parse(text)

        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun roundTripMultipleEntries() {
        val entries = listOf(
            LockedPackage.LockedEntry("first"),
            LockedPackage.LockedEntry("second")
        )
        val text = LockedPackage.encode(entries)
        val parsed = LockedPackage.parse(text)

        assertEquals(2, parsed.entries.size)
        assertEquals("first", parsed.entries[0].content)
        assertEquals("second", parsed.entries[1].content)
        assertEquals("", parsed.remaining.trim())
    }

    @Test
    fun multilineContentSurvivesRoundTrip() {
        val payload = "line1\nline2\nline3"
        val parsed = LockedPackage.parse(LockedPackage.encode(listOf(LockedPackage.LockedEntry(payload))))

        assertEquals(1, parsed.entries.size)
        assertEquals(payload, parsed.entries[0].content)
    }

    @Test
    fun surroundingTextIsCapturedAsRemaining() {
        val text = "vmess://prefix\n" +
            LockedPackage.HEADER + "\n" +
            """["locked-content"]""" + "\n" +
            LockedPackage.FOOTER + "\n" +
            "ss://suffix"

        val parsed = LockedPackage.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals("locked-content", parsed.entries[0].content)
        val remaining = parsed.remaining.trim()
        assertTrue(remaining.contains("vmess://prefix"))
        assertTrue(remaining.contains("ss://suffix"))
        assertTrue(LockedPackage.HEADER !in remaining)
        assertTrue(LockedPackage.FOOTER !in remaining)
    }

    @Test
    fun parseEmptyJsonBlockYieldsNoEntries() {
        val text = LockedPackage.HEADER + "\n" + LockedPackage.FOOTER
        val parsed = LockedPackage.parse(text)

        assertEquals(0, parsed.entries.size)
        assertEquals("", parsed.remaining.trim())
    }

    @Test
    fun markerCaseOrWhitespaceIsNotMatched() {
        val text = "${LockedPackage.HEADER.lowercase()}\n[\"a\"]\n${LockedPackage.FOOTER.lowercase()}"
        val parsed = LockedPackage.parse(text)

        assertEquals(0, parsed.entries.size)
        assertTrue(parsed.remaining.contains("a"))
    }

    @Test
    fun footerWithoutHeaderTreatsPayloadAsRemaining() {
        val text = LockedPackage.FOOTER + "\nss://payload"
        val parsed = LockedPackage.parse(text)

        assertEquals(0, parsed.entries.size)
        assertTrue(parsed.remaining.contains("ss://payload"))
    }

    @Test
    fun objectFormParsesPasswordAndSharedQuota() {
        val text = LockedPackage.HEADER + "\n" +
            """{"password":"s3cret","expiryEpochMinute":120,"dataLimitBytes":500,""" +
            """"entries":[{"content":"vless://a"},{"content":"ss://b"}]}""" + "\n" +
            LockedPackage.FOOTER

        val parsed = LockedPackage.parse(text)

        assertEquals("s3cret", parsed.password)
        assertEquals(2, parsed.entries.size)
        // Package-level values override the per-entry defaults.
        parsed.entries.forEach {
            assertEquals(120L, it.expiryEpochMinute)
            assertEquals(500L, it.dataLimitBytes)
        }
    }

    @Test
    fun objectFormPackageValuesOverridePerEntryValues() {
        val text = LockedPackage.HEADER + "\n" +
            """{"password":"x","expiryEpochMinute":120,"dataLimitBytes":500,""" +
            """"entries":[{"content":"vless://a","expiryEpochMinute":1,"dataLimitBytes":1}]}""" + "\n" +
            LockedPackage.FOOTER

        val parsed = LockedPackage.parse(text)

        assertEquals("x", parsed.password)
        assertEquals(1, parsed.entries.size)
        assertEquals(120L, parsed.entries[0].expiryEpochMinute)
        assertEquals(500L, parsed.entries[0].dataLimitBytes)
    }

    @Test
    fun objectFormWithoutPasswordHasBlankPassword() {
        val text = LockedPackage.HEADER + "\n" +
            """{"password":"","expiryEpochMinute":0,"dataLimitBytes":0,""" +
            """"entries":[{"content":"vless://a"}]}""" + "\n" +
            LockedPackage.FOOTER

        val parsed = LockedPackage.parse(text)

        assertEquals("", parsed.password)
        assertEquals(1, parsed.entries.size)
        assertEquals(0L, parsed.entries[0].expiryEpochMinute)
    }
}