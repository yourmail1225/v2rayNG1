package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.GroupLockConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.LockEvaluator

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
    val locked: Boolean,
    val usage: LockUsageUiModel = LockUsageUiModel(),
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
)

/**
 * Snapshot of a locked profile's quota for the two card progress bars: the
 * consumed-data share and the remaining lock window. Values are captured when the
 * row is built, so every bar in a frame derives from the same [nowEpochMinute].
 */
internal data class LockUsageUiModel(
    val usedBytes: Long = 0L,
    val dataLimitBytes: Long = 0L,
    val expiryEpochMinute: Long = 0L,
    val startEpochMinute: Long = 0L,
    val nowEpochMinute: Long = 0L,
) {
    val hasDataLimit: Boolean get() = dataLimitBytes > 0L
    val hasTimeLimit: Boolean
        get() = expiryEpochMinute > 0L && startEpochMinute > 0L && expiryEpochMinute > startEpochMinute

    /** Whether either quota bar has a limit to render against. */
    val isActive: Boolean get() = hasDataLimit || hasTimeLimit
}

/**
 * Consumed share of a data limit, or null when there is no limit to fill against.
 */
internal fun dataUsageFraction(usedBytes: Long, dataLimitBytes: Long): Float? =
    if (dataLimitBytes > 0L) (usedBytes.toFloat() / dataLimitBytes.toFloat()).coerceIn(0f, 1f) else null

/**
 * Remaining share of a data limit, reversed so the bar depletes as data is consumed,
 * or null when there is no limit to fill against.
 */
internal fun dataRemainingFraction(usedBytes: Long, dataLimitBytes: Long): Float? =
    if (dataLimitBytes > 0L) {
        ((dataLimitBytes - usedBytes).toFloat() / dataLimitBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

/**
 * Whether the data quota is finished because the consumed bytes reached the limit.
 */
internal fun isDataFinished(usedBytes: Long, dataLimitBytes: Long): Boolean =
    dataLimitBytes > 0L && usedBytes >= dataLimitBytes

/**
 * Whether the lock window is already over at [nowEpochMinute]. A missing expiry is
 * never considered finished.
 */
internal fun isTimeFinished(expiryEpochMinute: Long, nowEpochMinute: Long): Boolean =
    expiryEpochMinute > 0L && nowEpochMinute >= expiryEpochMinute

private const val GIBIBYTE = 1_073_741_824L

/**
 * Formats a byte count as a GB figure with at most one decimal place, dropping the
 * trailing `.0` for whole numbers (e.g. 1.2, 2, 0.5). The label and unit come from
 * the caller's string resource so locales keep control of the suffix.
 */
internal fun gbString(bytes: Long): String {
    if (bytes <= 0L) return "0"
    if (bytes < GIBIBYTE / 10) return "0.1"
    val tenths = bytes * 10L / GIBIBYTE
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}

/**
 * Remaining data after [usedBytes] of a [dataLimitBytes] quota, floored at zero and
 * formatted for the "GB remaining" card value.
 */
internal fun remainingGbString(usedBytes: Long, dataLimitBytes: Long): String =
    gbString(if (dataLimitBytes > usedBytes) dataLimitBytes - usedBytes else 0L)

/**
 * Remaining share of the lock window [startEpochMinute, expiryEpochMinute] seen at
 * [nowEpochMinute], or null when no valid window exists.
 */
internal fun timeRemainingFraction(
    startEpochMinute: Long,
    expiryEpochMinute: Long,
    nowEpochMinute: Long,
): Float? {
    if (startEpochMinute <= 0L || expiryEpochMinute <= startEpochMinute) return null
    val total = expiryEpochMinute - startEpochMinute
    val remaining = (expiryEpochMinute - nowEpochMinute).coerceIn(0L, total)
    return remaining.toFloat() / total.toFloat()
}

/**
 * Whole days remaining until [expiryEpochMinute] seen at [nowEpochMinute], rounded up,
 * or 0 when there is no valid expiry or the window has already ended. Both values are
 * epoch minutes, so one day is 1440 units.
 */
internal fun remainingDays(expiryEpochMinute: Long, nowEpochMinute: Long): Long {
    if (expiryEpochMinute <= 0L || nowEpochMinute <= 0L) return 0L
    if (nowEpochMinute >= expiryEpochMinute) return 0L
    return (expiryEpochMinute - nowEpochMinute + 1439L) / 1440L
}

/**
 * The subscription-wide consumed bytes a permanently locked profile's card should show.
 *
 * A locked group already accounts a whole subscription against one counter, seeded from
 * the row's own reported usage and written back to the panel. Summing per-profile
 * counters instead is a second tally that starts at zero and drifts away from the number
 * the panel shows, so the group's own figure wins whenever the group is locked. Without
 * a group lock the profile counters are the only usage recorded, so they are summed.
 *
 * @param groupLock The owning subscription's group lock, or null when it has none.
 * @param summedProfileUsedBytes Sum of the permanently locked profiles' own counters.
 */
internal fun sharedUsageForLockedProfiles(
    groupLock: GroupLockConfig?,
    summedProfileUsedBytes: Long,
): Long = if (groupLock?.enabled == true) groupLock.usedBytes else summedProfileUsedBytes

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
    affiliation: ServerAffiliationInfo?,
    groupLock: GroupLockConfig? = null,
    sharedLockedUsedBytes: Long = 0L,
): ServerRowUiModel {
    val profile = server.profile
    // A profile in a group with an active lock is treated exactly like a locked
    // profile: no edit, share, or unlock in the UI. The card renders the group-wide
    // quota, which is accounted against a single shared counter for the whole group,
    // unless the profile carries its own more-specific lock.
    val groupLocked = groupLock?.enabled == true
    val profileLockActive = affiliation?.locked == true || affiliation?.persistentLock == true
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank()
            ?: AngConfigManager.generateDescription(profile),
        typeDescription = serverProtocolDescription(profile),
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
        locked = server.locked || groupLocked,
        usage = if (profileLockActive) {
            // Profiles imported from a locked package (persistentLock) account their
            // consumed data against one subscription-wide counter, so every card in the
            // subscription shows the same remaining quota.
            buildLockUsage(
                affiliation,
                sharedUsedBytes = if (affiliation?.persistentLock == true) {
                    sharedLockedUsedBytes
                } else {
                    affiliation?.usedBytes ?: 0L
                },
            )
        } else if (groupLocked) {
            buildGroupLockUsage(groupLock)
        } else {
            LockUsageUiModel()
        },
    )
}

