package io.github.assistent

import io.github.assistent.core.Operations
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationsTest {
    @Test fun aToolsOwnWordsDecideTheState() {
        assertEquals("succeeded", Operations.outcome("TV Teste: com.google.android.youtube aberto (confirmado)"))
        assertEquals("failed", Operations.outcome("TV Teste: FALHOU - sem conexão ADB"))
        assertEquals("failed", Operations.outcome("error: timeout"))
        assertEquals("failed", Operations.outcome("TV 'Quarto' não cadastrada"))
        assertEquals("failed", Operations.outcome("TV Teste: app 'netflix' não está instalado"))
        assertEquals("unknown", Operations.outcome("TV Teste: comando enviado; estado da mídia: unknown (sem confirmação)"))
        assertEquals("unknown", Operations.outcome("wake-on-LAN sent to PC; status unknown; configure its IP"))
        assertEquals("succeeded", Operations.outcome("created #3 for Tue 14/10/2026 18:00 (reminder, repeat none)"))
    }

    @Test fun toolsMapToVisualFamilies() {
        assertEquals("search", Operations.kind("web_search"))
        assertEquals("tv", Operations.kind("tv_control"))
        assertEquals("time", Operations.kind("create_reminder"))
        assertEquals("webhook", Operations.kind("hook_portao"))
    }
}
