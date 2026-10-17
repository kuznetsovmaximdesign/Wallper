package com.wallper.live.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * Follows the default network so the app survives a VPN being switched on or off
 * (and Wi-Fi ↔ mobile hand-overs).
 *
 * Such a switch replaces the default network: connections opened on the old one
 * either fail or hang until a timeout. On every switch pooled connections are
 * dropped and requests still running are cancelled, so their retry logic reconnects
 * at once over the new route. [changes] then ticks, letting the UI retry what failed.
 */
class NetworkMonitor(context: Context, private val http: OkHttpClient) {

    private val _changes = MutableStateFlow(0)

    /** Increments whenever a new default network (or a VPN on/off) takes over. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var current: Network? = null
    private var vpn: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = switchTo(network, vpn)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            switchTo(network, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    init {
        context.getSystemService(ConnectivityManager::class.java)
            ?.runCatching { registerDefaultNetworkCallback(callback) }
    }

    @Synchronized
    private fun switchTo(network: Network, isVpn: Boolean?) {
        val previous = current
        val wasVpn = vpn
        current = network
        vpn = isVpn
        val switched = previous != null && previous != network
        val vpnFlipped = wasVpn != null && isVpn != null && wasVpn != isVpn
        if (!switched && !vpnFlipped) return
        http.connectionPool.evictAll()
        http.dispatcher.cancelAll()
        _changes.update { it + 1 }
    }
}
