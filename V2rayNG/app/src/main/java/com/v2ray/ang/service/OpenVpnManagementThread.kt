package com.v2ray.ang.service

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import com.v2ray.ang.util.LogUtil
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.LinkedList

/**
 * Bridge used by [OpenVpnManagementThread] to feed OpenVPN's tun requests into
 * the VpnService implementation in [OpenVpnCoreService].
 */
interface OpenVpnCoreBridge {
    fun addDNS(dns: String)
    fun addSearchDomain(domain: String)
    fun addRoute(dest: String, mask: String, gateway: String, device: String?)
    fun addRoutev6(networkCidr: String, device: String?)
    fun addHttpProxy(host: String, port: Int)
    fun setMtu(mtu: Int)
    fun setLocalIP(local: String, remote: String, mtu: Int, topology: String)
    fun setLocalIPv6(addrCidr: String)
    fun openTun(): ParcelFileDescriptor?
    fun protect(fd: Int): Boolean
    fun isTunConfigSame(): String
    fun reportState(state: String, detail: String)
    fun reportByteCount(bytesIn: Long, bytesOut: Long)
    fun reportLog(level: String, msg: String)
    fun credentialsFor(needed: String): Pair<String, String>?
}

/**
 * OpenVPN 2.x machine-readable management protocol over a Unix-domain socket.
 * The openvpn binary runs with `--management <path> unix --management-client`
 * so it dials the server socket bound here after startup. Command sequence is
 * ROUTE/IFCONFIG, then DNS, then OPENTUN; the tun fd is transported back to
 * the openvpn process as SCM_RIGHTS ancillary data.
 */
