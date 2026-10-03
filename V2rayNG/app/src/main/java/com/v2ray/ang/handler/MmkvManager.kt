package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.PREF_IS_BOOTED
import com.v2ray.ang.AppConfig.PREF_ROUTING_RULESET
import com.v2ray.ang.AppConfig.TAG
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.dto.entities.GroupLockConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LockEvaluator
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

internal class ProfileStorageException(message: String) : IllegalStateException(message)

object MmkvManager {

    //region private

    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"

    private val recoveryHandler = object : MMKVHandler {
        override fun onMMKVCRCCheckFail(mmapID: String) =
            recoverFromStorageError(mmapID, "CRC check")

        override fun onMMKVFileLengthError(mmapID: String) =
            recoverFromStorageError(mmapID, "file length check")

        override fun wantLogRedirecting(): Boolean = false

        override fun mmkvLog(
            level: MMKVLogLevel,
            file: String,
            line: Int,
            function: String,
            message: String
        ) = Unit
    }

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    private inline fun <T> withProfileIndexLock(block: () -> T): T {
        return synchronized(mainStorage) {
            mainStorage.lock()
            try {
                block()
            } finally {
                mainStorage.unlock()
            }
        }
    }

    private fun removeProfilePayloads(guids: Collection<String>) {
        if (guids.isEmpty()) return
        val keys = guids.toTypedArray()
        profileFullStorage.removeValuesForKeys(keys)
        serverAffStorage.removeValuesForKeys(keys)
        serverRawStorage.removeValuesForKeys(keys)
    }

    private fun requireStorageWrite(success: Boolean, message: String) {
        if (!success) throw ProfileStorageException(message)
    }

    private fun persistServerList(serverList: List<String>, subscriptionId: String): Boolean {
        return mainStorage.encode(serverListKey(subscriptionId), JsonUtil.toJson(serverList))
    }

    private fun serverListKey(subscriptionId: String): String {
        return "$KEY_SUB_SERVER_PREFIX${getSubscriptionId(subscriptionId)}"
    }

    /**
     * Returns every server referenced outside the target group, or null if the raw indexes
     * cannot provide a complete view.
     */
    private fun decodeServersReferencedByOtherGroups(subscriptionId: String): Set<String>? {
        val targetKey = serverListKey(subscriptionId)
        val keys = mainStorage.allKeys() ?: return null
        if (targetKey !in keys) return null

        val referencedServers = mutableSetOf<String>()
        for (key in keys) {
            if (!key.startsWith(KEY_SUB_SERVER_PREFIX) || key == targetKey) continue

            val json = mainStorage.decodeString(key)
            if (json.isNullOrBlank()) return null
            val serverIds = JsonUtil.fromJsonSafe(json, Array<String>::class.java) ?: return null
            referencedServers.addAll(serverIds)
        }
        return referencedServers
    }

    //endregion

    /**
     * Initializes MMKV with best-effort recovery so a damaged store is not silently discarded.
     */
    fun initialize(context: Context) {
        val logLevel = if (BuildConfig.DEBUG) {
            MMKVLogLevel.LevelDebug
        } else {
            MMKVLogLevel.LevelInfo
        }
        MMKV.initialize(
            context,
            context.filesDir.resolve("mmkv").absolutePath,
            null,
            logLevel,
            recoveryHandler
        )
    }

    private fun recoverFromStorageError(mmapID: String, error: String): MMKVRecoverStrategic {
        Log.e(TAG, "MMKV $error failed for $mmapID; attempting data recovery")
        return MMKVRecoverStrategic.OnErrorRecover
    }

    //region Server

    /**
     * Reads the legacy server list from KEY_ANG_CONFIGS for migration.
     * This method is for migration purposes only.
     *
     * @return The JSON string of legacy server list, or null if not exists.
     */
    fun readLegacyServerList(): String? {
        return mainStorage.decodeString(KEY_ANG_CONFIGS)
    }


    /**
     * Gets the selected server GUID.
     *
     * @return The selected server GUID.
     */
    fun getSelectServer(): String? {
        return mainStorage.decodeString(KEY_SELECTED_SERVER)
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        withProfileIndexLock {
            mainStorage.encode(KEY_SELECTED_SERVER, guid)
        }
    }

