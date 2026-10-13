package io.okno

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Bridges Android networking into Linux. Sockets inside proot already go
 * through whatever network Android uses (Wi-Fi, mobile data, VPN); what Linux
 * lacks is DNS configuration, which Android keeps to itself. We copy the DNS
 * servers of the active network into /etc/resolv.conf and refresh them when
 * the phone switches networks.
 */
class NetworkConfig(context: Context, private val env: LinuxEnvironment) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun writeResolvConf() {
        if (!env.rootfs.isDirectory) return
        val network = connectivity.activeNetwork
        val fromAndroid = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }
            .filterNot { it.contains('%') } // scoped IPv6 addresses don't work in resolv.conf
        // glibc uses at most three servers: prefer the network's own, then public fallbacks.
        val servers = (fromAndroid + FALLBACK_DNS).distinct().take(3)
        val file = File(env.rootfs, "etc/resolv.conf")
        file.delete() // may be a dangling symlink in container images
        file.writeText(buildString {
            append("# Written by Okno from the active Android network (${describe(network)}).\n")
            servers.forEach { append("nameserver $it\n") }
        })
    }

    fun startWatching() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = writeResolvConf()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = writeResolvConf()
        }
        connectivity.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    fun stopWatching() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
    }

    /** Human-readable summary for the "Сеть" dialog. */
    fun summary(): String = buildString {
        val network = connectivity.activeNetwork
        append("Подключение: ${describe(network)}\n")
        val dns = connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        append("DNS сети: ${dns.joinToString().ifEmpty { "нет" }}\n\n")
        append("Адреса телефона:\n")
        val lines = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { "  ${iface.name}: ${it.hostAddress}" }
            }
        append(lines.joinToString("\n").ifEmpty { "  нет" })
    }

    private fun describe(network: Network?): String {
        val caps = network?.let { connectivity.getNetworkCapabilities(it) } ?: return "нет сети"
        val kinds = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("мобильная сеть")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        }
        return kinds.joinToString(" + ").ifEmpty { "другая сеть" }
    }

    companion object {
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    }
}
