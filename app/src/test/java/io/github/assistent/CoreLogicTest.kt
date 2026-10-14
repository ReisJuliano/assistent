package io.github.assistent

import io.github.assistent.brain.Brain
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.Emotion
import io.github.assistent.voice.WakeWordEngine
import io.github.assistent.tools.WakeOnLan
import io.github.assistent.brain.CapabilityPlanner
import io.github.assistent.tools.DeviceDiscovery
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreLogicTest {
    private val phrases = listOf("ei nexo", "oi nexo", "olá nexo", "nexo", "anexo")
        .map(WakeWordEngine::words).sortedByDescending { it.size }

    @Test fun wakePhraseReturnsCommandAfterIt() {
        assertEquals("que horas são", WakeWordEngine.match("ei nexo que horas são", phrases, 0.75f))
    }

    @Test fun wakeIgnoresAccentsAndSmallMisrecognitions() {
        assertEquals("", WakeWordEngine.match("ola nexo", phrases, 0.75f))
        assertEquals("liga a luz", WakeWordEngine.match("ei nexu liga a luz", phrases, 0.75f))
    }

    @Test fun wakeDoesNotFireOnUnrelatedSpeech() {
        assertNull(WakeWordEngine.match("vamos almoçar mais tarde", phrases, 0.75f))
    }

    @Test fun wakeMatchingDoesNotDependOnTheDefaultAssistantName() {
        val custom = listOf("ei osvaldo", "osvaldo").map(WakeWordEngine::words)
        assertEquals("acende a luz", WakeWordEngine.match("ei oswaldo acende a luz", custom, 0.75f))
    }

    @Test fun calibrationExtractsOnlyTheSpokenName() {
        assertEquals("Oswaldo", WakeWordEngine.nameCandidate("ei Oswaldo"))
        assertEquals("Rubia", WakeWordEngine.nameCandidate("Rubia"))
    }

    @Test fun wakeOnLanBuildsAValidMagicPacket() {
        val mac = WakeOnLan.parseMac("AA:bb:01:02:03:04")!!
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertEquals(0xFF, packet[0].toInt() and 0xFF)
        assertEquals(0xAA, packet[6].toInt() and 0xFF)
        assertEquals(0x04, packet.last().toInt() and 0xFF)
    }

    @Test fun wakeOnLanRejectsMalformedMac() {
        assertNull(WakeOnLan.parseMac("AA:BB:CC"))
        assertNull(WakeOnLan.parseMac("GG:BB:CC:DD:EE:FF"))
    }

    @Test fun capabilityPlanningIsGeneralAndRequiresConfirmedResults() {
        val prompt = CapabilityPlanner.instructions()
        org.junit.Assert.assertTrue(prompt.contains("not only televisions"))
        org.junit.Assert.assertTrue(prompt.contains("confirmed by its tool result"))
        org.junit.Assert.assertTrue(prompt.contains("Independent steps may run in parallel"))
    }

    @Test fun ssdpHeadersAreCaseInsensitiveInTheDiscoveryParser() {
        val method = DeviceDiscovery::class.java.getDeclaredMethod("parseHeaders", String::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val headers = method.invoke(DeviceDiscovery, "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:8008/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n") as Map<String, String>
        assertEquals("http://192.168.1.20:8008/desc.xml", headers["location"])
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", headers["st"])
    }

    @Test fun emotionTagIsParsedAndStripped() {
        val (e, text) = Brain.parseEmotion("[joy] **Oba!** Vai fazer sol amanhã.")
        assertEquals(Emotion.JOY, e)
        assertEquals("Oba! Vai fazer sol amanhã.", text)
    }

    @Test fun missingTagFallsBackToNeutral() {
        assertEquals(Emotion.NEUTRAL, Brain.parseEmotion("Sem tag").first)
    }

    @Test fun configPatchMergesDeeply() {
        val base = Json.parseToJsonElement("""{"face":{"color":"#3DE8FF","glow":0.6},"llm":{"model":"a"}}""")
        val patch = Json.parseToJsonElement("""{"face":{"color":"#FF0000"}}""")
        val merged = ConfigStore.merge(base, patch).jsonObject
        assertEquals("#FF0000", merged["face"]!!.jsonObject["color"]!!.jsonPrimitive.content)
        assertEquals("0.6", merged["face"]!!.jsonObject["glow"]!!.jsonPrimitive.content)
        assertEquals("a", merged["llm"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }
}
