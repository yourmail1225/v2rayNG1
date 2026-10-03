package com.v2ray.ang.handler

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CustomUpdateInfo(
    val hasUpdate: Boolean = false,
    val version: String = "",
    val downloadUrl: String = "",
    val message: String = ""
)

object CustomUpdateManager {
    private const val UPDATE_API_URL = "YOUR_PYTHON_API_URL_HERE/api/update"
    
    suspend fun checkForUpdate(): CustomUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()
            
            var response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = UPDATE_API_URL,
                    timeout = 5000
                )
            )
            
            if (response.isNullOrEmpty()) {
                val httpPort = SettingsManager.getHttpPort()
                response = HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = UPDATE_API_URL,
                        timeout = 5000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            }
            
            if (response.isNullOrEmpty()) {
                return@withContext CustomUpdateInfo(hasUpdate = false)
            }
            
            val updateInfo = JsonUtil.fromJsonSafe(response, CustomUpdateInfo::class.java)
            return@withContext updateInfo ?: CustomUpdateInfo(hasUpdate = false)
        } catch (e: Exception) {
            LogUtil.e("CustomUpdateManager", "Failed to check for update: ${e.message}")
            return@withContext CustomUpdateInfo(hasUpdate = false)
        }
    }
}
