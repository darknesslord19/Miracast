package com.darknesslord.miracast

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/** Telefon ile TV arasındaki basit, düşük gecikmeli akış protokolü. */
object Proto {
    const val PORT = 47800
    const val BEACON_PORT = 47801
    const val MAGIC = "DLMIRROR1"

    const val T_FORMAT = 1   // video boyutu (w,h)
    const val T_CSD = 2      // H.264 SPS/PPS
    const val T_VIDEO = 3    // H.264 kare
    const val T_AUDIO = 4    // PCM 48kHz stereo 16-bit
    const val T_PING = 5     // canlılık sinyali
    const val T_REQ_KEY = 6  // alıcı anahtar kare istiyor

    const val F_KEY = 1

    const val SAMPLE_RATE = 48000
    const val MAX_PAYLOAD = 16 * 1024 * 1024
    val EMPTY = ByteArray(0)
}

class Packet(val type: Int, val flags: Int, val pts: Long, val data: ByteArray)

object PacketIO {
    fun write(out: DataOutputStream, p: Packet) {
        out.writeByte(p.type)
        out.writeByte(p.flags)
        out.writeInt(p.data.size)
        out.writeLong(p.pts)
        out.write(p.data)
    }

    fun read(inp: DataInputStream): Packet {
        val type = inp.readUnsignedByte()
        val flags = inp.readUnsignedByte()
        val len = inp.readInt()
        val pts = inp.readLong()
        if (len < 0 || len > Proto.MAX_PAYLOAD) throw IOException("Geçersiz paket boyu: $len")
        val data = if (len == 0) Proto.EMPTY else ByteArray(len)
        if (len > 0) inp.readFully(data)
        return Packet(type, flags, pts, data)
    }

    fun format(w: Int, h: Int): Packet =
        Packet(Proto.T_FORMAT, 0, 0, ByteBuffer.allocate(8).putInt(w).putInt(h).array())

    fun parseFormat(p: Packet): Pair<Int, Int> {
        val b = ByteBuffer.wrap(p.data)
        return b.int to b.int
    }
}

object Net {
    fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (ia.address is Inet4Address) out.add(b)
                }
            }
        } catch (_: Exception) {
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return out
    }

    fun localIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return "?"
            var fallback: String? = null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        val name = ni.name.lowercase()
                        if (name.startsWith("wlan") || name.startsWith("eth")) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            }
            return fallback ?: "?"
        } catch (_: Exception) {
            return "?"
        }
    }
}
