package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField

/**
 * Editor for OpenVPN .ovpn profiles. The raw config is edited directly on disk-backed
 * text plus username/password pair prompted through a popup dialog; the saved values
 * are passed to the engine's management prompt on the next connect.
 */
class ServerOpenVpnActivity : BaseComponentActivity() {

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }

    private var initialRemarks: String = ""
    private var initialContent: String = ""
    private var initialUsername: String = ""
    private var initialPassword: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = MmkvManager.decodeServerConfig(editGuid)
        initialRemarks = config?.remarks ?: ""
        initialContent = MmkvManager.decodeServerRaw(editGuid).orEmpty()
        initialUsername = config?.username.orEmpty()
        initialPassword = config?.password.orEmpty()
    }

    @Composable
    override fun ScreenContent() {
        var showCredentialsDialog by rememberSaveable { mutableStateOf(false) }
        var username by rememberSaveable { mutableStateOf(initialUsername) }
        var password by rememberSaveable { mutableStateOf(initialPassword) }

        ServerCustomConfigScreen(
            editGuid = editGuid,
            isRunning = isRunning,
            initialRemarks = initialRemarks,
            initialContent = initialContent,
            title = getString(R.string.menu_item_import_config_manually_openvpn),
            editorPlaceholder = "# OpenVPN .ovpn configuration",
            onBackClick = { finish() },
            onSave = { remarks, content -> saveServer(remarks, content, username, password) },
            onDelete = { deleteServer() },
            credentialsAction = {
                IconButton(onClick = { showCredentialsDialog = true }) {
                    Icon(
                        painterResource(R.drawable.ic_lock_24dp),
                        contentDescription = stringResource(R.string.openvpn_credentials_title)
                    )
                }
            }
        )

        if (showCredentialsDialog) {
            InputDialog(
                title = stringResource(R.string.openvpn_credentials_title),
                fields = listOf(
                    InputField(label = stringResource(R.string.openvpn_username), value = username),
                    InputField(
                        label = stringResource(R.string.openvpn_password),
                        value = password,
                        visualTransformation = PasswordVisualTransformation()
                    )
                ),
                onFieldChange = { index, value ->
                    if (index == 0) username = value else password = value
                },
                confirmText = stringResource(R.string.action_ok),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = { showCredentialsDialog = false },
                onDismiss = { showCredentialsDialog = false }
            )
        }
    }

    private fun saveServer(
        remarks: String,
        content: String,
        username: String?,
        password: String?
    ): Boolean {
        if (remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return false
        }
        if (content.isBlank()) {
            toast(R.string.toast_config_file_invalid)
            return false
        }

        val config =
            MmkvManager.decodeServerConfig(editGuid)
                ?: ProfileItem.create(EConfigType.OPENVPN)

        config.remarks = remarks
        config.server = null
        config.serverPort = null
        config.username = username?.takeIf { it.isNotBlank() }?.trim()
        config.password = password?.takeIf { it.isNotBlank() }
        config.description = AngConfigManager.generateDescription(config)

        val savedGuid = MmkvManager.encodeServerConfig(
            editGuid,
            config
        )

        MmkvManager.encodeServerRaw(
            savedGuid,
            content
        )

        toastSuccess(R.string.toast_success)

        ProfileEditorResult.run {
            finishSaved(
                guid = savedGuid,
                restartService = isRunning
            )
        }

        return true
    }

    private fun deleteServer(): Boolean {
        if (editGuid.isEmpty()) {
            return false
        }

        if (editGuid == MmkvManager.getSelectServer()) {
            toast(R.string.toast_action_not_allowed)
            return false
        }

        MmkvManager.removeServer(editGuid)

        ProfileEditorResult.run {
            finishDeleted(editGuid)
        }

        return true
    }
}