internal fun buildLockUsage(
    affiliation: ServerAffiliationInfo?,
    sharedUsedBytes: Long = affiliation?.usedBytes ?: 0L,
): LockUsageUiModel {
    val aff = affiliation ?: return LockUsageUiModel()
    // Only an active profile lock enforces a quota; a disabled lock's leftover
    // expiry and data-limit values are not shown as if they still applied.
    if (!aff.locked && !aff.persistentLock) return LockUsageUiModel()
    return LockUsageUiModel(
        usedBytes = sharedUsedBytes,
        dataLimitBytes = aff.dataLimitBytes,
        expiryEpochMinute = aff.expiryEpochMinute,
        startEpochMinute = aff.startEpochMinute,
        nowEpochMinute = LockEvaluator.todayEpochMinute(),
    )
}

/**
 * Snapshot of a subscription group's lock quota for a card's progress bars. The
 * group accounts usage against one shared counter, so every profile in the group
 * renders the same combined values rather than per-profile copies.
 */
internal fun buildGroupLockUsage(groupLock: GroupLockConfig?): LockUsageUiModel {
    val lock = groupLock ?: return LockUsageUiModel()
    if (!lock.enabled) return LockUsageUiModel()
    return LockUsageUiModel(
        usedBytes = lock.usedBytes,
        dataLimitBytes = lock.dataLimitBytes,
        expiryEpochMinute = lock.expiryEpochMinute,
        startEpochMinute = lock.startEpochMinute,
        nowEpochMinute = LockEvaluator.todayEpochMinute(),
    )
}

private fun serverProtocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    profile.network?.let { network ->
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}