class OpenVpnManagementThread(
    private val bridge: OpenVpnCoreBridge,
    private val socketName: String
) : Runnable {
    private val TAG = "OpenVpnManagement"
    private var mServerSocketLocal: LocalSocket? = null
    private var mServerSocket: LocalServerSocket? = null
    private var mSocket: LocalSocket? = null
    private val mFDList = LinkedList<FileDescriptor>()

    fun openManagementInterface(): Boolean {
        // AF_UNIX bind is connection-oriented; retry while another process
        // from a previous run still holds the address.
        var tries = 8
        val local = LocalSocket()
        while (tries > 0 && !local.isBound) {
            try {
                local.bind(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: IOException) {
                tries--
                try {
                    Thread.sleep(300)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        if (!local.isBound) {
            LogUtil.e(TAG, "Cannot bind management socket $socketName")
            return false
        }
        return try {
            mServerSocketLocal = local
            mServerSocket = LocalServerSocket(local.getFileDescriptor())
            true
        } catch (e: IOException) {
            LogUtil.e(TAG, "Cannot create management server socket", e)
            false
        }
    }

    fun managmentCommand(cmd: String) {
        val socket = mSocket ?: return
        try {
            socket.getOutputStream().write(cmd.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        } catch (e: IOException) {
            LogUtil.w(TAG, "Cannot write to management socket: ${e.message}")
        }
    }

    fun closeManagementInterface() {
        try {
            mSocket?.close()
        } catch (e: IOException) {
            // Socket already closed
        }
        try {
            mServerSocket?.close()
        } catch (e: IOException) {
            // Socket already closed
        }
        mSocket = null
        mServerSocket = null
        mServerSocketLocal = null
    }

    override fun run() {
        val serverSocket = mServerSocket ?: return
        try {
            mSocket = serverSocket.accept()
            try {
                serverSocket.close()
            } catch (e: IOException) {
                // Accepting socket closed after the client connected
            }
            val instream: InputStream = mSocket!!.getInputStream()
            managmentCommand("version 3\n")
            var pendingInput = ""
            val buffer = ByteArray(2048)
            while (true) {
                val numbytesread = instream.read(buffer)
                if (numbytesread == -1) {
                    break
                }
                val fds = try {
                    mSocket!!.getAncillaryFileDescriptors()
                } catch (e: IOException) {
                    LogUtil.w(TAG, "Error reading fds from management socket: ${e.message}")
                    null
                }
                if (fds != null) {
                    for (fd in fds) {
                        mFDList.add(fd)
                    }
                }
                val input = String(buffer, 0, numbytesread, Charsets.UTF_8)
                pendingInput += input
                pendingInput = processInput(pendingInput)
            }
        } catch (e: IOException) {
            val msg = e.message
            if (msg != "socket closed" && msg != "Connection reset by peer") {
                LogUtil.w(TAG, "Management socket error: $msg")
            }
        } finally {
            closeManagementInterface()
            bridge.reportState("EXITING", "OpenVPN management socket closed")
        }
    }

    private fun processInput(pendingInput: String): String {
        var rest = pendingInput
        while (rest.contains("\n")) {
            val idx = rest.indexOf('\n')
            val line = rest.substring(0, idx).trimEnd('\r')
            processCommand(line)
            rest = rest.substring(idx + 1)
        }
        return rest
    }

    private fun processCommand(command: String) {
        if (command.startsWith(">NEED-OK:")) {
            processNeedCommand(command.substring(9))
            return
        }
        if (command.startsWith(">PASSWORD:")) {
            processPasswordCommand(command.substring(10))
            return
        }
        if (command.startsWith(">HOLD:")) {
            releaseHold()
            return
        }
        if (command.startsWith(">STATE:")) {
            processStateCommand(command.substring(7))
            return
        }
        if (command.startsWith(">BYTECOUNT:")) {
            processByteCountCommand(command.substring(11))
            return
        }
        if (command.startsWith(">LOG:")) {
            processLogCommand(command.substring(5))
            return
        }
        if (command.startsWith(">INFOMSG:")) {
            bridge.reportLog("I", "Server info: ${command.substring(9)}")
            return
        }
        // Some openvpn builds print the protected fd number in the line as well
        // as attaching it with SCM_RIGHTS; the number is meaningless on this side.
        if (command.startsWith("PROTECTFD: ")) {
            val fd = pollFd()
            if (fd != null) {
                protectFileDescriptor(fd)
            }
            managmentCommand("needok 'PROTECTFD' ok\n")
            return
        }
        if (command.startsWith("SUCCESS:") || command.startsWith(">INFO:")) {
            // SUCCESS acknowledges a command; >INFO: is the greeting header
            return
        }
        LogUtil.w(TAG, "Unrecognized management line: $command")
    }

    private fun processStateCommand(argument: String) {
        val parts = argument.split(",", limit = 3)
        if (parts.size < 2) {
            return
        }
        val state = parts[1]
        val detail = if (parts.size >= 3 && parts[2] != ",,") {
            parts[2].replace(",", "")
        } else {
            ""
        }
        bridge.reportState(state, detail)
    }

    private fun processByteCountCommand(argument: String) {
        val comma = argument.indexOf(',')
        if (comma <= 0) {
            return
        }
        try {
            bridge.reportByteCount(argument.substring(0, comma).toLong(), argument.substring(comma + 1).toLong())
        } catch (e: NumberFormatException) {
            LogUtil.w(TAG, "Unparsable byte count: $argument")
        }
    }

    private fun processLogCommand(argument: String) {
        // LOG:<unix time>,<flags>,<level>,<message>
        val parts = argument.split(",", limit = 4)
        if (parts.size < 4) {
            return
        }
        bridge.reportLog(parts[1], parts[3])
    }

    private fun processNeedCommand(argument: String) {
        val p1 = argument.indexOf('\'')
        val p2 = argument.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) {
            LogUtil.w(TAG, "Cannot parse NEED-OK command: $argument")
            managmentCommand("needok 'OPENTUN' cancel\n")
            return
        }
        val needed = argument.substring(p1 + 1, p2)
        val msgIdx = argument.indexOf("MSG:")
        val extra = if (msgIdx >= 0) argument.substring(msgIdx + 4) else ""

        when (needed) {
            "PROTECTFD" -> {
                val fd = pollFd()
                if (fd != null) {
                    protectFileDescriptor(fd)
                }
                managmentCommand("needok 'PROTECTFD' ok\n")
            }

            "DNSSERVER", "DNS6SERVER" -> bridge.addDNS(extra)
            "DNSDOMAIN" -> bridge.addSearchDomain(extra)

            "ROUTE" -> {
                val routeparts = extra.split(" ")
                if (routeparts.size == 5) {
                    bridge.addRoute(routeparts[0], routeparts[1], routeparts[2], routeparts[4])
                } else if (routeparts.size >= 3) {
                    bridge.addRoute(routeparts[0], routeparts[1], routeparts[2], null)
                } else {
                    LogUtil.w(TAG, "Unrecognized ROUTE command: $extra")
                }
            }

            "ROUTE6" -> {
                val route6parts = extra.split(" ")
                if (route6parts.size >= 2) {
                    bridge.addRoutev6(route6parts[0], route6parts[1])
                } else {
                    LogUtil.w(TAG, "Unrecognized ROUTE6 command: $extra")
                }
            }

            "IFCONFIG" -> {
                val ifconfigparts = extra.split(" ")
                if (ifconfigparts.size >= 4) {
                    val mtu = try {
                        ifconfigparts[2].toInt()
                    } catch (e: NumberFormatException) {
                        LogUtil.w(TAG, "Unparsable IFCONFIG mtu: $extra")
                        1500
                    }
                    bridge.setLocalIP(ifconfigparts[0], ifconfigparts[1], mtu, ifconfigparts[3])
                } else {
                    LogUtil.w(TAG, "Unrecognized IFCONFIG command: $extra")
                }
            }

            "IFCONFIG6" -> {
                val ifconfig6parts = extra.split(" ")
                if (ifconfig6parts.size >= 2) {
                    val mtu = try {
                        ifconfig6parts[1].toInt()
                    } catch (e: NumberFormatException) {
                        LogUtil.w(TAG, "Unparsable IFCONFIG6 mtu: $extra")
                        1500
                    }
                    bridge.setMtu(mtu)
                    bridge.setLocalIPv6(ifconfig6parts[0])
                } else {
                    LogUtil.w(TAG, "Unrecognized IFCONFIG6 command: $extra")
                }
            }

            "PERSIST_TUN_ACTION" -> managmentCommand("needok 'PERSIST_TUN_ACTION' ${bridge.isTunConfigSame()}\n")

            "OPENTUN" -> {
                if (sendTunFD(extra)) {
                    return
                }
                managmentCommand("needok 'OPENTUN' cancel\n")
            }

            "HTTPPROXY" -> {
                val httpproxy = extra.split(" ")
                if (httpproxy.size >= 2) {
                    val port = try {
                        httpproxy[1].toInt()
                    } catch (e: NumberFormatException) {
                        0
                    }
                    if (port > 0) {
                        bridge.addHttpProxy(httpproxy[0], port)
                    }
                }
            }

            else -> LogUtil.w(TAG, "Unknown needok command: $argument")
        }

        if (needed != "PROTECTFD" && needed != "PERSIST_TUN_ACTION" && needed != "OPENTUN") {
            managmentCommand("needok '$needed' ok\n")
        }
    }

    private fun processPasswordCommand(argument: String) {
        if (argument.startsWith("Auth-Token:")) {
            return
        }
        if (argument.startsWith("Verification Failed")) {
            LogUtil.e(TAG, "OpenVPN reported $argument")
            return
        }
        val p1 = argument.indexOf('\'')
        val p2 = argument.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) {
            LogUtil.w(TAG, "Cannot parse PASSWORD command: $argument")
            return
        }
        val needed = argument.substring(p1 + 1, p2)
        val creds = bridge.credentialsFor(needed)
        if (creds == null) {
            LogUtil.e(TAG, "OpenVPN requests '$needed' credentials but the profile has none")
            if (needed == "Auth") {
                managmentCommand("username \"\"\n")
            }
            managmentCommand("password \"\"\n")
            return
        }
        if (needed == "Auth") {
            managmentCommand("username \"${creds.first}\"\n")
        }
        managmentCommand("password \"${creds.second}\"\n")
    }

    private fun releaseHold() {
        managmentCommand("hold release\n")
        managmentCommand("bytecount 1\n")
        managmentCommand("state on\n")
    }

    private fun pollFd(): FileDescriptor? {
        val fd = mFDList.pollFirst()
        if (fd == null) {
            LogUtil.e(TAG, "OpenVPN sent a file descriptor that never arrived")
        }
        return fd
    }

    private fun protectFileDescriptor(fd: FileDescriptor) {
        try {
            val getInt = FileDescriptor::class.java.getDeclaredMethod("getInt$")
            val fdint = getInt.invoke(fd) as Int
            val result = bridge.protect(fdint)
            if (!result) {
                LogUtil.w(TAG, "Could not protect VPN socket $fdint")
            }
            Os.close(fd)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to protect VPN socket", e)
        }
    }

    private fun sendTunFD(extra: String): Boolean {
        if (extra != "tun") {
            LogUtil.e(TAG, "Device type '$extra' requested, only tun is possible with the Android VPN API")
            return false
        }
        val pfd = bridge.openTun() ?: return false
        return try {
            val setInt = FileDescriptor::class.java.getDeclaredMethod("setInt$", Int::class.javaPrimitiveType)
            val fdtosend = FileDescriptor()
            setInt.invoke(fdtosend, pfd.fd)
            // SCM_RIGHTS: the descriptor is attached to the next write; it must
            // be cleared afterwards or every write keeps sending it.
            mSocket?.setFileDescriptorsForSend(arrayOf(fdtosend))
            managmentCommand("needok 'OPENTUN' ok\n")
            mSocket?.setFileDescriptorsForSend(null)
            pfd.close()
            true
        } catch (e: Exception) {
            LogUtil.e(TAG, "Could not send tun fd over management socket", e)
            false
        }
    }
}