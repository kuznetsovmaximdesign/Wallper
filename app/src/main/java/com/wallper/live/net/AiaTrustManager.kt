package com.wallper.live.net

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Platform trust validation plus AIA chasing.
 *
 * Some servers (notably Apple's Aerials CDN) omit their intermediate certificate.
 * Browsers recover by downloading the issuer from the leaf's Authority Information
 * Access extension; Android does not. This trust manager does the same and hands the
 * completed chain back to the platform trust manager, so it is still verified against
 * the system root store with the usual hostname-aware checks. Nothing is accepted that
 * the platform would reject for a server sending its full chain.
 */
class AiaTrustManager(private val platform: X509ExtendedTrustManager) : X509ExtendedTrustManager() {

    private val issuers = ConcurrentHashMap<String, X509Certificate>()

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        platform.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        platform.checkClientTrusted(chain, authType, engine)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        verify(chain) { platform.checkServerTrusted(it, authType) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        verify(chain) { platform.checkServerTrusted(it, authType, socket) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        verify(chain) { platform.checkServerTrusted(it, authType, engine) }

    private inline fun verify(chain: Array<out X509Certificate>, check: (Array<out X509Certificate>) -> Unit) {
        try {
            check(chain)
        } catch (original: CertificateException) {
            val completed = complete(chain) ?: throw original
            check(completed)
        }
    }

    private fun complete(chain: Array<out X509Certificate>): Array<X509Certificate>? {
        val tail = chain.lastOrNull() ?: return null
        val url = caIssuersUrl(tail) ?: return null
        val issuer = issuers[url] ?: runCatching { download(url) }.getOrNull()?.also { issuers[url] = it } ?: return null
        if (chain.any { it == issuer }) return null
        return arrayOf(*chain, issuer)
    }

    private fun download(url: String): X509Certificate {
        val candidates = if (url.startsWith("http://")) listOf("https://" + url.removePrefix("http://"), url) else listOf(url)
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                val connection = URL(candidate).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    return CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No AIA candidates")
    }

    internal companion object {
        private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

        // DER encoding of OID 1.3.6.1.5.5.7.48.2 (id-ad-caIssuers).
        private val CA_ISSUERS = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

        fun caIssuersUrl(certificate: X509Certificate): String? =
            certificate.getExtensionValue(AIA_OID)?.let(::parseCaIssuers)

        /** Finds the first caIssuers URI (GeneralName [6] IA5String) in an AIA extension. */
        fun parseCaIssuers(der: ByteArray): String? {
            var i = 0
            while (i <= der.size - CA_ISSUERS.size) {
                if (CA_ISSUERS.indices.all { der[i + it] == CA_ISSUERS[it] }) {
                    var p = i + CA_ISSUERS.size
                    if (p >= der.size || der[p] != 0x86.toByte()) return null
                    p++
                    var length = der[p++].toInt() and 0xff
                    if (length and 0x80 != 0) {
                        val bytes = length and 0x7f
                        length = 0
                        repeat(bytes) { length = (length shl 8) or (der[p++].toInt() and 0xff) }
                    }
                    if (p + length > der.size) return null
                    return String(der, p, length, Charsets.US_ASCII)
                }
                i++
            }
            return null
        }
    }
}
