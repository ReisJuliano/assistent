package io.github.assistent

import io.github.assistent.tools.SpotifyDevice
import io.github.assistent.tools.SpotifyDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyDevicesTest {
    private val pc = SpotifyDevice("1", "DESKTOP-DIOGO", "Computer", false)
    private val tv = SpotifyDevice("2", "TV da Sala", "TV", false)
    private val phone = SpotifyDevice("3", "SM-S928B", "Smartphone", false)

    @Test fun severalInactiveDevicesMeansAsk() = assertNull(SpotifyDevices.choose(listOf(pc, tv), "", "SM-S928B"))
    @Test fun theActiveOneWins() = assertEquals("2", SpotifyDevices.choose(listOf(pc, tv.copy(active = true)), "", "x")!!.id)
    @Test fun aSingleDeviceIsUsed() = assertEquals("1", SpotifyDevices.choose(listOf(pc), "", "x")!!.id)
    @Test fun byNameTypeOrHere() {
        assertEquals("2", SpotifyDevices.choose(listOf(pc, tv, phone), "tv", "SM-S928B")!!.id)
        assertEquals("1", SpotifyDevices.choose(listOf(pc, tv, phone), "no computador", "SM-S928B")!!.id)
        assertEquals("3", SpotifyDevices.choose(listOf(pc, tv, phone), "aqui", "SM-S928B")!!.id)
        assertEquals("2", SpotifyDevices.choose(listOf(pc, tv, phone), "sala", "SM-S928B")!!.id)
    }

    @Test fun parsesTheApi() {
        val d = SpotifyDevices.parse("""{"devices":[{"id":"a","is_active":true,"name":"Echo","type":"Speaker"}]}""")
        assertEquals(SpotifyDevice("a", "Echo", "Speaker", true), d.single())
    }
}
