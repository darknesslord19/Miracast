package com.darknesslord.miracast

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class TvInfo(val name: String, val host: String, val lastSeen: Long)

/** TV tarafı: ağa her saniye "buradayım" yayını yapar. */
class Beacon(private val name: String) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                val msg = "${Proto.MAGIC}|$name".toByteArray(Charsets.UTF_8)
                while (running) {
                    for (addr in Net.broadcastAddresses()) {
                        try {
                            sock.send(DatagramPacket(msg, msg.size, addr, Proto.BEACON_PORT))
                        } catch (_: Exception) {
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
            } finally {
                sock?.close()
            }
        }, "beacon").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}

/** Telefon tarafı: ağdaki TV'leri otomatik bulur. */
class TvFinder(context: Context, private val onChange: (List<TvInfo>) -> Unit) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private val tvs = LinkedHashMap<String, TvInfo>()

    @Volatile var current: List<TvInfo> = emptyList()
        private set

    fun start() {
        if (running) return
        running = true
        try {
            lock = wifi.createMulticastLock("mirror-discovery").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
        Thread({
            while (running) {
                try {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 1000
                        bind(InetSocketAddress(Proto.BEACON_PORT))
                    }
                    socket = s
                    val buf = ByteArray(512)
                    while (running) {
                        val now = System.currentTimeMillis()
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val text = String(p.data, 0, p.length, Charsets.UTF_8)
                            if (text.startsWith(Proto.MAGIC + "|")) {
                                val host = p.address.hostAddress ?: continue
                                val name = text.substringAfter("|").ifBlank { host }
                                synchronized(tvs) { tvs[host] = TvInfo(name, host, now) }
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                        publish(now)
                    }
                    s.close()
                } catch (_: Exception) {
                    if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                }
            }
        }, "tv-finder").apply { isDaemon = true; start() }
    }

    private fun publish(now: Long) {
        val list: List<TvInfo>
        synchronized(tvs) {
            tvs.entries.removeAll { now - it.value.lastSeen > 5000 }
            list = tvs.values.toList()
        }
        val changed = list.map { it.host to it.name } != current.map { it.host to it.name }
        current = list
        if (changed) onChange(list)
    }

    fun stop() {
        running = false
        socket?.close()
        try { lock?.release() } catch (_: Exception) { }
        lock = null
    }
}
