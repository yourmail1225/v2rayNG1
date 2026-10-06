package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.GroupLockConfig
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LockEvaluatorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun nullLockIsAlwaysAllowed() {
        assertTrue(LockEvaluator.evaluate(null, todayEpochDay = 500) is LockEvaluator.Decision.Allow)
    }

    @Test
    fun disabledLockIsAllowed() {
        val lock = GroupLockConfig(enabled = false, expiryEpochDay = 1, dataLimitBytes = 1)
        assertTrue(LockEvaluator.evaluate(lock, todayEpochDay = 999) is LockEvaluator.Decision.Allow)
    }

    @Test
    fun expiredLockIsDenied() {
        val lock = GroupLockConfig(enabled = true, expiryEpochDay = 10)
        val decision = LockEvaluator.evaluate(lock, todayEpochDay = 11)
        assertTrue(decision is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedReason.EXPIRED,
            (decision as LockEvaluator.Decision.Denied).reason
        )
        assertEquals(LockEvaluator.DeniedScope.GROUP, (decision as LockEvaluator.Decision.Denied).scope)
    }

    @Test
    fun lockExpiresTheDayAfterTheExpiryDay() {
        val lock = GroupLockConfig(enabled = true, expiryEpochDay = 10)
        assertTrue(LockEvaluator.evaluate(lock, todayEpochDay = 10) is LockEvaluator.Decision.Allow)
    }

    @Test
    fun dataLimitReachedIsDenied() {
        val lock = GroupLockConfig(enabled = true, dataLimitBytes = 100)
        val decision = LockEvaluator.evaluate(lock, todayEpochDay = 0, currentUsedBytes = 100)
        assertTrue(decision is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedReason.DATA_LIMIT_REACHED,
            (decision as LockEvaluator.Decision.Denied).reason
        )
        assertEquals(LockEvaluator.DeniedScope.GROUP, (decision as LockEvaluator.Decision.Denied).scope)
    }

    @Test
    fun dataLimitBelowThresholdIsAllowed() {
        val lock = GroupLockConfig(enabled = true, dataLimitBytes = 100)
        assertTrue(
            LockEvaluator.evaluate(lock, todayEpochDay = 0, currentUsedBytes = 99) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun defaultZeroLimitDoesNotDeny() {
        val lock = GroupLockConfig(enabled = true)
        assertTrue(LockEvaluator.evaluate(lock, todayEpochDay = 10_000, currentUsedBytes = 10_000) is LockEvaluator.Decision.Allow)
    }

    @Test
    fun dateHelpersRoundTripViaUtc() {
        val epochDay = LockEvaluator.parseEpochDay("2025-06-01", utc)
        assertEquals(epochDay, LockEvaluator.todayEpochDay(utc, nowMillis = epochDay * 86_400_000L))
        assertEquals("2025-06-01", LockEvaluator.formatEpochDay(epochDay, utc))
    }

    @Test
    fun parseEpochDayBlankOrInvalidReturnsZero() {
        assertEquals(0L, LockEvaluator.parseEpochDay("", utc))
        assertEquals(0L, LockEvaluator.parseEpochDay("not-a-date", utc))
        assertEquals(0L, LockEvaluator.parseEpochDay("2025/06/01", utc))
    }

    @Test
    fun formatEpochDayZeroIsEmptyString() {
        assertEquals("", LockEvaluator.formatEpochDay(0, utc))
    }

    @Test
    fun todayEpochDayUsesUtcWhenAsked() {
        // Exactly one day since the Unix epoch in UTC.
        val day = LockEvaluator.todayEpochDay(utc, nowMillis = 86_400_000L)
        assertEquals(1L, day)
    }

    @Test
    fun todayEpochMinuteUsesUtcWhenAsked() {
        // 2025-06-01 10:30 UTC in wall-clock minutes.
        val minute = LockEvaluator.todayEpochMinute(utc, nowMillis = 29_146_230L * 60_000L)
        assertEquals(29_146_230L, minute)
    }

    @Test
    fun parseEpochMinuteRoundTripsDateTime() {
        val minute = LockEvaluator.parseEpochMinute("2025-06-01 10:30", utc)
        assertEquals(29_146_230L, minute)
        assertEquals("2025-06-01 10:30", LockEvaluator.formatEpochMinute(minute, utc))
    }

    @Test
    fun parseEpochMinuteDateOnlyBecomesEndOfDay() {
        // A bare date keeps the legacy allow-whole-expiry-day semantics: expiry at
        // the minute after 23:59 of that day.
        val minute = LockEvaluator.parseEpochMinute("2025-06-01", utc)
        assertEquals("2025-06-02 00:00", LockEvaluator.formatEpochMinute(minute, utc))
    }

    @Test
    fun parseEpochMinuteBlankOrInvalidReturnsZero() {
        assertEquals(0L, LockEvaluator.parseEpochMinute("", utc))
        assertEquals(0L, LockEvaluator.parseEpochMinute("not-a-date", utc))
        assertEquals(0L, LockEvaluator.parseEpochMinute("2025-06-01 25:00", utc))
        assertEquals(0L, LockEvaluator.parseEpochMinute("2025/06/01 10:30", utc))
    }

    @Test
    fun formatEpochMinuteZeroIsEmptyString() {
        assertEquals("", LockEvaluator.formatEpochMinute(0, utc))
    }

    @Test
    fun minuteExpiryDeniesOnOrAfterExpiryMinute() {
        val lock = GroupLockConfig(enabled = true, expiryEpochMinute = 29_146_230L)
        assertTrue(
            LockEvaluator.evaluate(lock, nowEpochMinute = 29_146_229L) is LockEvaluator.Decision.Allow
        )
        val denied = LockEvaluator.evaluate(lock, nowEpochMinute = 29_146_230L)
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(LockEvaluator.DeniedReason.EXPIRED, (denied as LockEvaluator.Decision.Denied).reason)
        assertTrue(
            LockEvaluator.evaluate(lock, nowEpochMinute = 29_146_231L) is LockEvaluator.Decision.Denied
        )
    }

    @Test
    fun legacyDayExpiryStillAppliesWhenNoMinuteSet() {
        val lock = GroupLockConfig(enabled = true, expiryEpochDay = 10)
        assertTrue(
            LockEvaluator.evaluate(lock, todayEpochDay = 11, nowEpochMinute = 500_000) is LockEvaluator.Decision.Denied
        )
    }

    @Test
    fun minuteExpiryTakesPrecedenceOverLegacyDay() {
        val lock = GroupLockConfig(enabled = true, expiryEpochDay = 10, expiryEpochMinute = 29_146_230L)
        // Minute unexpired even though the legacy day already elapsed.
        assertTrue(
            LockEvaluator.evaluate(lock, todayEpochDay = 11, nowEpochMinute = 29_146_229L) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun unlockedProfileIsAlwaysAllowed() {
        assertTrue(
            LockEvaluator.evaluateProfile(
                locked = false,
                expiryEpochMinute = 1,
                dataLimitBytes = 1,
                nowEpochMinute = 999,
                currentUsedBytes = 999,
            ) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun lockedProfileWithoutConditionsIsAllowed() {
        assertTrue(
            LockEvaluator.evaluateProfile(locked = true) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun profileExpiryDeniesOnOrAfterExpiryMinute() {
        val denied = LockEvaluator.evaluateProfile(
            locked = true,
            expiryEpochMinute = 29_146_230L,
            nowEpochMinute = 29_146_230L,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedReason.EXPIRED,
            (denied as LockEvaluator.Decision.Denied).reason
        )
        assertEquals(LockEvaluator.DeniedScope.PROFILE, denied.scope)
        assertTrue(
            LockEvaluator.evaluateProfile(
                locked = true,
                expiryEpochMinute = 29_146_230L,
                nowEpochMinute = 29_146_229L,
            ) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun profileDataLimitReachedIsDenied() {
        val denied = LockEvaluator.evaluateProfile(
            locked = true,
            dataLimitBytes = 100,
            currentUsedBytes = 100,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedReason.DATA_LIMIT_REACHED,
            (denied as LockEvaluator.Decision.Denied).reason
        )
        assertEquals(LockEvaluator.DeniedScope.PROFILE, denied.scope)
    }

    @Test
    fun profileDataLimitBelowThresholdIsAllowed() {
        assertTrue(
            LockEvaluator.evaluateProfile(
                locked = true,
                dataLimitBytes = 100,
                currentUsedBytes = 99,
            ) is LockEvaluator.Decision.Allow
        )
    }

    @Test
    fun groupDenialIsScopedToGroup() {
        val denied = LockEvaluator.evaluate(
            GroupLockConfig(enabled = true, expiryEpochMinute = 1),
            nowEpochMinute = 2,
        )
        assertEquals(
            LockEvaluator.DeniedScope.GROUP,
            (denied as LockEvaluator.Decision.Denied).scope
        )
    }

    @Test
    fun permanentLockedProfileWithExpiredExpiryIsDeniedAsProfile() {
        val denied = LockEvaluator.evaluateServer(
            groupLock = null,
            affiliation = ServerAffiliationInfo(
                locked = false,
                persistentLock = true,
                expiryEpochMinute = 10,
            ),
            nowEpochMinute = 11,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedScope.PROFILE,
            (denied as LockEvaluator.Decision.Denied).scope
        )
        assertEquals(
            LockEvaluator.DeniedReason.EXPIRED,
            (denied as LockEvaluator.Decision.Denied).reason
        )
    }

    @Test
    fun permanentLockedProfileWithoutConditionsIsAllowed() {
        val decision = LockEvaluator.evaluateServer(
            groupLock = null,
            affiliation = ServerAffiliationInfo(locked = false, persistentLock = true),
            nowEpochMinute = 999,
        )
        assertTrue(decision is LockEvaluator.Decision.Allow)
    }

    @Test
    fun evaluateServerGroupDenialIsScopedToGroup() {
        val denied = LockEvaluator.evaluateServer(
            groupLock = GroupLockConfig(enabled = true, expiryEpochMinute = 1),
            affiliation = ServerAffiliationInfo(locked = false),
            nowEpochMinute = 2,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedScope.GROUP,
            (denied as LockEvaluator.Decision.Denied).scope
        )
    }

    @Test
    fun evaluateServerNullAffiliationWithCancelledGroupIsAllowed() {
        val decision = LockEvaluator.evaluateServer(
            groupLock = GroupLockConfig(enabled = false, expiryEpochMinute = 1),
            affiliation = null,
            nowEpochMinute = 999,
        )
        assertTrue(decision is LockEvaluator.Decision.Allow)
    }

    @Test
    fun evaluateServerPermanentLockAcceptsSharedUsedBytes() {
        // Persistent-lock profiles (imported from a locked package) enforce the whole
        // subscription's consumed bytes, even when the profile's own counter is low.
        val denied = LockEvaluator.evaluateServer(
            groupLock = null,
            affiliation = ServerAffiliationInfo(
                locked = false,
                persistentLock = true,
                dataLimitBytes = 100,
                usedBytes = 5,
            ),
            nowEpochMinute = 0,
            currentUsedBytes = 150,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
        assertEquals(
            LockEvaluator.DeniedReason.DATA_LIMIT_REACHED,
            (denied as LockEvaluator.Decision.Denied).reason
        )
        assertEquals(LockEvaluator.DeniedScope.PROFILE, denied.scope)
    }

    @Test
    fun evaluateServerPermanentLockBelowSharedSumIsAllowed() {
        val decision = LockEvaluator.evaluateServer(
            groupLock = null,
            affiliation = ServerAffiliationInfo(
                locked = false,
                persistentLock = true,
                dataLimitBytes = 100,
                usedBytes = 5,
            ),
            nowEpochMinute = 0,
            currentUsedBytes = 99,
        )
        assertTrue(decision is LockEvaluator.Decision.Allow)
    }

    @Test
    fun evaluateServerDefaultsToAffiliationCounter() {
        // Existing callers keep per-profile semantics when no shared counter is passed.
        val denied = LockEvaluator.evaluateServer(
            groupLock = null,
            affiliation = ServerAffiliationInfo(
                locked = false,
                persistentLock = true,
                dataLimitBytes = 100,
                usedBytes = 120,
            ),
            nowEpochMinute = 0,
        )
        assertTrue(denied is LockEvaluator.Decision.Denied)
    }

    @Test
    fun anEnabledGroupIsChargedWithoutAnyCondition() {
        // An activated row usually carries no expiry and no volume limit. Its traffic is
        // still what the panel reports, so charging must not depend on a limit existing.
        assertTrue(LockEvaluator.chargesUsage(GroupLockConfig(enabled = true)))
        assertTrue(LockEvaluator.chargesUsage(GroupLockConfig(enabled = true, expiryEpochMinute = 99)))
        assertTrue(LockEvaluator.chargesUsage(GroupLockConfig(enabled = true, dataLimitBytes = 1)))
    }

    @Test
    fun anUnlockedOrAbsentGroupIsNotCharged() {
        assertEquals(false, LockEvaluator.chargesUsage(null))
        assertEquals(
            false,
            LockEvaluator.chargesUsage(GroupLockConfig(enabled = false, dataLimitBytes = 1)),
        )
    }

    @Test
    fun chargingAndEnforcementAreIndependentQuestions() {
        // The row that broke reporting had no limit, yet charging is required for it.
        // Enforcement must still allow it, so the two answers have to stay separate.
        val openEnded = GroupLockConfig(enabled = true)
        assertTrue(LockEvaluator.chargesUsage(openEnded))
        assertTrue(LockEvaluator.evaluate(openEnded) is LockEvaluator.Decision.Allow)
    }
}