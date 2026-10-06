package com.v2ray.ang.service

import android.content.Context
import android.os.Build
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs the OpenVPN 2.x engine binary as a child process with a config file
 * (profile + Android management options) written into the app cache.
 *
 * The binary shipped in the APK is the gpl patched openvpn built for the
 * Android management API: `lib/<abi>/libovpnexec.so` (a small shim) dlopens
 * `lib/<abi>/libopenvpn.so` from [LD_LIBRARY_PATH] after exec.
 */
class OpenVpnEngine(
    private val context: Context,
    socketPath: String,
    private val rawConfig: String,
    private val onLog: (String) -> Unit,
    private val onExit: () -> Unit
) {
    val config: String = buildEnhancedConfig(rawConfig, socketPath)
    val username: String?
    val password: String?

    private var process: Process? = null
    private var readerThread: Thread? = null
    private val logPattern = Regex("""(\d+)\.(\d+) ([0-9A-Fa-f])+ (.*)""")
    private val configFile: File = File(context.cacheDir, "v2rayng_openvpn.conf")

    init {
        val creds = parseInlineCredentials(rawConfig)
        username = creds?.first
        password = creds?.second
    }

    fun start(): Boolean {
        val binary = locateBinary()
        if (binary == null) {
            LogUtil.e(TAG, "OpenVPN binary not found; the APK was built without the OpenVPN engine")
            return false
        }
        // OpenVPN only accepts `--config` from a file path; write into the
        // app-private cache (0600) and remove it again during stop().
        try {
            configFile.writeText(config, Charsets.UTF_8)
        } catch (e: IOException) {
            LogUtil.e(TAG, "Failed to write OpenVPN config file", e)
            return false
        }
        val pb = ProcessBuilder(binary, "--config", configFile.absolutePath)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        env["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            process = proc
            readerThread = Thread({ readProcessOutput(proc) }, "OpenVPN-Process-Log").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: IOException) {
            LogUtil.e(TAG, "Failed to start OpenVPN process", e)
            false
        }
    }

    fun stop() {
        val proc = process
        process = null
        try {
            if (proc?.isAlive == true) {
                proc.destroy()
                if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    LogUtil.vpn(TAG, "OpenVPN process ignored SIGTERM, force killing")
                    proc.destroyForcibly()
                    proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LogUtil.w(TAG, "Error while stopping OpenVPN process: ${e.message}")
        }
        try {
            readerThread?.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readerThread = null
        configFile.delete()
    }

    private fun readProcessOutput(proc: Process) {
        try {
            val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                val match = logPattern.find(line)
                if (match != null && match.groups.size > 4 && match.groups[4] != null) {
                    onLog(match.groupValues[4])
                } else {
                    onLog(line)
                }
            }
        } catch (e: IOException) {
            if (process == null) {
                LogUtil.vpn(TAG, "OpenVPN process output closed")
            } else {
                LogUtil.w(TAG, "Error reading OpenVPN output: ${e.message}")
            }
        } finally {
            onExit()
        }
    }

    private fun locateBinary(): String? {
        val nativeShim = File(context.applicationInfo.nativeLibraryDir, "libovpnexec.so")
        if (nativeShim.isFile) {
            return nativeShim.absolutePath
        }
        // Pre-P devices cannot execute binaries from nativeLibraryDir, but can
        // from cache. The shim needs libopenvpn.so, so keep LD_LIBRARY_PATH.
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        try {
            val input = context.assets.open("pie_openvpn.$abi")
            val outFile = File(context.cacheDir, "c_pie_openvpn.$abi")
            outFile.outputStream().use { os -> input.copyTo(os) }
            input.close()
            if (outFile.setExecutable(true)) {
                return outFile.absolutePath
            }
        } catch (e: IOException) {
            LogUtil.w(TAG, "Cannot extract pre-P openvpn shim for $abi: ${e.message}")
        }
        return null
    }

    companion object {
        const val TAG = "OpenVpnEngine"
        const val SIGTERM = "signal SIGTERM"
        private const val timeoutMs = 1500L

        /**
         * Resolves the first active `remote <host> [port]` line for TCP-latency tests.
         * Returns null when no remote exists so callers can report "no delay".
         */
        fun parseOpenVpnRemote(config: String): Pair<String, Int>? {
            return config.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
                .firstNotNullOfOrNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.firstOrNull() != "remote" || parts.size < 2) {
                        null
                    } else {
                        parts[1].takeIf { it.isNotEmpty() && !it.startsWith("/") }?.let { host ->
                            host to (parts.getOrNull(2)?.toIntOrNull() ?: 1194)
                        }
                    }
                }
        }

        fun buildEnhancedConfig(raw: String, socketPath: String): String {
            val builder = StringBuilder()
            builder.append("# v2rayNG managed OpenVPN 2.x config\n")
            builder.append("management $socketPath unix\n")
            builder.append("management-client\n")
            builder.append("management-query-passwords\n")
            builder.append("management-hold\n")
            builder.append("machine-readable-output\n")
            builder.append("allow-recursive-routing\n")
            // No external program may be executed from the Android sandbox.
            builder.append("script-security 0\n")
            builder.append("ifconfig-nowarn\n")
            builder.append("verb 3\n")
            builder.append("\n")
            builder.append(mapRawLines(raw))
            return builder.toString()
        }

        internal fun mapRawLines(raw: String): String {
            val managed = setOf(
                "management",
                "management-client",
                "management-query-passwords",
                "management-hold",
                "machine-readable-output",
                "allow-recursive-routing",
                // The Android management API passes the tun fd over the socket on a
                // releaseHold; persisting the tun across process restarts is impossible.
                "persist-tun",
                // The engine never stays up across a telephony/network switch here.
                "persist-key"
            )
            return raw.lineSequence().map { line ->
                val key = line.trim().substringBefore(' ')
                when {
                    managed.contains(key) -> null
                    // A file-backed auth-user-pass cannot exist on Android;
                    // switch it to the management prompt for credentials.
                    key == "auth-user-pass" && line.trim() != "auth-user-pass" -> "auth-user-pass"
                    key == "dev" -> normalizeDev(line.trim())
                    else -> line
                }
            }.filterNotNull().joinToString("\n")
        }

        // The management OPENTUN payload must be exactly the literal "tun"; a named
        // device (e.g. "dev tun0") cancels the handshake. Android owns the device name
        // once the engine creates it, so any tun<name> the user pinned is normalized.
        private fun normalizeDev(line: String): String {
            val value = line.removePrefix("dev").trim()
            return when {
                value == "tun" || value.startsWith("dev ") || value.isEmpty() || !value.startsWith("tun") -> line
                else -> "dev tun"
            }
        }

        internal fun parseInlineCredentials(raw: String): Pair<String, String>? {
            val lines = raw.lineSequence().map { it.trim() }.toList()
            for (i in lines.indices) {
                if (lines[i] != "auth-user-pass") {
                    continue
                }
                val user = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<") }
                val pass = lines.getOrNull(i + 2)?.takeIf { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<") }
                if (user != null && pass != null) {
                    return user to pass
                }
            }
            return null
        }
    }
}