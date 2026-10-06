package com.v2ray.ang.service

import android.content.Context
import android.content.Intent
import com.v2ray.ang.util.LogUtil

/**
 * Routes app-initiated VPN commands to the engine that owns the current run mode.
 *
 * Only the OpenVPN engine is dispatched through this object. The V2Ray/Xray engine
 * keeps its existing path through [CoreVpnService] and [CoreServiceManager], so the
 * dispatcher never re-targets a non-OpenVPN profile.
 */
object VpnCoreDispatcher {
    private const val TAG = "VpnCoreDispatcher"

    fun startService(
        context: Context,
        serverType: String,
        configData: String,
        username: String? = null,
        password: String? = null,
    ) {
        if (serverType.equals("openvpn", ignoreCase = true)) {
            LogUtil.i(TAG, "Routing to OpenVpnCoreService (OpenVPN 3 Native C++)")
            val intent = Intent(context, OpenVpnCoreService::class.java).apply {
                putExtra(OpenVpnCoreService.EXTRA_CONFIG, configData)
                if (!username.isNullOrEmpty()) {
                    putExtra(OpenVpnCoreService.EXTRA_USERNAME, username)
                }
                if (!password.isNullOrEmpty()) {
                    putExtra(OpenVpnCoreService.EXTRA_PASSWORD, password)
                }
            }
            context.startForegroundService(intent)
        } else {
            LogUtil.w(TAG, "Unsupported serverType=$serverType, ignoring dispatch")
        }
    }

    fun stopService(context: Context, serverType: String? = null) {
        if (serverType != null && !serverType.equals("openvpn", ignoreCase = true)) {
            LogUtil.w(TAG, "Unsupported serverType=$serverType, ignoring stop dispatch")
            return
        }
        LogUtil.i(TAG, "Stopping OpenVpnCoreService")
        val stopIntent = Intent(context, OpenVpnCoreService::class.java).apply {
            action = OpenVpnCoreService.ACTION_STOP
        }
        context.startService(stopIntent)
    }
}