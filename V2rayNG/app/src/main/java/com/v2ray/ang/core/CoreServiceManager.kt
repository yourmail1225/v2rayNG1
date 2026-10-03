package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.ActivationManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.LockDeniedMessage
import com.v2ray.ang.util.LockEvaluator
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    private const val GROUP_USAGE_TICK_MS = 3000L

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var currentProfileGuid: String? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupUsageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Refreshes subscriptions and pushes the traffic reading after a real connection.
     *
     * A row the panel republished since the last hourly worker run only reaches the
     * customer here, and the same write that updates `usedBytes` stamps the connection so
     * the panel can show when the subscription was last in use. Both go through the
     * activation manager's own scope, which owns the row write, so this scope only
     * schedules them and is cancelled with the service.
     */
    private val connectionReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isReloading = false

    @Volatile
    private var dataLimitNoticeShown = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        val runningGuid = MmkvManager.getSelectServer()
        val runningConfig = runningGuid?.let { MmkvManager.decodeServerConfig(it) }
        val denied = runningGuid?.let { guid ->
            runningConfig?.let { config ->
                val affiliation = MmkvManager.decodeServerAffiliationInfo(guid)
                val subscriptionUsed = if (affiliation?.persistentLock == true) {
                    MmkvManager.getSubscriptionUsedBytes(config.subscriptionId)
                } else {
                    affiliation?.usedBytes ?: 0L
                }
                LockEvaluator.evaluateServer(
                    MmkvManager.decodeGroupLock(config.subscriptionId),
                    affiliation,
                    currentUsedBytes = subscriptionUsed,
                ) as? LockEvaluator.Decision.Denied
            }
        }
        if (denied != null) {
            LogUtil.i(
                AppConfig.TAG,
                "StartCore-Manager: Lock denies ${runningConfig?.remarks ?: "profile"}"
            )
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_LOCK_DENIED,
                LockDeniedMessage.resolve(service, denied.reason, denied.scope)
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            dataLimitNoticeShown = false
            doStartCoreLoop(service, vpnInterface)
            scheduleGroupUsageAccumulation()
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        currentProfileGuid = guid
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            onConnectionEstablished()
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Periodic work an activated customer depends on once the tunnel is up.
     *
     * Runs off the connection path so a slow or unreachable row can never delay or fail
     * the session, and a reload is excluded because reloading the running config is not a
     * new connection. The usage write also stamps the connection time in the row, which
     * is what the panel shows as the last connection.
     */
    private fun onConnectionEstablished() {
        if (!ActivationManager.isActivated()) return
        connectionReportScope.launch {
            runCatching { AngConfigManager.updateConfigViaSubAll() }
                .onFailure { LogUtil.e(AppConfig.TAG, "Connection refresh of subscriptions failed", it) }
            ActivationManager.reportUsage()
        }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
        groupUsageScope.coroutineContext.cancelChildren()
        connectionReportScope.coroutineContext.cancelChildren()
        // A new connection attempt must start with a clean notice flag so the expiry and
        // data-limit messages are shown again for the next session.
        dataLimitNoticeShown = false
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        currentProfileGuid = null

        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            connectionTestScope.coroutineContext.cancelChildren()
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            launchCore(service, tunFd, isReload = true)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Consumes [bytes] against the running group's lock and the running profile's lock,
     * and, when either data limit is reached, notifies the UI and stops the session.
     *
     * @param subscriptionId The group to charge, or null to use the running config's group.
     *                       Passed explicitly by the OpenVPN engine which keeps no [currentConfig].
     * @param profileGuid The profile to charge, or null to use the running xray profile.
     *                    Passed by the OpenVPN engine, which keeps no [currentProfileGuid].
     */
    fun accumulateGroupDataUsage(bytes: Long, subscriptionId: String? = null, profileGuid: String? = null) {
        if (bytes <= 0L) return
        val groupId = subscriptionId ?: currentConfig?.subscriptionId ?: return
        val lock = MmkvManager.decodeGroupLock(groupId)
        // A locked group is charged even without a data limit, because the counter is
        // what the panel shows the customer as consumed traffic and what the row writes
        // back to GitHub. The limit only decides when charging stops the session.
        if (LockEvaluator.chargesUsage(lock)) {
            val used = MmkvManager.addGroupUsedBytes(groupId, bytes)
            if (lock.dataLimitBytes > 0L && used >= lock.dataLimitBytes && !dataLimitNoticeShown) {
                dataLimitNoticeShown = true
                val service = getService() ?: return
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Group data limit reached, stopping")
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_STATE_LOCK_DENIED,
                    LockDeniedMessage.resolve(
                        service,
                        LockEvaluator.DeniedReason.DATA_LIMIT_REACHED,
                        LockEvaluator.DeniedScope.GROUP
                    )
                )
                MessageHelper.sendMsg2Service(service, AppConfig.MSG_STATE_STOP, "")
            }
        }
        // Profile locks are charged for the xray run modes, which track the selected
        // profile GUID, and for OpenVPN sessions, which pass the selected profile's GUID
        // explicitly because they keep no [currentConfig].
        val pg = profileGuid ?: currentProfileGuid ?: return
        val aff = MmkvManager.decodeServerAffiliationInfo(pg) ?: return
        if (!aff.locked && !aff.persistentLock) return
        // A permanently locked profile accounts its traffic against one subscription-wide
        // counter, and that counter is what its card shows. Charging was gated on a
        // per-profile data limit being present, so a locked row published without a volume
        // cap never moved that counter: the app kept displaying its first reading while
        // the group counter, which is what the panel reads, kept climbing.
        val used = MmkvManager.addProfileUsedBytes(pg, bytes)
        // Enforcement stays exactly as it was, so a lock that carries its own limit still
        // stops the session on that limit. A permanently locked profile shares the
        // subscription's quota, which its group enforces.
        if (aff.dataLimitBytes <= 0L) return
        if (used >= aff.dataLimitBytes && !dataLimitNoticeShown) {
            dataLimitNoticeShown = true
            val service = getService() ?: return
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Profile data limit reached, stopping")
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_LOCK_DENIED,
                LockDeniedMessage.resolve(
                    service,
                    LockEvaluator.DeniedReason.DATA_LIMIT_REACHED,
                    LockEvaluator.DeniedScope.PROFILE
                )
            )
            MessageHelper.sendMsg2Service(service, AppConfig.MSG_STATE_STOP, "")
        }
    }

    /**
     * Starts the periodic lock enforcement loop for the xray run modes. The loop always
     * runs so that expiry times are enforced even when a lock carries no data limit, and
     * the stored usage counters are re-evaluated on every tick no matter who charged them.
     *
     * The notification speed loop consumes the resetting core counters while it lives,
     * so this coroutine only charges from those counters when that loop is not running
     * to avoid double counting; enforcement itself reads the stored usage, which the
     * speed loop feeds through [accumulateGroupDataUsage].
     *
     * Gating on the live loop state (not the static speed setting) keeps exactly one
     * consumer at all times: stopping the speed loop (screen off, session end) hands
     * the counters back to this loop immediately, so a session never records traffic
     * against the usage limit twice or loses the period between toggles.
     */
    private fun scheduleGroupUsageAccumulation() {
        groupUsageScope.coroutineContext.cancelChildren()
        groupUsageScope.launch {
            while (true) {
                delay(GROUP_USAGE_TICK_MS)
                if (!isRunning()) return@launch
                val service = getService() ?: continue
                val config = currentConfig ?: continue
                val group = MmkvManager.decodeGroupLock(config.subscriptionId)
                val profileGuid = currentProfileGuid
                val profile = profileGuid?.let { MmkvManager.decodeServerAffiliationInfo(it) }
// An enabled group is charged even when it carries no expiry and no volume limit;
                // see LockEvaluator.chargesUsage. Gating this loop on a condition being
                // present left the counter at zero forever for exactly the activated rows
                // that had no limit to enforce.
                val groupActive = LockEvaluator.chargesUsage(group)
                val profileActive = profile != null &&
                    (profile.locked || profile.persistentLock) &&
                    (profile.dataLimitBytes > 0L || profile.expiryEpochMinute != 0L)
                if (!groupActive && !profileActive) continue

                // The speed loop is the sole counter consumer while it runs; otherwise
                // this loop charges from the same resetting counters. Using the live job
                // state (not the static speed setting) means a screen-off stop or a
                // mid-session setting toggle hand ownership over without a lost or
                // double count.
                if (!NotificationManager.isSpeedNotificationRunning()) {
                    val total = queryAllOutboundTrafficStats()
                        .filter { it.tag != AppConfig.TAG_DIRECT && it.tag != AppConfig.TAG_BLOCKED }
                        .sumOf { it.value }
                    if (total > 0L) accumulateGroupDataUsage(total)
                }

                // Re-evaluate stored expiry and usage every tick. This stops an already
                // running session once its lock expires or its volume is consumed, even
                // when the speed loop owns the counter reads or the lock has no volume.
                val denial = profileGuid?.let { guid ->
                    val subscriptionUsed = if (profile?.persistentLock == true) {
                        MmkvManager.getSubscriptionUsedBytes(config.subscriptionId)
                    } else {
                        profile?.usedBytes ?: 0L
                    }
                    LockEvaluator.evaluateServer(
                        group,
                        profile,
                        currentUsedBytes = subscriptionUsed,
                    ) as? LockEvaluator.Decision.Denied
                }
                if (denial != null) {
                    LogUtil.i(
                        AppConfig.TAG,
                        "StartCore-Manager: Running lock (${denial.scope}/${denial.reason}) denies session, stopping"
                    )
                    if (!dataLimitNoticeShown) {
                        dataLimitNoticeShown = true
                        MessageHelper.sendMsg2UI(
                            service,
                            AppConfig.MSG_STATE_LOCK_DENIED,
                            LockDeniedMessage.resolve(service, denial.reason, denial.scope)
                        )
                    }
                    MessageHelper.sendMsg2Service(service, AppConfig.MSG_STATE_STOP, "")
                    return@launch
                }
            }
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(requestId: String) {
        val service = getService() ?: return
        if (!isRunning() || isReloading) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        connectionTestScope.coroutineContext.cancelChildren()
        connectionTestScope.launch {
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                ensureActive()
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
