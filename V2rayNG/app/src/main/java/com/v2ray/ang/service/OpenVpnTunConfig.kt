package com.v2ray.ang.service

internal fun ipv4ToLong(ip: String): Long {
    val parts = ip.split(".")
    return (parts[0].toLong() shl 24) or
        (parts[1].toLong() shl 16) or
        (parts[2].toLong() shl 8) or
        parts[3].toLong()
}

internal fun prefixFromNetmask(netmask: String): Int {
    var nm = ipv4ToLong(netmask) + (1L shl 32)
    var zeros = 0
    while ((nm and 1L) == 0L) {
        zeros++
        nm = nm shr 1
    }
    // If the rest of the netmask is not only 1s, fall back to a /32.
    return if (nm != (0x1ffffffffL shr zeros)) 32 else 32 - zeros
}

internal fun longToIpv4(value: Long): String =
    "${(value shr 24) and 0xff}.${(value shr 16) and 0xff}.${(value shr 8) and 0xff}.${value and 0xff}"

internal fun normaliseToNetwork(value: Long, prefix: Int): Long {
    if (prefix <= 0 || prefix > 32) return 0L
    return value and (0xffffffffL shl (32 - prefix))
}

/**
 * Parses the components of an `IFCONFIG` NEED-OK payload and computes the
 * CIDR prefix for the local address. A peer /32 netmask means the pair is a
 * point-to-point link: net30 uses /30, p2p uses /31, unless the address and
 * peer are not on the same subnet.
 */
internal fun computeLocalPrefix(local: String, remote: String, topology: String): Int {
    var prefix = prefixFromNetmask(remote)
    if (prefix == 32 && remote != "255.255.255.255") {
        val (masklen, mask) = if (topology == "net30") {
            30 to 0xfffffffcL
        } else {
            31 to 0xfffffffeL
        }
        prefix = if ((ipv4ToLong(remote) and mask) == (ipv4ToLong(local) and mask)) {
            masklen
        } else {
            32
        }
    }
    return prefix
}

/**
 * Decides whether an Android `ROUTE` payload should become a VpnService route.
 * Mirrors ics-openvpn addRoute(): include routes on tun devices, the gateway
 * that owns the link, and gateways that sit inside the local network.
 */
internal fun shouldIncludeRoute(
    device: String?,
    gateway: String,
    localIP: String?,
    remoteGW: String?
): Boolean {
    var include = device?.let {
        it.startsWith("tun") || it == "(null)" || it == "vpnservice-tun"
    } ?: false
    if (gateway == "255.255.255.255" || gateway == remoteGW) {
        return true
    }
    val local = localIP ?: return include
    val idx = local.indexOf('/')
    if (idx <= 0) {
        return include
    }
    val localPrefix = local.substring(idx + 1).toInt()
    val localNetwork = normaliseToNetwork(ipv4ToLong(local.substring(0, idx)), localPrefix)
    return include || normaliseToNetwork(ipv4ToLong(gateway), localPrefix) == localNetwork
}

/** The tun device always owns its link on Android; v6 routes ride on it. */
internal fun ownTunDevice(device: String?): Boolean =
    device?.let { it.startsWith("tun") || it == "(null)" || it == "vpnservice-tun" } ?: false

/**
 * Android does not automatically route to the tun's own network, so it must be
 * added explicitly for point-to-point links (prefix <= 31) that are not 0.0.0.0.
 */
internal fun computeSelfNetworkRoute(local: String, prefix: Int): String? {
    if (prefix > 31) {
        return null
    }
    val network = normaliseToNetwork(ipv4ToLong(local), prefix)
    if (network == 0L) {
        return null
    }
    return "${longToIpv4(network)}/$prefix"
}

/**
 * Accumulates the tun configuration OpenVPN reports through the management
 * interface (IFCONFIG/ROUTE/DNS) until OPENTUN asks for the interface.
 */
class OpenVpnTunConfig {
    var mLocalIP: String? = null
    var mLocalIPv6: String? = null
    var mMtu: Int = 1500
    var mRemoteGW: String? = null
    val mDns = mutableListOf<String>()
    val mSearchDomains = mutableListOf<String>()
    val mRoutes = mutableListOf<String>()
    val mRoutesv6 = mutableListOf<String>()

    fun canonicalString(): String =
        "local=$mLocalIP local6=$mLocalIPv6 mtu=$mMtu gw=$mRemoteGW " +
            "dns=${mDns.joinToString("|")} domain=${mSearchDomains.joinToString("|")} " +
            "route4=${mRoutes.joinToString("|")} route6=${mRoutesv6.joinToString("|")}"
}