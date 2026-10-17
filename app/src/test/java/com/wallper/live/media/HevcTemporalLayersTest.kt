package com.wallper.live.media

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevcTemporalLayersTest {

    /** HEVC NAL header: forbidden(1) type(6) layer(6) tid+1(3), then two payload bytes. */
    private fun nal(type: Int, tid: Int) = byteArrayOf(((type shl 1) and 0x7e).toByte(), (tid + 1).toByte(), 0x26, 0x01)

    private fun annexB(vararg nals: ByteArray) =
        nals.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }

    private fun lengthPrefixed(vararg nals: ByteArray) =
        nals.fold(ByteArray(0)) { acc, n -> acc + ByteBuffer.allocate(4).putInt(n.size).array() + n }

    @Test
    fun readsSliceTemporalIdSkippingParameterSets() {
        val sample = annexB(nal(32, 0), nal(33, 0), nal(34, 0), nal(39, 0), nal(2, 4))
        assertEquals(4, HevcTemporalLayers.temporalId(ByteBuffer.wrap(sample), sample.size))
        val prefixed = lengthPrefixed(nal(35, 0), nal(3, 2))
        assertEquals(2, HevcTemporalLayers.temporalId(ByteBuffer.wrap(prefixed), prefixed.size))
        val noSlice = annexB(nal(32, 0))
        assertNull(HevcTemporalLayers.temporalId(ByteBuffer.wrap(noSlice), noSlice.size))
    }

    @Test
    fun picksLayersForTargetFrameRate() {
        // Apple's 240 fps Aerials over 2 s: TID0..4 = 30/30/60/120/240 pictures.
        val counts = mapOf(0 to 30, 1 to 30, 2 to 60, 3 to 120, 4 to 240)
        assertEquals(2, HevcTemporalLayers.maxLayerFor(counts, 240.0, 60.0))
        assertEquals(1, HevcTemporalLayers.maxLayerFor(counts, 240.0, 30.0))
        assertEquals(4, HevcTemporalLayers.maxLayerFor(counts, 240.0, 240.0))
    }
}