    /**
     * Encodes the server list for a given subscription.
     * Saves to the subscription's serverList (including default subscription for ungrouped servers).
     *
     * @param serverList The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun encodeServerList(serverList: MutableList<String>, subscriptionId: String) {
        withProfileIndexLock {
            persistServerList(serverList, subscriptionId)
        }
    }


    /**
     * Decodes the server list for a given subscription.
     * If subscriptionId is empty, returns ungrouped servers.
     * Otherwise, returns servers from the specified subscription's serverList.
     *
     * @param subscriptionId The subscription ID.
     * @return The list of server GUIDs.
     */
    fun decodeServerList(subscriptionId: String): MutableList<String> {
        val json = mainStorage.decodeString(serverListKey(subscriptionId))
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Decodes all server list (merged from all subscriptions including default subscription).
     * Use this when you need the complete server list.
     *
     * @return The list of all server GUIDs.
     */
    fun decodeAllServerList(): MutableList<String> {
        val allServers = mutableListOf<String>()
        val subsList = decodeSubsList()

        // If DEFAULT_SUBSCRIPTION_ID is not in the subscriptions list, add its servers
        if (!subsList.contains(DEFAULT_SUBSCRIPTION_ID)) {
            allServers.addAll(decodeServerList(DEFAULT_SUBSCRIPTION_ID))
        }

        // Add servers from all subscriptions
        subsList.forEach { guid ->
            allServers.addAll(decodeServerList(guid))
        }

        return allServers
    }


    /**
     * Decodes the server configuration.
     *
     * @param guid The server GUID.
     * @return The server configuration.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
    }


    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        withProfileIndexLock {
            requireStorageWrite(
                profileFullStorage.encode(key, JsonUtil.toJson(config)),
                "Failed to save profile payload",
            )

            // Use default subscription for servers without subscription
            val subId = getSubscriptionId(config.subscriptionId)
            val serverList = decodeServerList(subId)

            if (!serverList.contains(key)) {
                serverList.add(0, key)
                requireStorageWrite(
                    persistServerList(serverList, subId),
                    "Failed to publish profile index",
                )
                if (getSelectServer().isNullOrBlank()) {
                    requireStorageWrite(
                        mainStorage.encode(KEY_SELECTED_SERVER, key),
                        "Failed to update selected profile",
                    )
                }
            }
        }

        return key
    }

    /**
     * Saves a profile batch before publishing its group index and removing replaced payloads.
     *
     * @param profiles Generated GUIDs and parsed profiles, in insertion order.
     * @param rawConfigs Optional raw configuration payloads keyed by profile GUID.
     * @param subscriptionId The destination subscription ID.
     * @param append Whether to append to the existing group index.
     */
    internal fun saveServerProfiles(
        profiles: Map<String, ProfileItem>,
        rawConfigs: Map<String, String>,
        subscriptionId: String,
        append: Boolean,
    ) {
        if (profiles.isEmpty()) return

        withProfileIndexLock {
            val replacedServers = if (append) {
                emptyList()
            } else {
                decodeServerList(subscriptionId).toList()
            }
            val previousSelection = getSelectServer()
            val selectedProfile = if (!append &&
                previousSelection != null &&
                previousSelection in replacedServers
            ) {
                decodeServerConfig(previousSelection)
            } else {
                null
            }
            val replacementSelection = ProfileReplacement.findSelectedReplacement(
                profiles = profiles,
                currentSelection = previousSelection,
                selectedProfile = selectedProfile,
            )

            profiles.forEach { (guid, profile) ->
                requireStorageWrite(
                    profileFullStorage.encode(guid, JsonUtil.toJson(profile)),
                    "Failed to save profile payload",
                )
                rawConfigs[guid]?.let { raw ->
                    requireStorageWrite(
                        serverRawStorage.encode(guid, raw),
                        "Failed to save raw profile payload",
                    )
                }
            }

            val serverList = if (append) {
                decodeServerList(subscriptionId)
            } else {
                mutableListOf()
            }
            val indexedServers = serverList.toHashSet()
            profiles.keys.forEach { guid ->
                if (indexedServers.add(guid)) {
                    serverList.add(0, guid)
                }
            }
            requireStorageWrite(
                persistServerList(serverList, subscriptionId),
                "Failed to publish profile index",
            )
            replacementSelection?.let { guid ->
                requireStorageWrite(
                    mainStorage.encode(KEY_SELECTED_SERVER, guid),
                    "Failed to update selected profile",
                )
            }
            if (replacedServers.isEmpty()) return@withProfileIndexLock

            val protectedServer = replacementSelection ?: previousSelection
            val referencedByOtherGroups = decodeServersReferencedByOtherGroups(subscriptionId)
            val removablePayloads = ProfileReplacement.findRemovablePayloads(
                replacedServers = replacedServers,
                replacementServers = profiles.keys,
                protectedServer = protectedServer,
                serversReferencedByOtherGroups = referencedByOtherGroups,
            )
            removeProfilePayloads(removablePayloads)
        }
    }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }

