package com.wallper.live.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiaTrustManagerTest {

    private fun aia(vararg descriptions: ByteArray): ByteArray {
        val seq = descriptions.fold(ByteArray(0)) { acc, d -> acc + d }
        val inner = byteArrayOf(0x30, seq.size.toByte()) + seq
        return byteArrayOf(0x04, inner.size.toByte()) + inner // OCTET STRING wrapper
    }

    private fun description(oidTail: Byte, uri: String): ByteArray {
        val oid = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, oidTail)
        val name = byteArrayOf(0x86.toByte(), uri.length.toByte()) + uri.encodeToByteArray()
        val body = oid + name
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test
    fun findsCaIssuersAfterOcsp() {
        val der = aia(
            description(0x01, "http://ocsp.apple.com/ocsp03-apsrsa12g101"),
            description(0x02, "http://certs.apple.com/apsrsa12g1.der"),
        )
        assertEquals("http://certs.apple.com/apsrsa12g1.der", AiaTrustManager.parseCaIssuers(der))
    }

    @Test
    fun returnsNullWithoutCaIssuers() {
        assertNull(AiaTrustManager.parseCaIssuers(aia(description(0x01, "http://ocsp.example.com"))))
    }
}
