package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.GroupLockConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

interface MainDataSource : Closeable {
    val mainServiceEvent: Flow<MainServiceEvent>

    fun getSelectedSubscriptionId(): String
    fun setSelectedSubscriptionId(id: String)

    fun getSelectServer(): String?
    fun setSelectServer(guid: String)

    fun getConfirmRemove(): Boolean
    fun getDoubleColumnDisplay(): Boolean
    fun isGroupAllDisplayEnabled(): Boolean

    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String

    fun getSubscriptions(): List<SubscriptionCache>
    fun getSubscriptionItem(id: String): SubscriptionItem?

    fun getServerGuidList(groupId: String): List<String>
    fun decodeServerConfig(guid: String): ProfileItem?
    fun decodeServerRaw(guid: String): String?
    fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo?

    fun isProfileLocked(guid: String): Boolean
    fun isProfilePermanentlyLocked(guid: String): Boolean
    fun setProfileLocked(guid: String, locked: Boolean)
    fun setProfileLockConfig(guid: String, locked: Boolean, expiryEpochMinute: Long, dataLimitBytes: Long)
    fun resetProfileUsedBytes(guid: String)

    fun decodeGroupLock(groupId: String): GroupLockConfig
    fun encodeGroupLock(groupId: String, config: GroupLockConfig)
    fun resetGroupUsedBytes(groupId: String)

    fun exportLockedPackage(guids: List<String>): String
    fun setClipboard(text: String)

    fun encodeServerList(guids: List<String>, groupId: String)

    fun removeServer(guid: String)
    fun removeAllServer(): Int
    fun removeInvalidServerByGuid(guid: String): Int
    fun removeInvalidServersInGroup(groupId: String): Int

    fun clearAllTestDelayResults(guids: List<String>)
    fun sortByTestResultsForSub(subId: String)
    fun getSubsList(): List<String>

    suspend fun importBatchConfig(
        server: String?,
        subscriptionId: String,
        updateUI: Boolean
    ): Pair<Int, Int>

    fun updateConfigViaSubAll(): SubscriptionUpdateResult
    fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult

    fun shareNonCustomConfigsToClipboard(guids: List<String>): Int
    fun share2QRCode(guid: String): android.graphics.Bitmap?
    fun share2Clipboard(guid: String): Boolean

    /**
     * Shares a profile as a locked package. Overrides ship the lock conditions edited
     * in the lock dialog before they are saved; null values fall back to the profile's
     * stored conditions.
     */
    fun shareLocked2QRCode(
        guid: String,
        expiryEpochMinute: Long? = null,
        dataLimitBytes: Long? = null,
    ): android.graphics.Bitmap?

    fun shareLocked2Clipboard(
        guid: String,
        expiryEpochMinute: Long? = null,
        dataLimitBytes: Long? = null,
    ): Boolean

    fun writeLockedPackageFile(
        guid: String,
        expiryEpochMinute: Long? = null,
        dataLimitBytes: Long? = null,
    ): java.io.File?

    fun sendMsg2Service(msgId: Int, content: String)
    fun sendMsg2TestService(msg: TestServiceMessage, requestId: String? = null)
    fun cancelAllPing()
    fun testCurrentServerRealPing(requestId: String)

    fun syncSubscriptions()
    fun initAssets()
}
