package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.isOpenVpnConfig
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.V2rayNFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LockedPackage
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.URI

object AngConfigManager {

    private data class ParsedProfile(
        val profile: ProfileItem,
        val rawConfig: String? = null,
        val locked: Boolean = false,
        val expiryEpochMinute: Long = 0L,
        val dataLimitBytes: Long = 0L,
    )

    // Parser mapping for different config types (lazy initialized)
    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
        )
    }

    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val config = MmkvManager.decodeServerConfig(guid) ?: return -1
            if (MmkvManager.isProfileLocked(guid)) return -1
            if (MmkvManager.decodeGroupLock(config.subscriptionId).enabled) return -1
            // An OpenVPN profile shares its raw .ovpn content; the v2ray config builder
            // does not understand it.
            if (config.configType == EConfigType.OPENVPN) {
                val raw = MmkvManager.decodeServerRaw(guid)
                if (raw.isNullOrEmpty()) return -1
                Utils.setClipboard(context, raw)
                return 0
            }
            val result = CoreConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        if (MmkvManager.isProfileLocked(guid)) {
            return ""
        }
        val config = MmkvManager.decodeServerConfig(guid)
        // A config in a group with an active lock cannot be exported through the
        // generic share paths; it is only movable as a locked package.
        if (config != null && MmkvManager.decodeGroupLock(config.subscriptionId).enabled) {
            return ""
        }
        return shareConfigContent(guid)
    }

    /**
     * Returns the share text of a configuration, bypassing the locked-state guard.
     * Used only to build a locked package for exporting the profile to another device.
     */
    private fun shareConfigContent(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            // An OpenVPN profile shares its raw .ovpn text; it has no URI encoding.
            if (config.configType == EConfigType.OPENVPN) {
                return MmkvManager.decodeServerRaw(guid).orEmpty()
            }

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        return try {
            val pending = mutableListOf<ParsedProfile>()
            var importText = server
            var packagePassword = ""
            if (server != null) {
                val lockedParsed = LockedPackage.parse(server)
                if (lockedParsed.entries.isNotEmpty()) {
                    lockedParsed.entries.forEach { entry ->
                        parseLockedEntry(entry, subid, pending)
                    }
                    importText = lockedParsed.remaining
                }
                packagePassword = lockedParsed.password
            }

            // OpenVPN .ovpn auto-detection & import
            if (importText.isOpenVpnConfig()) {
                val profile = ProfileItem(
                    configType = EConfigType.OPENVPN,
                    subscriptionId = subid,
                    remarks = "OpenVPN",
                )
                pending.add(ParsedProfile(profile = profile, rawConfig = importText))
                importText = ""
            }

            var count = parseBatchConfig(Utils.decode(importText), subid).also { pending.addAll(it) }.size
            if (count <= 0) {
                count = parseBatchConfig(importText, subid).also { pending.addAll(it) }.size
            }
            if (count <= 0) {
                count = parseCustomConfigServer(importText, subid).also { pending.addAll(it) }.size
            }

            if (pending.isNotEmpty()) {
                commitProfiles(pending, subid, append)
            }

            // A password shipped in the locked package protects the subscription from
            // being changed or removed without it; store it on the owning subscription.
            if (packagePassword.isNotEmpty() && subid.isNotBlank()) {
                MmkvManager.applySubscriptionPassword(subid, packagePassword)
            }

            var countSub = parseBatchSubscription(importText)
            if (countSub <= 0) {
                countSub = parseBatchSubscription(Utils.decode(importText))
            }
            if (countSub > 0) {
                updateConfigViaSubAll()
            }

            pending.size to countSub
        } catch (e: ProfileStorageException) {
            LogUtil.e(AppConfig.TAG, "Failed to store imported profiles", e)
            0 to 0
        }
    }

    /**
     * Appends the profiles of one locked-package entry to [out] without persisting.
     *
     * The entry's lock conditions (expiry moment and data limit) are stamped on the
     * parsed profiles so the recipient inherits the sharer's lock settings. Callers
     * accumulate every entry of a package and commit the batch once, so a multi-entry
     * package survives a replace-style save.
     *
     * @param entry The locked-package entry.
     * @param subid The subscription ID.
     * @param out The accumulator for parsed profiles.
     * @return The number of profiles appended.
     */
    private fun parseLockedEntry(
        entry: LockedPackage.LockedEntry,
        subid: String,
        out: MutableList<ParsedProfile>,
    ): Int {
        if (entry.content.isOpenVpnConfig()) {
            val profile = ProfileItem(
                configType = EConfigType.OPENVPN,
                subscriptionId = subid,
                remarks = "OpenVPN",
            )
            out.add(
                ParsedProfile(
                    profile = profile,
                    rawConfig = entry.content,
                    locked = true,
                    expiryEpochMinute = entry.expiryEpochMinute,
                    dataLimitBytes = entry.dataLimitBytes,
                )
            )
            return 1
        }
        var parsed = parseBatchConfig(
            Utils.decode(entry.content),
            subid,
            locked = true,
            expiryEpochMinute = entry.expiryEpochMinute,
            dataLimitBytes = entry.dataLimitBytes,
        )
        if (parsed.isEmpty()) {
            parsed = parseBatchConfig(
                entry.content,
                subid,
                locked = true,
                expiryEpochMinute = entry.expiryEpochMinute,
                dataLimitBytes = entry.dataLimitBytes,
            )
        }
        if (parsed.isEmpty()) {
            parsed = parseCustomConfigServer(
                entry.content,
                subid,
                locked = true,
                expiryEpochMinute = entry.expiryEpochMinute,
                dataLimitBytes = entry.dataLimitBytes,
            )
        }
        out.addAll(parsed)
        return parsed.size
    }

    /**
     * Exports every locked profile in [serverList] as a locked package.
     *
     * Each entry carries the lock conditions (expiry moment and data limit) currently
     * applied on the originating profile, so a batch export preserves them too.
     *
     * @param serverList The list of server GUIDs.
     * @return The encoded locked package text, or an empty string when nothing is locked.
     */
    fun exportLockedPackage(serverList: List<String>): String {
        val entries = serverList.mapNotNull { guid ->
            if (!MmkvManager.isProfileLocked(guid)) return@mapNotNull null
            val content = shareConfigContent(guid)
            if (content.isBlank()) return@mapNotNull null
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            LockedPackage.LockedEntry(
                content = content,
                expiryEpochMinute = aff?.expiryEpochMinute ?: 0L,
                dataLimitBytes = aff?.dataLimitBytes ?: 0L,
            )
        }
        return LockedPackage.encode(entries)
    }

    /**
     * Returns the locked-package text for a single profile, or an empty string when the
     * profile is missing or has no shareable content. The payload stays
     * exportable-but-locked: importing it re-imports the profile with a permanent lock
     * that the recipient cannot unlock, no matter the lock state on this device.
     *
     * The lock conditions applied on the profile are embedded in the package unless the
     * caller overrides them, so the recipient inherits the same expiry and data limit.
     *
     * @param guid The GUID of the configuration.
     * @param expiryEpochMinuteOverride The expiry wall-clock minute to ship, or null to
     *                                  use the profile's stored value.
     * @param dataLimitBytesOverride The data-volume limit to ship, or null to use the
     *                               profile's stored value.
     */
    fun shareLockedConfig(
        guid: String,
        expiryEpochMinuteOverride: Long? = null,
        dataLimitBytesOverride: Long? = null,
    ): String {
        val content = shareConfigContent(guid)
        if (content.isBlank()) return ""
        val aff = MmkvManager.decodeServerAffiliationInfo(guid)
        return LockedPackage.encode(
            listOf(
                LockedPackage.LockedEntry(
                    content = content,
                    expiryEpochMinute = expiryEpochMinuteOverride ?: (aff?.expiryEpochMinute ?: 0L),
                    dataLimitBytes = dataLimitBytesOverride ?: (aff?.dataLimitBytes ?: 0L),
                )
            )
        )
    }

    /**
     * Copies the locked-package text of a single locked profile to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @param expiryEpochMinuteOverride The expiry wall-clock minute to ship, or null to
     *                                  use the profile's stored value.
     * @param dataLimitBytesOverride The data-volume limit to ship, or null to use the
     *                               profile's stored value.
     * @return The result code; 0 on success.
     */
    fun shareLocked2Clipboard(
        context: Context,
        guid: String,
        expiryEpochMinuteOverride: Long? = null,
        dataLimitBytesOverride: Long? = null,
    ): Int {
        try {
            val conf = shareLockedConfig(guid, expiryEpochMinuteOverride, dataLimitBytesOverride)
            if (TextUtils.isEmpty(conf)) return -1

            Utils.setClipboard(context, conf)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share locked config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Creates a QR code bitmap from the locked-package text of a locked profile.
     *
     * @param guid The GUID of the configuration.
     * @param expiryEpochMinuteOverride The expiry wall-clock minute to ship, or null to
     *                                  use the profile's stored value.
     * @param dataLimitBytesOverride The data-volume limit to ship, or null to use the
     *                               profile's stored value.
     * @return The QR code bitmap, or null when the profile cannot be shared as locked.
     */
    fun shareLocked2QRCode(
        guid: String,
        expiryEpochMinuteOverride: Long? = null,
        dataLimitBytesOverride: Long? = null,
    ): Bitmap? {
        try {
            val conf = shareLockedConfig(guid, expiryEpochMinuteOverride, dataLimitBytesOverride)
            if (TextUtils.isEmpty(conf)) return null
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share locked config as QR code", e)
            return null
        }
    }

    /**
     * Writes the locked-package text of a locked profile to a cache file for sharing.
     * Callers must run this off the main thread.
     *
     * @param context The context; the file is written under its cache directory.
     * @param guid The GUID of the configuration.
     * @param expiryEpochMinuteOverride The expiry wall-clock minute to ship, or null to
     *                                  use the profile's stored value.
     * @param dataLimitBytesOverride The data-volume limit to ship, or null to use the
     *                               profile's stored value.
     * @return The created file, or null when the profile cannot be shared as locked.
     */
    fun writeLockedPackageFile(
        context: Context,
        guid: String,
        expiryEpochMinuteOverride: Long? = null,
        dataLimitBytesOverride: Long? = null,
    ): File? {
        return try {
            val conf = shareLockedConfig(guid, expiryEpochMinuteOverride, dataLimitBytesOverride)
            if (TextUtils.isEmpty(conf)) return null
            val file = File(context.cacheDir, "locked-config-$guid.txt")
            file.writeText(conf)
            file
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to write locked config file", e)
            null
        }
    }
    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    /**
     * Parses a batch of configurations without persisting.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param locked Whether the parsed profiles are lock-imported.
     * @param expiryEpochMinute The lock expiry wall-clock minute to apply, 0 for none.
     * @param dataLimitBytes The lock data-volume limit to apply, 0 for none.
     * @return The parsed profiles, or an empty list when nothing parsed.
     */
    private fun parseBatchConfig(
        servers: String?,
        subid: String,
        locked: Boolean = false,
        expiryEpochMinute: Long = 0L,
        dataLimitBytes: Long = 0L,
    ): List<ParsedProfile> {
        try {
            if (servers == null) {
                return emptyList()
            }
            val subItem = MmkvManager.decodeSubscription(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            val v2raynLines = mutableListOf<String>()

            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    if (it.startsWith(AppConfig.V2RAYNFMTS, ignoreCase = true)) {
                        v2raynLines.add(it)
                    } else {
                        val config = parseConfig(it, subid, subItem)
                        if (config != null) {
                            configs.add(config)
                        }
                    }
                }

            val v2raynConfigs = V2rayNFmt.parse(v2raynLines, subid)
            val allConfigs = v2raynConfigs + configs

            return allConfigs.map { parsed ->
                ParsedProfile(
                    profile = parsed,
                    locked = locked,
                    expiryEpochMinute = expiryEpochMinute,
                    dataLimitBytes = dataLimitBytes,
                )
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return emptyList()
    }

    /**
     * Commits parsed profiles before removing the profiles they replace.
     *
     * @param configs The parsed profiles to save.
     * @param subid The subscription ID.
     * @param append Whether to append to the existing server list.
     */
    private fun commitProfiles(
        configs: List<ParsedProfile>,
        subid: String,
        append: Boolean,
    ) {
        val keyToProfile = linkedMapOf<String, ProfileItem>()
        val rawConfigs = mutableMapOf<String, String>()
        val importedLocks = mutableListOf<Pair<String, Pair<Long, Long>>>()

        configs.forEach { parsed ->
            val key = Utils.getUuid()
            keyToProfile[key] = parsed.profile
            parsed.rawConfig?.let { raw -> rawConfigs[key] = raw }
            if (parsed.locked) importedLocks.add(key to (parsed.expiryEpochMinute to parsed.dataLimitBytes))
        }

        MmkvManager.saveServerProfiles(
            profiles = keyToProfile,
            rawConfigs = rawConfigs,
            subscriptionId = subid,
            append = append,
        )
        importedLocks.forEach { (key, settings) ->
            // Profiles imported from a locked share keep a permanent lock that the UI
            // cannot unlock; the sharer's own lock stays editable on the originating
            // device because it is never written through this import path. The package's
            // lock conditions are re-applied so the recipient inherits the same expiry
            // and data limit.
            MmkvManager.encodeProfileImportedLock(key, settings.first, settings.second)
        }
    }

    /**
     * Parses a custom configuration server without persisting.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param locked Whether the parsed profiles are lock-imported.
     * @param expiryEpochMinute The lock expiry wall-clock minute to apply, 0 for none.
     * @param dataLimitBytes The lock data-volume limit to apply, 0 for none.
     * @return The parsed profiles, or an empty list when nothing parsed.
     */
    private fun parseCustomConfigServer(
        server: String?,
        subid: String,
        locked: Boolean = false,
        expiryEpochMinute: Long = 0L,
        dataLimitBytes: Long = 0L,
    ): List<ParsedProfile> {
        if (server == null) {
            return emptyList()
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    return serverList.reversed().map { srv ->
                        val config = CustomFmt.parse(JsonUtil.toJson(srv))
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        ParsedProfile(
                            profile = config,
                            rawConfig = JsonUtil.toJsonPretty(srv) ?: "",
                            locked = locked,
                            expiryEpochMinute = expiryEpochMinute,
                            dataLimitBytes = dataLimitBytes,
                        )
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                return listOf(ParsedProfile(config, server, locked, expiryEpochMinute, dataLimitBytes))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return emptyList()
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                return listOf(ParsedProfile(config, server, locked, expiryEpochMinute, dataLimitBytes))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return emptyList()
        } else {
            return emptyList()
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
                if (str.startsWith(scheme)) parser(str) else null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            LogUtil.i(AppConfig.TAG, url)
            val userAgent = it.subscription.userAgent
            val requestHeaders = it.subscription.requestHeaders
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()

            var configText = try {
                val httpPort = SettingsManager.getHttpPort()
                HttpUtil.getUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = url,
                        userAgent = userAgent,
                        requestHeaders = requestHeaders,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
                ""
            }
            if (configText.isEmpty()) {
                configText = try {
                    HttpUtil.getUrlContentWithUserAgent(
                        UrlContentRequest(
                            url = url,
                            userAgent = userAgent,
                            requestHeaders = requestHeaders
                        )
                    )
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e)
                    ""
                }
            }
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                // A password shipped by a locked-package row protects the subscription
                // from being changed or removed without it. Re-parse only for the
                // password; profile parsing already happened in parseConfigViaSub.
                val lockedParsed = LockedPackage.parse(configText)
                if (lockedParsed.password.isNotEmpty()) {
                    MmkvManager.applySubscriptionPassword(it.guid, lockedParsed.password)
                }
                LogUtil.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Removes invalid server configurations for a subscription.
     *
     * @param subId The subscription ID.
     */
    fun removeInvalidServer(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        val invalidServers = serverList.filter {
            val aff = MmkvManager.decodeServerAffiliationInfo(it)
            aff != null && aff.testDelayMillis < 0L
        }
        MmkvManager.removeServers(invalidServers, subId)
    }

    /**
     * Sorts servers by test results for a subscription.
     *
     * @param subId The subscription ID.
     */
    fun sortByTestResultsForSub(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        if (serverList.isEmpty()) return

        val sorted = serverList
            .map { guid ->
                val delay =
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                guid to if (delay <= 0L) Long.MAX_VALUE else delay
            }
            .sortedBy { it.second }
            .map { it.first }
            .toMutableList()
        MmkvManager.encodeServerList(sorted, subId)
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        val pending = mutableListOf<ParsedProfile>()
        var importText = server
        if (server != null) {
            val lockedParsed = LockedPackage.parse(server)
            if (lockedParsed.entries.isNotEmpty()) {
                lockedParsed.entries.forEach { entry ->
                    parseLockedEntry(entry, subid, pending)
                }
                importText = lockedParsed.remaining
            }
        }

        var count = parseBatchConfig(Utils.decode(importText), subid).also { pending.addAll(it) }.size
        if (count <= 0) {
            count = parseBatchConfig(importText, subid).also { pending.addAll(it) }.size
        }
        if (count <= 0) {
            count = parseCustomConfigServer(importText, subid).also { pending.addAll(it) }.size
        }
        if (pending.isNotEmpty()) {
            commitProfiles(pending, subid, append)
        }
        return pending.size
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
