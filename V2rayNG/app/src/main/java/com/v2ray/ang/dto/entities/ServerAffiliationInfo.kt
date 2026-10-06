package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var locked: Boolean = false,
    var persistentLock: Boolean = false,
    var expiryEpochMinute: Long = 0L,
    var dataLimitBytes: Long = 0L,
    var usedBytes: Long = 0L,
    var startEpochMinute: Long = 0L,
)
