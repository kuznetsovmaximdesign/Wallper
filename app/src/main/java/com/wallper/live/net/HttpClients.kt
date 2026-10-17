package com.wallper.live.net

import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

object HttpClients {

    /**
     * Trust is configured declaratively in res/xml/network_security_config.xml:
     * Apple's Aerials CDN chains to "Apple Root CA", which is trusted for that host only.
     *
     * Timeouts are short so a connection stranded by a network or VPN switch is noticed
     * quickly; callers retry on a fresh connection.
     */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Many VPNs route only IPv4 while DNS still returns IPv6 addresses; OkHttp tries
     * addresses in order, so an unroutable IPv6 address would stall each connection
     * for the whole connect timeout. IPv4 goes first, IPv6 remains as a fallback.
     */
    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).sortedBy { it !is Inet4Address }
    }
}
