package com.v2ray.ang.ui.activation

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.base.BaseViewModelEvent
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import kotlinx.coroutines.launch

class ActivationActivity : BaseComponentActivity() {

    private val viewModel: ActivationViewModel by viewModels {
        ActivationViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.viewModelEvent.collect { event ->
                if (event is BaseViewModelEvent.FinishActivity) {
                    finish()
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        ActivationScreen(
            uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
            onBackClick = { finish() },
            onActivate = { code, configCode -> viewModel.activate(code, configCode) },
        )
    }
}

@Composable
private fun ActivationScreen(
    uiState: ActivationUiState,
    onBackClick: () -> Unit,
    onActivate: (String, String?) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var configCode by rememberSaveable { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.activation_title),
                onBackClick = onBackClick,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            FormTextField(
                label = stringResource(R.string.activation_code_field),
                value = code,
                onValueChange = { code = it },
            )
            FormTextField(
                label = stringResource(R.string.activation_config_field),
                value = configCode,
                onValueChange = { configCode = it },
            )
            uiState.errorResId?.let { resId ->
                Text(
                    text = stringResource(resId),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onActivate(code, configCode) },
                enabled = !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(text = stringResource(R.string.activation_submit))
                }
            }
            NavigationBarsSpacer()
        }
    }
}