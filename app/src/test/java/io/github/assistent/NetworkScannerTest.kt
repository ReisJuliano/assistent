package io.github.assistent

import io.github.assistent.tools.NetworkScanner
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkScannerTest {
    /** RFC 1002 node status response: header, echoed name, type/class, TTL, rdlength, names table, MAC. */
    private fun nodeStatus(names: List<Triple<String, Int, Int>>, mac: ByteArray): ByteArray {
        val req = NetworkScanner.netbiosRequest()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x42, 0x42, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        out.write(req, 12, 34) // encoded name (0x20 + 32 bytes + 0)
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0))
        val rdata = java.io.ByteArrayOutputStream()
        rdata.write(names.size)
        names.forEach { (n, suffix, flags) ->
            rdata.write(n.padEnd(15).toByteArray(Charsets.US_ASCII)); rdata.write(suffix); rdata.write(flags shr 8); rdata.write(flags and 0xFF)
        }
        rdata.write(mac)
        rdata.write(ByteArray(40))
        val r = rdata.toByteArray()
        out.write(r.size shr 8); out.write(r.size and 0xFF); out.write(r)
        return out.toByteArray()
    }

    @Test fun readsTheComputerNameAndMac() {
        val resp = nodeStatus(
            listOf(Triple("WORKGROUP", 0x00, 0x8400), Triple("PC-DIOGO", 0x00, 0x0400), Triple("PC-DIOGO", 0x20, 0x0400)),
            byteArrayOf(0x3c, 0x7c, 0x3f, 0x79, 0x7c, 0xad.toByte()),
        )
        assertEquals("PC-DIOGO" to "3c:7c:3f:79:7c:ad", NetworkScanner.parseNetbios(resp))
    }

    @Test fun requestMatchesTheSpec() = assertEquals(
        "42420000000100000000000020434b4141414141414141414141414141414141414141414141414141414141410000210001",
        NetworkScanner.netbiosRequest().joinToString("") { "%02x".format(it) },
    )

    @Test fun directedBroadcast() {
        assertEquals("192.168.1.255", NetworkScanner.directedBroadcast("192.168.1.14", 24))
        assertEquals("10.0.3.255", NetworkScanner.directedBroadcast("10.0.2.15", 23))
    }

    @Test fun classifiesByPorts() {
        assertEquals("tv", NetworkScanner.classify(listOf(5555), emptyList(), null))
        assertEquals("tv", NetworkScanner.classify(listOf(8002, 8001), emptyList(), null))
        assertEquals("pc", NetworkScanner.classify(listOf(135, 445), emptyList(), null))
        assertEquals("cast", NetworkScanner.classify(listOf(8008, 8009), emptyList(), null))
    }
}
