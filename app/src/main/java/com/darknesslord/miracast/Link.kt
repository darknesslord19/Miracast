package com.darknesslord.miracast

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Telefon -> TV bağlantısı. Ağ yavaşlarsa birikmeyi önlemek için ara kareleri
 * atar ve yeni anahtar kare ister; böylece gecikme büyümez ve görüntü donmaz.
 */
class Link(private val socket: Socket, private val onKeyRequest: () -> Unit) {
    private val queue = LinkedBlockingQueue<Packet>()
    private val queuedBytes = AtomicLong()
    @Volatile var alive = true
        private set
    @Volatile private var waitKey = true

    fun start() {
        Thread(::writeLoop, "link-writer").apply { isDaemon = true; start() }
        Thread(::readLoop, "link-reader").apply { isDaemon = true; start() }
    }

    /** Biçim ve SPS/PPS gibi kontrol paketleri hiç atılmaz. */
    fun sendControl(p: Packet) {
        if (!alive) return
        queuedBytes.addAndGet(p.data.size.toLong())
        queue.offer(p)
    }

    fun send(p: Packet) {
        if (!alive) return
        val backlog = queuedBytes.get()
        when (p.type) {
            Proto.T_VIDEO -> {
                val key = (p.flags and Proto.F_KEY) != 0
                if (waitKey && !key) return
                if (key) waitKey = false
                if (!key && backlog > 2_500_000) {
                    waitKey = true
                    onKeyRequest()
                    return
                }
                if (key && backlog > 8_000_000) {
                    // Ağ tamamen tıkandı; bekleyen eski görüntüleri at.
                    dropStaleVideo()
                }
            }
            Proto.T_AUDIO -> if (backlog > 1_500_000) return
        }
        queuedBytes.addAndGet(p.data.size.toLong())
        queue.offer(p)
    }

    private fun dropStaleVideo() {
        val keep = ArrayList<Packet>()
        queue.drainTo(keep)
        var bytes = 0L
        for (q in keep) {
            if (q.type == Proto.T_VIDEO) continue
            bytes += q.data.size
            queue.offer(q)
        }
        queuedBytes.set(bytes)
    }

    private fun writeLoop() {
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
            val ping = Packet(Proto.T_PING, 0, 0, Proto.EMPTY)
            while (alive) {
                val p = queue.poll(1, TimeUnit.SECONDS)
                if (p == null) {
                    PacketIO.write(out, ping)
                    out.flush()
                    continue
                }
                queuedBytes.addAndGet(-p.data.size.toLong())
                PacketIO.write(out, p)
                if (queue.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun readLoop() {
        try {
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (alive) {
                val p = PacketIO.read(inp)
                if (p.type == Proto.T_REQ_KEY) onKeyRequest()
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    fun close() {
        alive = false
        try { socket.close() } catch (_: Exception) { }
        queue.clear()
    }
}
