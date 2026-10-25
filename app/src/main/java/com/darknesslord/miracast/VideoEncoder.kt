package com.darknesslord.miracast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class VideoEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val sink: Sink,
) {
    interface Sink {
        fun onCsd(data: ByteArray)
        fun onFrame(data: ByteArray, pts: Long, key: Boolean)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val surface: Surface
    @Volatile private var running = true
    private val thread: Thread

    init {
        val bitrate = bitrateFor(width, height, fps)
        val f = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Ekran sabitken de akış canlı kalsın (TV donmuş sanmasın).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        try {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset()
            val g = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            codec.configure(g, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
        codec.start()
        thread = Thread(::drainLoop, "video-encoder").apply { start() }
    }

    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 20_000)
            } catch (_: Exception) {
                break
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = codec.outputFormat
                val csd = concat(fmt.getByteBuffer("csd-0"), fmt.getByteBuffer("csd-1"))
                if (csd.isNotEmpty()) sink.onCsd(csd)
            } else if (idx >= 0) {
                try {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            sink.onCsd(data)
                        } else {
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            sink.onFrame(data, info.presentationTimeUs, key)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                } catch (_: Exception) {
                    break
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    fun release() {
        running = false
        try { thread.join(1000) } catch (_: InterruptedException) { }
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { surface.release() } catch (_: Exception) { }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        fun bitrateFor(w: Int, h: Int, fps: Int): Int {
            val b = (w.toLong() * h * fps * 0.14).toLong()
            return b.coerceIn(4_000_000L, 24_000_000L).toInt()
        }

        private fun concat(a: ByteBuffer?, b: ByteBuffer?): ByteArray {
            val la = a?.remaining() ?: 0
            val lb = b?.remaining() ?: 0
            val out = ByteArray(la + lb)
            a?.duplicate()?.get(out, 0, la)
            b?.duplicate()?.get(out, la, lb)
            return out
        }

        private fun align16(v: Int): Int = max(16, (v / 16) * 16)

        /** Ekran oranını koruyarak donanım kodlayıcının desteklediği en yüksek boyutu seçer. */
        fun pickSize(realW: Int, realH: Int, maxLong: Int): Pair<Int, Int> {
            var scale = min(1.0, maxLong.toDouble() / max(realW, realH))
            val caps = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(MIME, true) } }
                    ?.getCapabilitiesForType(MIME)?.videoCapabilities
            } catch (_: Exception) {
                null
            }
            repeat(25) {
                val w = align16((realW * scale).toInt())
                val h = align16((realH * scale).toInt())
                if (caps == null || caps.isSizeSupported(w, h)) return w to h
                scale *= 0.92
            }
            return if (realW >= realH) 1280 to 720 else 720 to 1280
        }
    }
}
