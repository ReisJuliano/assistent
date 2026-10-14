package io.github.assistent.tools

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

internal object WakeOnLan {
    fun parseMac(value: String): ByteArray? {
        val parts = value.trim().split(':', '-', ' ', '.')
            .filter(String::isNotBlank)
        if (parts.size != 6 || parts.any { it.length !in 1..2 }) return null
        return parts.map { it.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
    }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must contain six bytes" }
        return ByteArray(102).also { packet ->
            repeat(6) { packet[it] = 0xFF.toByte() }
            repeat(16) { index -> mac.copyInto(packet, 6 + index * 6) }
        }
    }

    fun send(mac: ByteArray, broadcast: String, port: Int) {
        val packet = magicPacket(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port.coerceIn(1, 65535)))
        }
    }
}