        // Get config to determine which subscription to update
        val config = decodeServerConfig(guid)
        val subId = getSubscriptionId(config?.subscriptionId)

        // Remove from appropriate server list
        val serverList = decodeServerList(subId)
        serverList.remove(guid)
        encodeServerList(serverList, subId)

        // Clean up storage
        if (getSelectServer() == guid) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        profileFullStorage.remove(guid)
        serverAffStorage.remove(guid)
    }

    /**
     * Removes the server configurations via subscription ID.
     *
     * @param subscriptionId The subscription ID.
     */
    fun removeServerViaSubid(subscriptionId: String?) {
        val subId = getSubscriptionId(subscriptionId)
        val serverList = decodeServerList(subId)

        // Remove all servers in the list
        serverList.forEach { guid ->
            if (getSelectServer() == guid) {
                mainStorage.remove(KEY_SELECTED_SERVER)
            }
            profileFullStorage.remove(guid)
            serverAffStorage.remove(guid)
        }

        serverList.clear()
        encodeServerList(serverList, subId)
    }

    /**
     * Removes multiple server configurations from a subscription.
     *
     * @param guids The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun removeServers(guids: List<String>, subscriptionId: String) {
        if (guids.isEmpty()) return
        val subId = getSubscriptionId(subscriptionId)
        val serverList = decodeServerList(subId)
        if (serverList.removeAll(guids)) {
            encodeServerList(serverList, subId)
        }

        val selectedServer = getSelectServer()
        guids.forEach { guid ->
            if (selectedServer == guid) {
                mainStorage.remove(KEY_SELECTED_SERVER)
            }
            profileFullStorage.remove(guid)
            serverAffStorage.remove(guid)
            serverRawStorage.remove(guid)
        }
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Sums the consumed bytes of every server in a subscription.
     *
     * Profiles imported from a locked package share one subscription-wide data cap:
     * each profile's quota card shows (and connection enforcement applies) the total
     * consumed across the whole subscription rather than the running profile alone.
     *
     * @param subscriptionId The subscription ID.
     * @return The sum of the consumed-byte counters of all servers in the subscription.
     */
    fun getSubscriptionUsedBytes(subscriptionId: String): Long {
        if (subscriptionId.isBlank()) return 0L
        return decodeServerList(subscriptionId).sumOf { guid ->
            decodeServerAffiliationInfo(guid)?.usedBytes ?: 0L
        }
    }

    /**
     * Encodes the server test delay in milliseconds.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     */
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        keys?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                aff.testDelayMillis = 0
                serverAffStorage.encode(key, JsonUtil.toJson(aff))
            }
        }
    }

    /**
     * Returns whether the server profile is locked against edit and share.
     *
     * @param guid The server GUID.
     * @return True when the profile is locked.
     */
    fun isProfileLocked(guid: String): Boolean {
        return decodeServerAffiliationInfo(guid)?.locked == true
    }

    /**
     * Returns whether the server profile holds a permanent lock imported from a
     * locked share. Such a profile can never be unlocked in the UI.
     *
     * @param guid The server GUID.
     * @return True when the profile is permanently locked.
     */
    fun isProfilePermanentlyLocked(guid: String): Boolean {
        return decodeServerAffiliationInfo(guid)?.persistentLock == true
    }

    /**
     * Marks a server profile as imported from a locked share: it stays locked in the
     * UI and no unlock or lock-editing entry point is offered for it.
     *
     * The expiry moment and data limit shipped in the locked package are stamped on the
     * imported profile, so the recipient inherits the sharer's lock conditions.
     *
     * @param guid The server GUID.
     * @param expiryEpochMinute The lock expiry wall-clock minute to apply, 0 for none.
     * @param dataLimitBytes The lock data-volume limit to apply, 0 for none.
     */
    fun encodeProfileImportedLock(guid: String, expiryEpochMinute: Long = 0L, dataLimitBytes: Long = 0L) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        if (expiryEpochMinute != 0L && expiryEpochMinute != aff.expiryEpochMinute) {
            aff.startEpochMinute = LockEvaluator.todayEpochMinute()
        }
        aff.locked = true
        aff.persistentLock = true
        aff.expiryEpochMinute = expiryEpochMinute
        aff.dataLimitBytes = dataLimitBytes
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Sets the locked state of a server profile.
     *
     * @param guid The server GUID.
     * @param locked Whether the profile is locked.
     */
    fun encodeProfileLocked(guid: String, locked: Boolean) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.locked = locked
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Sets the lock configuration of a server profile, preserving the test delay
     * and already consumed bytes.
     *
     * When the expiry moment changes or is first set, the accounting window for the
     * card's remaining-time indicator restarts at the current minute; saving the same
     * expiry again keeps the original start so elapsed time keeps flowing.
     *
     * @param guid The server GUID.
     */
    fun encodeProfileLockConfig(guid: String, locked: Boolean, expiryEpochMinute: Long, dataLimitBytes: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        if (expiryEpochMinute != 0L && expiryEpochMinute != aff.expiryEpochMinute) {
            aff.startEpochMinute = LockEvaluator.todayEpochMinute()
        }
        aff.locked = locked
        aff.expiryEpochMinute = expiryEpochMinute
        aff.dataLimitBytes = dataLimitBytes
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Consumes [bytes] against a server profile's lock and returns the new usage.
     *
     * @param guid The server GUID.
     * @param bytes The bytes to add.
     * @return The profile's total used bytes after the addition.
     */
    fun addProfileUsedBytes(guid: String, bytes: Long): Long {
        if (guid.isBlank()) {
            return 0L
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.usedBytes += bytes
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
        return aff.usedBytes
    }

    /**
     * Resets the consumed byte counter of a server profile's lock.
     *
     * @param guid The server GUID.
     */
    fun resetProfileUsedBytes(guid: String) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: return
        aff.usedBytes = 0L
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Removes all server configurations.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        profileFullStorage.clearAll()
        serverAffStorage.clearAll()
        serverRawStorage.clearAll()

        decodeSubscriptions().forEach { sub ->
            encodeServerList(mutableListOf(), sub.guid)
        }
        return count
    }

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { aff ->
                if (aff.testDelayMillis < 0L) {
                    removeServer(guid)
                    count++
                }
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    if (aff.testDelayMillis < 0L) {
                        removeServer(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    /**
     * Removes profile payloads that are provably absent from their raw SUB_SERVERS_* index.
     *
     * SUB_IDS and SUB are intentionally ignored: either store can be missing after MMKV
     * recovery while the group indexes still identify live profiles. If any group index or
     * profile payload needed for a decision is unreadable, that data is preserved.
     *
     * @return The number of profile payloads removed, or null if cleanup could not run safely.
     */
    internal fun removeOrphanedServerProfiles(): Int? = synchronized(mainStorage) {
        mainStorage.lock()
        try {
            val indexedServersBySubscription = mainStorage.allKeys().orEmpty()
                .asSequence()
                .filter { key -> key.startsWith(KEY_SUB_SERVER_PREFIX) }
                .associate { key ->
                    val subscriptionId = key.removePrefix(KEY_SUB_SERVER_PREFIX)
                    val json = mainStorage.decodeString(key)
                    val serverIds = if (json.isNullOrBlank()) {
                        null
                    } else {
                        JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toSet()
                    }
                    subscriptionId to serverIds
                }

            val profiles = profileFullStorage.allKeys().orEmpty().map { guid ->
                StoredProfileReference(
                    guid = guid,
                    subscriptionId = decodeServerConfig(guid)?.subscriptionId,
                )
            }
            val orphans = OrphanProfileCleaner.findOrphans(
                profiles = profiles,
                indexedServersBySubscription = indexedServersBySubscription,
                selectedServer = getSelectServer(),
            ) ?: return@synchronized null

            if (orphans.isNotEmpty()) {
                val keys = orphans.toTypedArray()
                profileFullStorage.removeValuesForKeys(keys)
                serverAffStorage.removeValuesForKeys(keys)
                serverRawStorage.removeValuesForKeys(keys)
            }
            orphans.size
        } finally {
            mainStorage.unlock()
        }
    }

    //endregion

    //region Subscriptions

    private fun getSubscriptionId(subscriptionId: String?): String {
        return subscriptionId?.ifEmpty { DEFAULT_SUBSCRIPTION_ID } ?: DEFAULT_SUBSCRIPTION_ID
    }

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) {
            return
        }
        subStorage.allKeys()?.forEach { key ->
            subsList.add(key)
        }
        encodeSubsList(subsList)
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * Removes the subscription.
     *
     * @param subid The subscription ID.
     */
    fun removeSubscription(subid: String) {
        subStorage.remove(subid)
        subStorage.remove(groupLockKey(subid))
        val subsList = decodeSubsList()
        subsList.remove(subid)
        encodeSubsList(subsList)

        removeServerViaSubid(subid)
    }

    /**
     * Encodes the subscription.
     *
     * @param guid The subscription GUID.
     * @param subItem The subscription item.
     */
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))

        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
    }

    /**
     * Stores the password delivered by a locked-package subscription without touching
     * the rest of the subscription item, so a refresh can update it independently of
     * the caller's cached copy.
     *
     * @param subscriptionId The subscription ID; blank means no subscription to update.
     * @param password The password to store; blank is ignored so a plain subscription
     *                 keeps whatever password the user set manually.
     */
    fun applySubscriptionPassword(subscriptionId: String, password: String) {
        if (subscriptionId.isBlank() || password.isBlank()) return
        val sub = decodeSubscription(subscriptionId) ?: return
        if (sub.password == password) return
        sub.password = password
        subStorage.encode(subscriptionId, JsonUtil.toJson(sub))
    }

    private fun groupLockKey(subscriptionId: String): String {
        return "LOCK_${getSubscriptionId(subscriptionId)}"
    }

    /**
     * Decodes the group lock configuration for a subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The group lock configuration (defaults when unset).
     */
    fun decodeGroupLock(subscriptionId: String): GroupLockConfig {
        val json = subStorage.decodeString(groupLockKey(subscriptionId))
        return json?.let { JsonUtil.fromJsonSafe(it, GroupLockConfig::class.java) } ?: GroupLockConfig()
    }

    /**
     * Encodes the group lock configuration for a subscription.
     *
     * @param subscriptionId The subscription ID.
     * @param config The group lock configuration.
     */
    fun encodeGroupLock(subscriptionId: String, config: GroupLockConfig) {
        subStorage.encode(groupLockKey(subscriptionId), JsonUtil.toJson(config))
    }

    /**
     * Adds consumed data to the group lock usage counter.
     *
     * @param subscriptionId The subscription ID.
     * @param bytes The bytes consumed since the last query.
     * @return The updated used-byte counter.
     */
    fun addGroupUsedBytes(subscriptionId: String, bytes: Long): Long {
        if (bytes <= 0L) return decodeGroupLock(subscriptionId).usedBytes
        val lock = decodeGroupLock(subscriptionId)
        lock.usedBytes += bytes
        encodeGroupLock(subscriptionId, lock)
        return lock.usedBytes
    }

    /**
     * Resets the group lock usage counter.
     *
     * @param subscriptionId The subscription ID.
     */
    fun resetGroupUsedBytes(subscriptionId: String) {
        val lock = decodeGroupLock(subscriptionId)
        lock.usedBytes = 0L
        encodeGroupLock(subscriptionId, lock)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            // Keep the first occurrence so a damaged index cannot produce duplicate Compose keys.
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.distinct()?.toMutableList() ?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java) ?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJsonSafe(ruleset, Array<RulesetItem>::class.java)?.toMutableList() ?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?) {
        if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }


    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJsonSafe(json, WebDavConfig::class.java)
    }

    //endregion

    //region Compose helpers for Settings

    /**
     * MMKV-backed String state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvString(
        key: String,
        default: String = ""
    ): MutableState<String> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsString(key, default) ?: default)
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    /**
     * MMKV-backed Boolean state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvBool(
        key: String,
        default: Boolean = false
    ): MutableState<Boolean> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsBool(key, default))
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    //endregion
}
