package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Group lock editor state, opened from the more menu for the selected subscription.
 */
data class GroupLockEditorUi(
    val groupId: String,
    val groupName: String,
    val enabled: Boolean = false,
    val expiryEpochMinute: Long = 0L,
    val dataLimitBytes: Long = 0L,
    val usedBytes: Long = 0L,
)

/**
 * Profile lock editor state, opened from the lock affordance of a server row.
 */
data class ProfileLockEditorUi(
    val guid: String,
    val serverName: String,
    val enabled: Boolean = false,
    val expiryEpochMinute: Long = 0L,
    val dataLimitBytes: Long = 0L,
    val usedBytes: Long = 0L,
)

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val lockNotice: String? = null,
    val groupLockEditor: GroupLockEditorUi? = null,
    val profileLockEditor: ProfileLockEditorUi? = null
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data object ImportOpenVpnFile : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction

    /**
     * Shares a profile as a locked package. The optional overrides let the lock editor
     * ship the expiry and data limit currently being edited, before they are saved;
     * when null the profile's stored lock conditions are used.
     */
    data class ShareLockedQRCode(
        val guid: String,
        val expiryEpochMinute: Long? = null,
        val dataLimitBytes: Long? = null,
    ) : MainAction

    data class ShareLockedClipboard(
        val guid: String,
        val expiryEpochMinute: Long? = null,
        val dataLimitBytes: Long? = null,
    ) : MainAction

    data class ShareLockedFile(
        val guid: String,
        val expiryEpochMinute: Long? = null,
        val dataLimitBytes: Long? = null,
    ) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data class ToggleProfileLock(val guid: String) : MainAction
    data object ExportLocked : MainAction
    data class OpenGroupLockEditor(val groupId: String) : MainAction
    data class SaveGroupLock(
        val groupId: String,
        val enabled: Boolean,
        val expiryEpochMinute: Long,
        val dataLimitBytes: Long,
    ) : MainAction
    data class ResetGroupData(val groupId: String) : MainAction
    data object DismissGroupLockEditor : MainAction
    data class OpenProfileLockEditor(val guid: String) : MainAction
    data class SaveProfileLock(
        val guid: String,
        val enabled: Boolean,
        val expiryEpochMinute: Long,
        val dataLimitBytes: Long,
    ) : MainAction
    data class ResetProfileUsedBytes(val guid: String) : MainAction
    data object DismissProfileLockEditor : MainAction
    data object DismissLockNotice : MainAction

    data object LocateHandled : MainAction
}
