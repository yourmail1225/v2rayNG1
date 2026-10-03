package com.v2ray.ang.ui.activation

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.GroupLockConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.ActivationErrorKind
import com.v2ray.ang.handler.ActivationManager
import com.v2ray.ang.handler.ActivationOutcome
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RowActivation
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LockedPackage
import com.v2ray.ang.util.LockEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the one-time activation screen. Durable activation state lives in
 * [ActivationManager]; this state is transient UI progress.
 */
data class ActivationUiState(
    val isLoading: Boolean = false,
    val errorResId: Int? = null,
)

class ActivationViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(ActivationUiState())
    val uiState: StateFlow<ActivationUiState> = _uiState.asStateFlow()

    fun activate(code: String, configCode: String?) {
        if (_uiState.value.isLoading) return
        if (code.isBlank()) {
            toastError(R.string.activation_error_denied)
            return
        }
        viewModelScope.launch {
            _uiState.value = ActivationUiState(isLoading = true)
            when (val outcome = ActivationManager.activate(code, configCode)) {
                is ActivationOutcome.Master -> complete(ActivationManager.MODE_MASTER, code.trim(), "")
                is ActivationOutcome.Subscription ->
                    importSubscription(outcome.code, outcome.row)
                is ActivationOutcome.Error -> {
                    _uiState.value = ActivationUiState(errorResId = outcome.kind.toResId())
                }
            }
        }
    }

    /**
     * Installs the fetched row into the app's default subscription group.
     *
     * The row is already read, so activation never creates a second subscription: it
     * reuses [AppConfig.DEFAULT_SUBSCRIPTION_ID] and keeps naming it after the customer,
     * which is what the group header above the configurations reads. It also stores the
     * row's raw URL, which is what makes the subscription updater work later, since it
     * skips any subscription without one, and what a later refresh reads to pick up a
     * changed row. The group's own lock takes the row's expiry and data limit so traffic
     * is charged against the subscription and can be reported back to GitHub.
     */
    private suspend fun importSubscription(code: String, row: String) {
        val subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
        val importedCount = withContext(Dispatchers.IO) {
            // The row's shared quota is copied onto every entry, so the first one carries
            // the group's expiry and limit; a row with no lock block leaves both at 0.
            val quota = LockedPackage.parse(row).entries.firstOrNull()
            val published = RowActivation.read(row)
            // The default group is shared, so a re-activation of a different code would
            // otherwise inherit the previous customer's counter. The row carries the
            // authoritative figure, and the name, for whoever it belongs to.
            MmkvManager.encodeGroupLock(
                subscriptionId,
                GroupLockConfig(
                    enabled = true,
                    expiryEpochMinute = quota?.expiryEpochMinute ?: 0L,
                    startEpochMinute = LockEvaluator.todayEpochMinute(),
                    dataLimitBytes = quota?.dataLimitBytes ?: 0L,
                    usedBytes = published.usedBytes,
                )
            )
            MmkvManager.encodeSubscription(
                subscriptionId,
                SubscriptionItem(
                    remarks = published.username.ifBlank { code },
                    url = ActivationManager.rowUrl(code),
                    autoUpdate = true,
                    updateInterval = AppConfig.SUBSCRIPTION_ACTIVATED_UPDATE_INTERVAL_MINUTES,
                )
            )
            AngConfigManager.importBatchConfig(row, subscriptionId, append = false).first
        }
        if (importedCount > 0) {
            SubscriptionUpdater.syncOne(subId = subscriptionId)
            SettingsChangeManager.makeSetupGroupTab()
            complete(ActivationManager.MODE_CODE, code, subscriptionId)
        } else {
            _uiState.value = ActivationUiState(errorResId = R.string.activation_error_generic)
        }
    }

    private fun complete(mode: String, code: String, subscriptionId: String) {
        ActivationManager.markActivated(mode, code, subscriptionId)
        _uiState.value = ActivationUiState()
        if (subscriptionId.isNotBlank()) {
            ActivationManager.reportActivation(code, subscriptionId)
        }
        toastSuccess(R.string.activation_success)
        finishActivity()
    }
}

internal fun ActivationErrorKind.toResId(): Int = when (this) {
    ActivationErrorKind.NETWORK -> R.string.activation_error_network
    ActivationErrorKind.LIMIT -> R.string.activation_error_limit
    ActivationErrorKind.DENIED -> R.string.activation_error_denied
    ActivationErrorKind.NO_WRITE_TOKEN -> R.string.activation_error_no_write_token
    else -> R.string.activation_error_generic
}

class ActivationViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return ActivationViewModel(application) as T
    }
}