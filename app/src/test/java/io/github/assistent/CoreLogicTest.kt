package io.github.assistent

import io.github.assistent.brain.Brain
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.Emotion
import io.github.assistent.voice.WakeWordEngine
import io.github.assistent.tools.WakeOnLan
import io.github.assistent.tools.TvAdbWire
import io.github.assistent.tools.TvAdbClient
import io.github.assistent.brain.CapabilityPlanner
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

    @Test fun androidTvAdbFramesServicesWithFourHexLength() {
        assertEquals("000bshell:input", TvAdbWire.frame("shell:input").decodeToString())
    }

    @Test fun androidTvAdbClientCompletesARealWireExchange() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val size = input.readNBytes(4).toString(Charsets.US_ASCII).toInt(16)
                    assertEquals("shell:echo online", input.readNBytes(size).toString(Charsets.UTF_8))
                    socket.getOutputStream().apply {
                        write("OKAY".toByteArray())
                        write("online\n".toByteArray())
                        flush()
                    }
                }
            }
            assertEquals("online", TvAdbClient("127.0.0.1", server.localPort).shell("echo online"))
            future.get()
            executor.shutdownNow()
        }
    }

    @Test fun capabilityPlanningIsGeneralAndRequiresConfirmedResults() {
        val prompt = CapabilityPlanner.instructions()
        org.junit.Assert.assertTrue(prompt.contains("not only televisions"))
        org.junit.Assert.assertTrue(prompt.contains("confirmed by its tool result"))
        org.junit.Assert.assertTrue(prompt.contains("Independent steps may run in parallel"))
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
