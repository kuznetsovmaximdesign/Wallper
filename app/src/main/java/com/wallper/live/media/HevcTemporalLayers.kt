package com.wallper.live.media

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Lossless frame-rate reduction for HEVC streams coded with temporal sub-layers.
 *
 * Apple's macOS Aerials are 4K 240 fps with five temporal layers (TID 0–4 add
 * 15/15/30/60/120 fps). A picture never references a higher layer, so dropping
 * every picture above a chosen TID leaves a valid stream at a lower frame rate —
 * no re-encoding, the kept pictures are bit-identical to the original.
 */
object HevcTemporalLayers {

    /** Temporal id of the first coded slice in an access unit, or null if none is found. */
    fun temporalId(sample: ByteBuffer, size: Int): Int? {
        val start = sample.position()
        var i = start
        val end = start + size
        val annexB = size >= 4 && sample.get(start).toInt() == 0 && sample.get(start + 1).toInt() == 0 &&
            (sample.get(start + 2).toInt() == 1 || (sample.get(start + 2).toInt() == 0 && sample.get(start + 3).toInt() == 1))
        if (annexB) {
            while (i + 4 < end) {
                if (sample.get(i).toInt() == 0 && sample.get(i + 1).toInt() == 0 && sample.get(i + 2).toInt() == 1) {
                    vclTemporalId(sample, i + 3)?.let { return it }
                    i += 3
                } else {
                    i++
                }
            }
        } else {
            // Length-prefixed (4-byte) NAL units.
            while (i + 6 <= end) {
                val length = sample.getInt(i)
                if (length <= 0) return null
                vclTemporalId(sample, i + 4)?.let { return it }
                i += 4 + length
            }
        }
        return null
    }

    /** TID of the NAL at [offset] if it is a coded slice (types 0–31). */
    private fun vclTemporalId(buffer: ByteBuffer, offset: Int): Int? {
        val b0 = buffer.get(offset).toInt() and 0xff
        val b1 = buffer.get(offset + 1).toInt() and 0xff
        val type = (b0 shr 1) and 0x3f
        if (type >= 32) return null
        return (b1 and 0x07) - 1
    }

    /**
     * Highest TID to keep so that the result does not exceed [targetFps], given
     * the per-layer picture counts observed in a sample window of [sourceFps] video.
     */
    fun maxLayerFor(counts: Map<Int, Int>, sourceFps: Double, targetFps: Double): Int {
        val total = counts.values.sum().takeIf { it > 0 } ?: return Int.MAX_VALUE
        var kept = 0
        var best = 0
        for (tid in counts.keys.sorted()) {
            kept += counts.getValue(tid)
            if (sourceFps * kept / total <= targetFps + 1) best = tid else break
        }
        return best
    }

    /**
     * For HEVC above the device's playable rate, scans ~0.5 s of samples from [startUs]
     * to learn the temporal-layer layout and returns the highest layer to keep;
     * [Int.MAX_VALUE] keeps everything. Leaves the extractor at an undefined position.
     */
    fun scanMaxLayer(extractor: MediaExtractor, format: MediaFormat, startUs: Long, capFps: Double = 60.0): Int {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_HEVC) return Int.MAX_VALUE
        val capacity = maxOf(
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
            SCAN_BUFFER,
        )
        val buffer = ByteBuffer.allocateDirect(capacity)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val counts = HashMap<Int, Int>()
        val times = ArrayList<Long>(SCAN_SAMPLES)
        while (times.size < SCAN_SAMPLES) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            times += extractor.sampleTime
            buffer.position(0)
            val tid = temporalId(buffer, size) ?: 0
            counts[tid] = (counts[tid] ?: 0) + 1
            extractor.advance()
        }
        if (times.size < 2 || counts.size < 2) return Int.MAX_VALUE
        times.sort()
        val spanUs = (times.last() - times.first()).coerceAtLeast(1)
        val sourceFps = (times.size - 1) * 1_000_000.0 / spanUs
        val target = minOf(playableFps(format), capFps)
        return if (sourceFps > target + 1) maxLayerFor(counts, sourceFps, target) else Int.MAX_VALUE
    }

    private const val SCAN_SAMPLES = 120 // 0.5 s at 240 fps: several full layer cycles
    private const val SCAN_BUFFER = 12 shl 20

    /** The highest frame rate (60 or 30) the device can decode at this resolution. */
    fun playableFps(format: MediaFormat): Double {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return 30.0
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
        val supports60 = codecs.any { info ->
            runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities
                    ?.areSizeAndRateSupported(width, height, 60.0) == true
            }.getOrDefault(false)
        }
        return if (supports60) 60.0 else 30.0
    }
}
