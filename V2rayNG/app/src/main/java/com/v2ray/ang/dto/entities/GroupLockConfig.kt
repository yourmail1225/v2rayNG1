package com.v2ray.ang.dto.entities

data class GroupLockConfig(
    val enabled: Boolean = false,
    /** Legacy date-only expiry; superseded by [expiryEpochMinute] when non-zero. */
    val expiryEpochDay: Long = 0L,
    /** Minute-of-epoch expiry (local-time minutes); 0L means no minute-granularity expiry. */
    val expiryEpochMinute: Long = 0L,
    /** Minute-of-epoch stamp when the lock conditions were (re)applied, for a remaining-time bar. */
    val startEpochMinute: Long = 0L,
    val dataLimitBytes: Long = 0L,
    var usedBytes: Long = 0L,
)