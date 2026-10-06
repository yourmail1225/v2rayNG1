package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.util.LockEvaluator

/** Shows the reason a locked group refused a connection. */
@Composable
fun LockDeniedNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        message = message,
        dismissText = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss
    )
}

/** Edits the expiry moment and data-volume limit of a subscription-group lock. */
@Composable
fun GroupLockEditorDialog(
    editor: GroupLockEditorUi,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit,
    onReset: () -> Unit,
) {
    LockEditorDialog(
        title = { Text(editor.groupName) },
        enabledLabel = stringResource(R.string.lock_group_enable),
        editorKey = editor.groupId,
        enabled = editor.enabled,
        expiryEpochMinute = editor.expiryEpochMinute,
        dataLimitBytes = editor.dataLimitBytes,
        usedBytes = editor.usedBytes,
        onDismiss = onDismiss,
        onSave = onSave,
        onReset = onReset,
    )
}

/** Edits the expiry moment and data-volume limit of a server-profile lock. */
@Composable
fun ProfileLockEditorDialog(
    editor: ProfileLockEditorUi,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit,
    onReset: () -> Unit,
    onShareLink: ((expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit)? = null,
    onShareQRCode: ((expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit)? = null,
) {
    LockEditorDialog(
        title = { Text(editor.serverName) },
        enabledLabel = stringResource(R.string.lock_profile_enable),
        editorKey = editor.guid,
        enabled = editor.enabled,
        expiryEpochMinute = editor.expiryEpochMinute,
        dataLimitBytes = editor.dataLimitBytes,
        usedBytes = editor.usedBytes,
        onDismiss = onDismiss,
        onSave = onSave,
        onReset = onReset,
        onShareLink = onShareLink,
        onShareQRCode = onShareQRCode,
    )
}

@Composable
private fun LockEditorDialog(
    title: @Composable () -> Unit,
    enabledLabel: String,
    editorKey: String,
    enabled: Boolean,
    expiryEpochMinute: Long,
    dataLimitBytes: Long,
    usedBytes: Long,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit,
    onReset: () -> Unit,
    onShareLink: ((expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit)? = null,
    onShareQRCode: ((expiryEpochMinute: Long, dataLimitBytes: Long) -> Unit)? = null,
) {
    var enabledState by rememberSaveable(editorKey) { mutableStateOf(enabled) }
    var expiryText by rememberSaveable(editorKey) {
        mutableStateOf(
            LockEvaluator.formatEpochMinute(
                if (expiryEpochMinute != 0L) expiryEpochMinute
                else LockEvaluator.todayEpochMinute() + LockEvaluator.DEFAULT_LOCK_MINUTES
            )
        )
    }
    var limitMbText by rememberSaveable(editorKey) {
        mutableStateOf((dataLimitBytes / MB).takeIf { it > 0L }?.toString().orEmpty())
    }

    // Lock conditions as currently edited, shared with both the save button and the
    // lock-link/lock-QR share buttons so a shared package carries the values the user
    // is about to apply, even before they hit save.
    val currentExpiry = LockEvaluator.parseEpochMinute(expiryText)
    val currentLimitBytes = (limitMbText.toLongOrNull() ?: 0L).let { limitMb ->
        if (limitMb > 0L) {
            try {
                Math.multiplyExact(limitMb, MB)
            } catch (e: ArithmeticException) {
                0L
            }
        } else {
            0L
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { title() },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .consumeWindowInsets(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSwitchItem(
                    title = enabledLabel,
                    checked = enabledState,
                    onCheckedChange = { enabledState = it }
                )
                OutlinedTextField(
                    value = expiryText,
                    onValueChange = { expiryText = it },
                    label = { Text(stringResource(R.string.lock_group_expiry_label)) },
                    placeholder = { Text(stringResource(R.string.lock_group_expiry_placeholder)) },
                    singleLine = true,
                    colors = lockTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limitMbText,
                    onValueChange = { limitMbText = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(R.string.lock_group_data_limit_label)) },
                    placeholder = { Text(stringResource(R.string.lock_group_data_limit_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = lockTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.lock_group_used_of_limit,
                        mb(usedBytes),
                        mb(dataLimitBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onShareLink != null || onShareQRCode != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        onShareLink?.let {
                            TextButton(
                                onClick = { it(currentExpiry, currentLimitBytes) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.lock_share_locked_link))
                            }
                        }
                        onShareQRCode?.let {
                            TextButton(
                                onClick = { it(currentExpiry, currentLimitBytes) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.lock_share_locked_qrcode))
                            }
                        }
                    }
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.action_reset))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(enabledState, currentExpiry, currentLimitBytes) }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

private const val MB = 1_048_576L

private fun mb(bytes: Long): String {
    val value = bytes / MB
    return if (value > 0L) value.toString() else "0"
}

@Composable
private fun lockTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.secondary
)