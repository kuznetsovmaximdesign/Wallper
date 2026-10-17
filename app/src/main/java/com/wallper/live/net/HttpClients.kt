package com.wallper.live.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object HttpClients {

    /**
     * Trust is configured declaratively in res/xml/network_security_config.xml:
     * Apple's Aerials CDN chains to "Apple Root CA", which is trusted for that host only.
     */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
