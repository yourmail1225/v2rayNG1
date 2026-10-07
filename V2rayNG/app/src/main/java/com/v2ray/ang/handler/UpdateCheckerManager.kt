package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.AppUpdateNotice
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object UpdateCheckerManager {

    /** File the panel publishes at the repository root for the customer app. */
    private const val PANEL_UPDATE_FILE = "update.json"

    /**
     * Update notice the owner published through the panel, or null when the panel
     * offers none, the file cannot be read, or it is not newer than this build.
     *
     * A failure is logged and treated as "no update" so an unreachable repository
     * never blocks the app from starting.
     */
    suspend fun checkPanelUpdate(): AppUpdateNotice? = withContext(Dispatchers.IO) {
        // Allow checking the panel's published update.json even when the device
        // has not been activated. A missing or unreachable URL is treated as
        // "no update" so this never blocks startup.
        // Previously the check returned null when not activated, which prevented
        // owners from publishing an update notice visible before activation.
        // (Activation gating was intentional but made update delivery confusing.)
        val url = ActivationManager.repoRootUrl() + PANEL_UPDATE_FILE
        val response = try {
            HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 5000))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Panel update check failed", e)
            return@withContext null
        }
        if (response.isNullOrEmpty()) return@withContext null
        val notice = JsonUtil.fromJsonSafe(response, AppUpdateNotice::class.java)
        if (notice == null || !notice.isNewerThan(BuildConfig.VERSION_NAME)) {
            return@withContext null
        }
        LogUtil.i(AppConfig.TAG, "Panel published version: ${notice.version}")
        notice
    }

    /**
     * Downloads [notice] into the app's own cache directory and returns the file, or
     * null when the download failed. The cache path is what the app's FileProvider
     * already exposes, so the downloaded APK can be handed to the system installer
     * without granting any world-readable location.
     *
     * Must run off the main thread.
     */
    fun downloadPanelUpdate(notice: AppUpdateNotice, context: Context): File? {
        val target = File(context.cacheDir, "app-update.apk")
        val ok = HttpUtil.downloadToFile(
            UrlContentRequest(url = notice.url, timeout = 60_000),
            target
        )
        if (!ok) {
            target.delete()
            return null
        }
        return target
    }

    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        // The panel now publishes the update notice (url, version, notes) at
        // update.json in the repository root. The old upstream GitHub release check
        // was replaced by this source so both the on-launch popup and the "Check for
        // update" screen use the same rule.
        val notice = checkPanelUpdate() ?: return@withContext CheckUpdateResult(hasUpdate = false)
        return@withContext CheckUpdateResult(
            hasUpdate = true,
            latestVersion = notice.version,
            releaseNotes = notice.notes.ifBlank { notice.version },
            downloadUrl = notice.url,
            isPreRelease = includePreRelease,
        )
    }


}
