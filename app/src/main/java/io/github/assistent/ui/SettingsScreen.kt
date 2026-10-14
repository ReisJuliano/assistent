package io.github.assistent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.assistent.AppGraph
import io.github.assistent.config.AppConfig
import io.github.assistent.config.WebhookDef
import io.github.assistent.core.Activity
import io.github.assistent.core.Emotion
import io.github.assistent.face.RobotFace
import io.github.assistent.face.parseHexColor
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val FACE_COLORS = listOf("#3DE8FF", "#5CFFB0", "#FFD54A", "#FF8A3D", "#FF6FAE", "#B48CFF", "#FFFFFF", "#FF5A4E")

/**
 * Every change is written to config immediately and applied live (face, voice, wake phrases, API…).
 * Two panes in landscape/tablets (preview left), one column in portrait.
 */
@Composable
fun SettingsScreen(graph: AppGraph, onClose: () -> Unit) {
    val cfg by graph.config.config.collectAsState()
    val up: ((AppConfig) -> AppConfig) -> Unit = { graph.config.update(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth > 720.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(cfg.personality.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    StatusPill(graph)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Fechar") }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FacePreview(cfg, Modifier.weight(0.42f).fillMaxHeight().padding(bottom = 16.dp))
                        SettingsList(graph, cfg, up, Modifier.weight(0.58f), preview = false, onClose = onClose)
                    }
                } else {
                    SettingsList(graph, cfg, up, Modifier.fillMaxSize().padding(horizontal = 16.dp), preview = true, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(graph: AppGraph) {
    val s by graph.runtime.state.collectAsState()
    val (label, color) = when {
        s.wakeModelProgress != null -> "baixando modelo ${(s.wakeModelProgress!! * 100).roundToInt()}%" to Color(0xFFFFD54A)
        s.wakeReady -> "ouvindo" to Color(0xFF5CFFB0)
        s.serviceRunning -> "toque para falar" to Accent
        else -> "parado" to Color(0xFF8C9BA5)
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun FacePreview(cfg: AppConfig, modifier: Modifier = Modifier) {
    var emotion by remember { mutableStateOf(Emotion.HAPPY) }
    var activity by remember { mutableStateOf(Activity.IDLE) }
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))) {
        Box(Modifier.fillMaxWidth().weight(1f, fill = true).aspectRatio(1.6f, matchHeightConstraintsFirst = false)) {
            RobotFace(
                activity = activity, emotion = emotion, gaze = io.github.assistent.core.Gaze(), face = cfg.face,
                audioLevel = { if (activity == Activity.SPEAKING) abs(sin(System.nanoTime() / 9e7)).toFloat() * 0.8f else 0f },
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Emotion.entries.forEach { e ->
                FilterChip(selected = activity == Activity.IDLE && emotion == e, onClick = { emotion = e; activity = Activity.IDLE }, label = { Text(EMOTION_LABELS[e] ?: e.tag) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Activity.LISTENING, Activity.THINKING, Activity.SEARCHING, Activity.SPEAKING, Activity.SLEEPING).forEach { a ->
                FilterChip(selected = activity == a, onClick = { activity = if (activity == a) Activity.IDLE else a }, label = { Text(ACTIVITY_LABELS[a] ?: a.name) })
            }
        }
    }
}

private val TONE_LABELS = listOf(
    "natural" to "Natural", "alegre" to "Alegre", "descontraido" to "Descontraído", "rispido" to "Ríspido", "formal" to "Formal",
    "sarcastico" to "Sarcástico", "fofo" to "Fofo", "dramatico" to "Dramático", "custom" to "Personalizado",
)

private val EYE_STYLES = listOf(
    "solid" to "Pílula sólida", "iris" to "Íris escura", "cartoon" to "Cartoon", "ring" to "Anel", "orb" to "Orbe",
)

private val EMOTION_LABELS = mapOf(
    Emotion.NEUTRAL to "Neutro", Emotion.HAPPY to "Feliz", Emotion.JOY to "Alegria", Emotion.SAD to "Triste",
    Emotion.SURPRISED to "Surpreso", Emotion.CONFUSED to "Dúvida", Emotion.ANGRY to "Bravo", Emotion.LOVE to "Amor",
    Emotion.THINKING to "Pensativo", Emotion.SLEEPY to "Sonolento",
)
private val ACTIVITY_LABELS = mapOf(
    Activity.LISTENING to "Ouvindo", Activity.THINKING to "Pensando", Activity.SEARCHING to "Pesquisando",
    Activity.SPEAKING to "Falando", Activity.SLEEPING to "Dormindo",
)

@Composable
private fun SettingsList(
    graph: AppGraph, cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit, modifier: Modifier, preview: Boolean, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (preview) item { FacePreview(cfg, Modifier.fillMaxWidth().height(300.dp)) }

        item {
            Section("Rosto") {
                Label("Cor")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FACE_COLORS.forEach { hex ->
                        val selected = cfg.face.color.equals(hex, ignoreCase = true)
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(parseHexColor(hex))
                                .border(BorderStroke(if (selected) 3.dp else 0.dp, Color.White), CircleShape)
                                .clickable { up { it.copy(face = it.face.copy(color = hex)) } },
                        )
                    }
                }
                Field("Cor personalizada (#RRGGBB)", cfg.face.color) { v -> if (Regex("#[0-9a-fA-F]{6}").matches(v)) up { it.copy(face = it.face.copy(color = v)) } }
                SliderRow("Tamanho do rosto", cfg.face.scale, 0.5f..1.5f) { v -> up { it.copy(face = it.face.copy(scale = v)) } }
                SliderRow("Tamanho dos olhos", cfg.face.eyeSize, 0.6f..1.6f) { v -> up { it.copy(face = it.face.copy(eyeSize = v)) } }
                SliderRow("Distância entre olhos", cfg.face.eyeSpacing, 0.6f..1.6f) { v -> up { it.copy(face = it.face.copy(eyeSpacing = v)) } }
                SliderRow("Arredondamento", cfg.face.eyeRoundness, 0f..1f) { v -> up { it.copy(face = it.face.copy(eyeRoundness = v)) } }
                SliderRow("Altura dos olhos", cfg.face.eyeAspect, 0.6f..1.8f) { v -> up { it.copy(face = it.face.copy(eyeAspect = v)) } }
                Label("Estilo dos olhos")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EYE_STYLES.forEach { (key, label) ->
                        FilterChip(selected = cfg.face.eyeStyle == key, onClick = { up { it.copy(face = it.face.copy(eyeStyle = key)) } }, label = { Text(label) })
                    }
                }
                if (cfg.face.eyeStyle != "solid") {
                    SliderRow("Tamanho da íris", cfg.face.irisSize, 0.3f..0.9f) { v -> up { it.copy(face = it.face.copy(irisSize = v)) } }
                    SliderRow("Tamanho da pupila", cfg.face.pupilSize, 0.2f..0.8f) { v -> up { it.copy(face = it.face.copy(pupilSize = v)) } }
                }
                SwitchRow("Sobrancelhas", "Acompanham a emoção", cfg.face.showBrows) { v -> up { it.copy(face = it.face.copy(showBrows = v)) } }
                if (cfg.face.showBrows) {
                    SliderRow("Espessura das sobrancelhas", cfg.face.browThickness, 0.5f..2.5f) { v -> up { it.copy(face = it.face.copy(browThickness = v)) } }
                }
                SwitchRow("Bracinhos", "Seguram objetos e fazem gestos", cfg.face.showArms) { v -> up { it.copy(face = it.face.copy(showArms = v)) } }
                if (cfg.face.showArms) SwitchRow("Mãozinhas", "Mãos redondas na ponta dos braços", cfg.face.armHands) { v -> up { it.copy(face = it.face.copy(armHands = v)) } }
                SwitchRow("Mostrar boca", checked = cfg.face.showMouth) { v -> up { it.copy(face = it.face.copy(showMouth = v)) } }
                if (cfg.face.showMouth) {
                    SliderRow("Tamanho da boca", cfg.face.mouthSize, 0.5f..1.6f) { v -> up { it.copy(face = it.face.copy(mouthSize = v)) } }
                    SliderRow("Espessura da boca", cfg.face.mouthThickness, 0.5f..3f) { v -> up { it.copy(face = it.face.copy(mouthThickness = v)) } }
                }
                SliderRow("Brilho neon", cfg.face.glow, 0f..1.5f) { v -> up { it.copy(face = it.face.copy(glow = v)) } }
                SliderRow("Posição vertical", cfg.face.offsetY, -0.3f..0.3f) { v -> up { it.copy(face = it.face.copy(offsetY = v)) } }
            }
        }

        item {
            Section("Animação e tela") {
                SliderRow("Velocidade das expressões", cfg.face.animationStiffness, 30f..400f, format = { "${it.roundToInt()}" }) { v -> up { it.copy(face = it.face.copy(animationStiffness = v)) } }
                SliderRow("Elasticidade (menor = mais quique)", cfg.face.animationDamping, 0.4f..1f) { v -> up { it.copy(face = it.face.copy(animationDamping = v)) } }
                SwitchRow("Movimentos espontâneos", "Olhar ao redor, inclinar a cabeça quando ocioso", cfg.face.idleMotion) { v -> up { it.copy(face = it.face.copy(idleMotion = v)) } }
                SwitchRow("Seguir rosto com a câmera", "Detecção local, nada é gravado", cfg.face.cameraTracking) { v -> up { it.copy(face = it.face.copy(cameraTracking = v)) } }
                SwitchRow("Legendas", "Mostra o que ouviu e respondeu", cfg.face.showSubtitles) { v -> up { it.copy(face = it.face.copy(showSubtitles = v)) } }
                SwitchRow("Relógio no canto", "Relógio analógico sempre visível", cfg.face.showClock) { v -> up { it.copy(face = it.face.copy(showClock = v)) } }
                if (cfg.face.showClock) {
                    Segmented(listOf("digital" to "Digital retrô", "analog" to "Analógico"), cfg.face.clockStyle) { v ->
                        up { it.copy(face = it.face.copy(clockStyle = v)) }
                    }
                    Segmented(listOf("top_left" to "↖", "top_right" to "↗", "bottom_left" to "↙", "bottom_right" to "↘"), cfg.face.clockCorner) { v ->
                        up { it.copy(face = it.face.copy(clockCorner = v)) }
                    }
                }
                SwitchRow("Manter tela ligada", checked = cfg.face.keepScreenOn) { v -> up { it.copy(face = it.face.copy(keepScreenOn = v)) } }
                Label("Orientação")
                Segmented(listOf("auto" to "Automática", "portrait" to "Vertical", "landscape" to "Horizontal"), cfg.face.orientation) { v ->
                    up { it.copy(face = it.face.copy(orientation = v)) }
                }
            }
        }

        item {
            var result by remember { mutableStateOf<String?>(null) }
            Section("Cérebro (IA)") {
                Hint("Endpoint compatível com OpenAI. Padrão: CLIProxyAPI com login ChatGPT (OAuth), ex. http://IP-DO-PC:8317/v1")
                Field("Base URL", cfg.llm.baseUrl, keyboard = KeyboardType.Uri) { v -> up { it.copy(llm = it.llm.copy(baseUrl = v.trim())) } }
                Field("API key do proxy", cfg.llm.apiKey, secret = true) { v -> up { it.copy(llm = it.llm.copy(apiKey = v.trim())) } }
                Field("Modelo", cfg.llm.model) { v -> up { it.copy(llm = it.llm.copy(model = v.trim())) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        result = "testando…"
                        scope.launch {
                            result = runCatching { graph.llm.listModels(graph.config.current.llm) }
                                .fold({ "Conectado ✓  Modelos: " + it.joinToString().ifBlank { "(nenhum listado)" } }, { "Erro: ${it.message}" })
                        }
                    }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Testar conexão") }
                    TextButton(onClick = { graph.brain.clearHistory(); result = "Conversa reiniciada" }) { Text("Limpar conversa") }
                }
                result?.let { Hint(it) }
            }
        }

        item {
            Section("Personalidade") {
                Field("Nome do assistente", cfg.personality.name) { v -> if (v.isNotBlank()) up { it.copy(personality = it.personality.copy(name = v.trim())) } }
                Field("Seu nome", cfg.personality.userName) { v -> up { it.copy(personality = it.personality.copy(userName = v.trim())) } }
                Label("Tom das respostas")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TONE_LABELS.forEach { (key, label) ->
                        FilterChip(selected = cfg.personality.tone == key, onClick = { up { it.copy(personality = it.personality.copy(tone = key)) } }, label = { Text(label) })
                    }
                }
                if (cfg.personality.tone == "custom") {
                    Field("Descreva o tom (ex.: fala como um pirata)", cfg.personality.customTone, singleLine = false) { v -> up { it.copy(personality = it.personality.copy(customTone = v)) } }
                }
                Label("Piadinhas sobre o assunto")
                Segmented(listOf("auto" to "Auto", "always" to "Sempre", "never" to "Nunca"), cfg.personality.jokes) { v ->
                    up { it.copy(personality = it.personality.copy(jokes = v)) }
                }
                Hint("Auto: só nos tons descontraído, sarcástico, dramático e alegre. Nunca em assuntos sérios.")
                SwitchRow("Lembretes com frases criativas", "A IA reescreve cada lembrete no tom escolhido (sem IA, usa frases prontas)", cfg.reminders.aiPhrases) { v ->
                    up { it.copy(reminders = it.reminders.copy(aiPhrases = v)) }
                }
                Label("Tamanho das respostas")
                Segmented(listOf("short" to "Curta", "medium" to "Média", "detailed" to "Detalhada"), cfg.personality.verbosity) { v ->
                    up { it.copy(personality = it.personality.copy(verbosity = v)) }
                }
                Field("Jeito de ser", cfg.personality.traits, singleLine = false) { v -> up { it.copy(personality = it.personality.copy(traits = v)) } }
                Field("Idioma (ex. pt-BR)", cfg.personality.language) { v -> up { it.copy(personality = it.personality.copy(language = v.trim())) } }
                Field("Instruções extras", cfg.personality.extraInstructions, singleLine = false) { v -> up { it.copy(personality = it.personality.copy(extraInstructions = v)) } }
            }
        }

        item {
            Section("Voz (fala)") {
                Label("Efeito robótico")
                Segmented(listOf("off" to "Nenhum", "light" to "Leve", "classic" to "Clássico", "heavy" to "Pesado"), cfg.tts.robotVoice) { v ->
                    up { it.copy(tts = it.tts.copy(robotVoice = v)) }
                }
                Segmented(listOf("android" to "Sistema", "openai" to "OpenAI / Kokoro", "elevenlabs" to "ElevenLabs"), cfg.tts.provider) { v ->
                    up { it.copy(tts = it.tts.copy(provider = v)) }
                }
                when (cfg.tts.provider) {
                    "openai" -> {
                        Hint("OpenAI ou qualquer servidor compatível (/audio/speech), ex. Kokoro-FastAPI local: http://IP:8880/v1, modelo kokoro, voz pf_dora.")
                        Field("Base URL", cfg.tts.baseUrl, keyboard = KeyboardType.Uri) { v -> up { it.copy(tts = it.tts.copy(baseUrl = v.trim())) } }
                        Field("API key", cfg.tts.apiKey, secret = true) { v -> up { it.copy(tts = it.tts.copy(apiKey = v.trim())) } }
                        Field("Modelo", cfg.tts.model) { v -> up { it.copy(tts = it.tts.copy(model = v.trim())) } }
                        Field("Voz", cfg.tts.voice) { v -> up { it.copy(tts = it.tts.copy(voice = v.trim())) } }
                        Field("Estilo da voz (gpt-4o-mini-tts)", cfg.tts.instructions, singleLine = false) { v -> up { it.copy(tts = it.tts.copy(instructions = v)) } }
                    }
                    "elevenlabs" -> {
                        Field("API key", cfg.tts.apiKey, secret = true) { v -> up { it.copy(tts = it.tts.copy(apiKey = v.trim())) } }
                        Field("Voice ID", cfg.tts.elevenLabsVoiceId) { v -> up { it.copy(tts = it.tts.copy(elevenLabsVoiceId = v.trim())) } }
                        Field("Modelo", cfg.tts.elevenLabsModel) { v -> up { it.copy(tts = it.tts.copy(elevenLabsModel = v.trim())) } }
                    }
                    else -> {
                        Hint("Voz do sistema. Para uma voz melhor sem custo, instale/ative vozes de alta qualidade nas configurações de TTS do Android, ou use Kokoro local.")
                        Field("Nome da voz (vazio = melhor disponível)", cfg.tts.androidVoice) { v -> up { it.copy(tts = it.tts.copy(androidVoice = v.trim())) } }
                        SwitchRow("Preferir voz masculina", "Escolhe automaticamente uma voz masculina quando o Android informar essa opção.", cfg.tts.androidPreferMale) { v ->
                            up { it.copy(tts = it.tts.copy(androidPreferMale = v)) }
                        }
                        SwitchRow("Voz robótica", "Baixa um pouco o tom e deixa o ritmo mais sintético.", cfg.tts.androidRobot) { v ->
                            up { it.copy(tts = it.tts.copy(androidRobot = v)) }
                        }
                        SliderRow("Tom", cfg.tts.pitch, 0.5f..2f) { v -> up { it.copy(tts = it.tts.copy(pitch = v)) } }
                    }
                }
                SliderRow("Velocidade", cfg.tts.speed, 0.6f..1.6f) { v -> up { it.copy(tts = it.tts.copy(speed = v)) } }
                Button(onClick = { graph.scope.launch { graph.runtime.say("Oi! Eu sou ${cfg.personality.name}. Essa é a minha voz.", Emotion.HAPPY) } }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Ouvir voz")
                }
            }
        }

        item {
            Section("Reconhecimento de fala") {
                Segmented(listOf("android" to "Sistema", "openai" to "Whisper API"), cfg.stt.provider) { v -> up { it.copy(stt = it.stt.copy(provider = v)) } }
                if (cfg.stt.provider == "openai") {
                    Field("Base URL", cfg.stt.baseUrl, keyboard = KeyboardType.Uri) { v -> up { it.copy(stt = it.stt.copy(baseUrl = v.trim())) } }
                    Field("API key", cfg.stt.apiKey, secret = true) { v -> up { it.copy(stt = it.stt.copy(apiKey = v.trim())) } }
                    Field("Modelo", cfg.stt.model) { v -> up { it.copy(stt = it.stt.copy(model = v.trim())) } }
                }
                SliderRow("Silêncio para encerrar (ms)", cfg.stt.silenceMs.toFloat(), 500f..3000f, format = { "${it.roundToInt()}" }) { v ->
                    up { it.copy(stt = it.stt.copy(silenceMs = v.roundToInt())) }
                }
            }
        }

        item {
            val state by graph.runtime.state.collectAsState()
            Section("Ativação por voz") {
                SwitchRow("Palavra de ativação", "Offline (Vosk). Toque no rosto também ativa.", cfg.wake.enabled) { v -> up { it.copy(wake = it.wake.copy(enabled = v)) } }
                Hint("Uma frase por linha. {name} = nome do assistente. Ex.: “ei {name}”, “fala {name}”, “bom dia {name}”.")
                Field("Frases", cfg.wake.phrases.joinToString("\n"), singleLine = false) { v ->
                    up { it.copy(wake = it.wake.copy(phrases = v.lines().map(String::trim).filter(String::isNotEmpty))) }
                }
                Field("Variações do nome (separadas por vírgula)", cfg.wake.nameAliases.joinToString(", ")) { v ->
                    up { it.copy(wake = it.wake.copy(nameAliases = v.split(',').map(String::trim).filter(String::isNotEmpty))) }
                }
                SliderRow("Rigor (maior = menos falsos positivos)", cfg.wake.sensitivity, 0.5f..0.95f) { v -> up { it.copy(wake = it.wake.copy(sensitivity = v)) } }
                SliderRow("Dormir após inatividade (s, 0 = nunca)", cfg.wake.sleepAfterSeconds.toFloat(), 0f..600f, format = { "${it.roundToInt()}" }) { v ->
                    up { it.copy(wake = it.wake.copy(sleepAfterSeconds = v.roundToInt())) }
                }
                SliderRow("Continuar ouvindo após responder (s)", cfg.wake.followUpSeconds.toFloat(), 0f..15f, format = { "${it.roundToInt()}" }) { v ->
                    up { it.copy(wake = it.wake.copy(followUpSeconds = v.roundToInt())) }
                }
                state.wakeModelProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Field("URL do modelo Vosk", cfg.wake.modelUrl, keyboard = KeyboardType.Uri) { v -> up { it.copy(wake = it.wake.copy(modelUrl = v.trim())) } }
                state.error?.let { Hint("Último erro: $it") }
            }
        }

        item {
            Section("Pesquisa e clima") {
                Segmented(listOf("duckduckgo" to "DuckDuckGo", "searxng" to "SearXNG", "brave" to "Brave", "tavily" to "Tavily"), cfg.search.provider) { v ->
                    up { it.copy(search = it.search.copy(provider = v)) }
                }
                when (cfg.search.provider) {
                    "searxng" -> Field("URL do SearXNG", cfg.search.searxngUrl, keyboard = KeyboardType.Uri) { v -> up { it.copy(search = it.search.copy(searxngUrl = v.trim())) } }
                    "brave" -> Field("Brave API key", cfg.search.braveApiKey, secret = true) { v -> up { it.copy(search = it.search.copy(braveApiKey = v.trim())) } }
                    "tavily" -> Field("Tavily API key", cfg.search.tavilyApiKey, secret = true) { v -> up { it.copy(search = it.search.copy(tavilyApiKey = v.trim())) } }
                }
                Field("Local padrão (cidade, estado, país)", cfg.weather.defaultLocation) { v -> up { it.copy(weather = it.weather.copy(defaultLocation = v.trim())) } }
            }
        }

        item { UpdateSection(graph, cfg, up) }

        item { LifeSection(graph, cfg, up, onClose) }

        item { PeopleSection(graph, cfg, up) }

        item { DevicesSection(graph, cfg, up) }

        item { WebhooksSection(cfg, up) }

        item {
            var reveal by remember { mutableStateOf(false) }
            Section("API local") {
                SwitchRow("Ativada", checked = cfg.api.enabled) { v -> up { it.copy(api = it.api.copy(enabled = v)) } }
                SwitchRow("Acessível na rede local", "Desligado = só o próprio celular", cfg.api.allowLan) { v -> up { it.copy(api = it.api.copy(allowLan = v)) } }
                Field("Porta", cfg.api.port.toString(), keyboard = KeyboardType.Number) { v -> v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> up { it.copy(api = it.api.copy(port = p)) } } }
                val url = "http://${localIp() ?: "IP-DO-CELULAR"}:${cfg.api.port}/"
                Hint("Painel web: $url")
                Text(
                    if (reveal) cfg.api.token else "•".repeat(24),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { reveal = !reveal },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copy(context, cfg.api.token) }) { Text("Copiar token") }
                    TextButton(onClick = { graph.config.regenerateToken() }) { Text("Gerar novo") }
                }
            }
        }
    }
}

@Composable
private fun LifeSection(graph: AppGraph, cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit, onClose: () -> Unit) {
    val life = cfg.life
    Section("Vida") {
        Hint("Quando ninguém conversa com ele, faz coisas sozinho: joga, lê, passeia com o cachorro, sonha, reage ao clima de verdade…")
        SwitchRow("Vida própria", checked = life.enabled) { v -> up { it.copy(life = it.life.copy(enabled = v)) } }
        if (life.enabled) {
            SliderRow("Começa após (min sem conversa)", (life.idleSeconds / 60f).coerceAtLeast(1f), 1f..30f, format = { "${it.roundToInt()} min" }) { v ->
                up { it.copy(life = it.life.copy(idleSeconds = v.roundToInt() * 60)) }
            }
            SliderRow("Intervalo mínimo entre atividades", (life.minGapSeconds / 60f).coerceAtLeast(1f), 1f..60f, format = { "${it.roundToInt()} min" }) { v ->
                up { it.copy(life = it.life.copy(minGapSeconds = v.roundToInt() * 60, maxGapSeconds = maxOf(it.life.maxGapSeconds, v.roundToInt() * 60 + 60))) }
            }
            SwitchRow("Sonhos", "Enquanto dorme", life.dreams) { v -> up { it.copy(life = it.life.copy(dreams = v)) } }
            SwitchRow("Acordar sozinho às vezes", "Acorda, olha em volta e pergunta se tem alguém", life.selfWake) { v -> up { it.copy(life = it.life.copy(selfWake = v)) } }
            SwitchRow("Reações ao acordar", "Susto, tontura, sonolência", life.wakeReactions) { v -> up { it.copy(life = it.life.copy(wakeReactions = v)) } }
            SwitchRow("Acordar com movimento", "Se assusta, ou acorda e diz “opa, não vi você aí”", life.wakeOnMotion) { v -> up { it.copy(life = it.life.copy(wakeOnMotion = v)) } }
            SwitchRow("Clima de verdade", "Chuva/neve na tela, sua no calor, treme no frio e escolhe o que fazer conforme o tempo (${cfg.weather.defaultLocation})", life.weatherEffects) { v ->
                up { it.copy(life = it.life.copy(weatherEffects = v)) }
            }
            if (life.weatherEffects) SwitchRow("Clima sempre visível", "Desligado: aparece algumas vezes por dia, com animação", life.weatherAlways) { v ->
                up { it.copy(life = it.life.copy(weatherAlways = v)) }
            }
            SwitchRow("Economia de bateria", "Menos quadros por segundo e processamento mais leve (esquenta menos)", life.powerSave) { v ->
                up { it.copy(life = it.life.copy(powerSave = v)) }
            }
            SwitchRow("Dançar com música ambiente", "Pelo microfone (pode confundir barulho com música). Música do celular sempre vale.", life.roomMusic) { v ->
                up { it.copy(life = it.life.copy(roomMusic = v)) }
            }
            SwitchRow("Imitar a câmera", "Copia inclinação da cabeça, piscadas, fone, xícara, livro…", life.mimic) { v -> up { it.copy(life = it.life.copy(mimic = v)) } }
            Label("Ver agora")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                io.github.assistent.face.Scene.entries.forEach { sc ->
                    FilterChip(selected = false, label = { Text(sc.label) }, onClick = {
                        if (sc.kind == io.github.assistent.face.Scene.Kind.DREAM) graph.runtime.sleep() else graph.runtime.wake(greet = false)
                        graph.runtime.life.play(sc)
                        onClose()
                    })
                }
            }
            Label("Testar clima")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Sol" to io.github.assistent.core.Ambient("clear", 24f), "Chuva" to io.github.assistent.core.Ambient("rain", 18f),
                    "Tempestade" to io.github.assistent.core.Ambient("storm", 20f), "Neve" to io.github.assistent.core.Ambient("snow", -2f),
                    "Calor" to io.github.assistent.core.Ambient("clear", 36f), "Frio" to io.github.assistent.core.Ambient("cloudy", 5f),
                ).forEach { (label, amb) ->
                    FilterChip(selected = false, label = { Text(label) }, onClick = { graph.runtime.life.setAmbient(amb); onClose() })
                }
            }
        }
    }
}

@Composable
private fun PeopleSection(graph: AppGraph, cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val status by graph.people.status.collectAsState()
    val faceState by graph.runtime.state.collectAsState()
    var people by remember { mutableStateOf(emptyList<io.github.assistent.data.PersonEntity>()) }
    var voiceMsg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { people = graph.people.list() }
    val pc = cfg.people
    Section("Pessoas") {
        Hint("Reconhecimento facial no aparelho. Quando alguém conhecido passa, ele puxa assunto pelo nome num balãozinho.")
        SwitchRow("Reconhecer rostos", checked = pc.recognition) { v -> up { it.copy(people = it.people.copy(recognition = v)) } }
        SwitchRow("Cumprimentar quem passa", checked = pc.greet) { v -> up { it.copy(people = it.people.copy(greet = v)) } }
        SwitchRow("Falar o cumprimento em voz alta", checked = pc.greetSpeak) { v -> up { it.copy(people = it.people.copy(greetSpeak = v)) } }
        SwitchRow("Puxar assunto com desconhecidos", checked = pc.greetUnknown) { v -> up { it.copy(people = it.people.copy(greetUnknown = v)) } }
        SwitchRow("Frases criadas pela IA", "Senão usa os bordões prontos", pc.greetWithAi) { v -> up { it.copy(people = it.people.copy(greetWithAi = v)) } }
        SliderRow("Cumprimentar de novo após (min)", pc.greetAfterMinutes.toFloat(), 1f..240f, format = { "${it.roundToInt()}" }) { v ->
            up { it.copy(people = it.people.copy(greetAfterMinutes = v.roundToInt())) }
        }
        SliderRow("Rigor do reconhecimento", pc.threshold, 0.4f..0.78f) { v -> up { it.copy(people = it.people.copy(threshold = v)) } }
        people.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p.notes.isBlank()) p.name else "${p.name} — ${p.notes}",
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = {
                    voiceMsg = "Fale normalmente por uns 10 segundos, ${p.name}… (leia algo em voz alta)"
                    scope.launch {
                        val audio = graph.wakeWord.captureVoice(10, 40_000)
                        voiceMsg = if (audio == null) "Não ouvi voz suficiente. Tente de novo." else graph.voiceIdentity.enroll(p.id, audio)
                    }
                }) { Text("Gravar voz") }
                IconButton(onClick = { scope.launch { graph.people.delete(p.id); refresh++ } }) { Icon(Icons.Default.Delete, "Remover") }
            }
        }
        if (voiceMsg.isNotBlank()) Hint(voiceMsg)
        val voiceStatus by graph.voiceIdentity.status.collectAsState()
        if (voiceStatus.isNotBlank()) Hint(voiceStatus)
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank() && pc.recognition && cfg.face.cameraTracking, onClick = {
            val n = name
            scope.launch { if (graph.people.enroll(n) != null) { name = ""; refresh++ } }
        }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Cadastrar rosto (olhe para a câmera)") }
        if (!cfg.face.cameraTracking) Hint("Ative “Seguir rosto com a câmera” na seção Animação.")
        if (status.isNotBlank()) Hint(status)
        Hint(
            when (faceState.faceStatus) {
                "recognized" -> "Agora vendo ${faceState.faceIdentity} (${(faceState.faceSimilarity * 100).toInt()}%)"
                "unknown" -> "Agora: rosto não reconhecido"
                "recognizing" -> "Agora: reconhecendo rosto..."
                else -> "Agora: nenhum rosto detectado"
            },
        )
        TextButton(onClick = { graph.greeter.testGreeting() }) { Text("Testar cumprimento") }
    }
}

/** Multi-device: room of this device, pair with QR, list of devices and who is where. */
@Composable
private fun DevicesSection(graph: AppGraph, cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val status by graph.mesh.status.collectAsState()
    val scanning by graph.mesh.scanning.collectAsState()
    val presence by graph.mesh.presence.collectAsState()
    var pair by remember { mutableStateOf<io.github.assistent.core.PairInfo?>(null) }
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Section("Dispositivos (casa toda)") {
        Hint("Vários aparelhos, um só robô: compartilham configurações, rostos e memórias, e sabem em que cômodo você está.")
        Field("Cômodo deste aparelho", cfg.device.room) { v -> if (v.isNotBlank()) up { it.copy(device = it.device.copy(room = v.trim())) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { pair = graph.mesh.startPairing() }) { Text("Mostrar QR") }
            FilledTonalButton(onClick = { graph.mesh.scanning.value = !scanning }) { Text(if (scanning) "Cancelar leitura" else "Ler QR de outro aparelho") }
        }
        if (scanning) Hint("Aponte o QR do outro aparelho para a câmera frontal deste.")
        pair?.let { info ->
            val qr = remember(info.uri) { qrBitmap(info.uri) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(qr, contentDescription = "QR de pareamento", modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(8.dp))
                Text("Código ${info.code} · ${info.host}:${info.port}", fontFamily = FontFamily.Monospace)
                Hint("Válido por 5 minutos.")
            }
        }
        Label("Ou digite os dados do outro aparelho")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it }, label = { Text("IP") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(code, { code = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(0.7f))
        }
        Button(enabled = host.isNotBlank() && code.length == 6, onClick = { scope.launch { graph.mesh.join(host.trim(), cfg.api.port, code.trim()) } }) { Text("Parear") }
        if (status.isNotBlank()) Hint(status)
        if (cfg.mesh.peers.isNotEmpty()) {
            Label("Aparelhos")
            cfg.mesh.peers.forEach { p ->
                val online = System.currentTimeMillis() - p.lastSeen < 3 * 60_000
                Text("${if (online) "●" else "○"} ${p.room} — ${p.host}", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { graph.mesh.leave() }) { Text("Sair do grupo") }
        }
        if (presence.isNotEmpty()) {
            Label("Quem está onde")
            presence.values.sortedByDescending { it.at }.forEach { p ->
                val mins = (System.currentTimeMillis() - p.at) / 60_000
                Text("${p.person}: ${p.room} (há $mins min)", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

/** Self-update: where to look for new versions, permissions it needs, check now. */
@Composable
private fun UpdateSection(graph: AppGraph, cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val status by graph.updater.status.collectAsState()
    Section("Atualizações") {
        Hint("Ele baixa e instala versões novas sozinho. No PC: ./gradlew publishUpdate e tools/serve-updates.ps1.")
        SwitchRow("Atualizar automaticamente", checked = cfg.update.enabled) { v -> up { it.copy(update = it.update.copy(enabled = v)) } }
        Field("URL do latest.json", cfg.update.url, keyboard = KeyboardType.Uri) { v -> up { it.copy(update = it.update.copy(url = v.trim())) } }
        if (!graph.updater.canInstall()) {
            FilledTonalButton(onClick = { context.startActivity(graph.updater.installPermissionIntent()) }) { Text("Permitir instalar atualizações") }
        }
        if (!graph.updater.canRelaunch()) {
            FilledTonalButton(onClick = { context.startActivity(graph.updater.overlayPermissionIntent()) }) { Text("Permitir reabrir após atualizar") }
        }
        Button(onClick = { scope.launch { graph.updater.checkAndInstall() } }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Verificar agora") }
        Hint("Versão ${io.github.assistent.BuildConfig.VERSION_NAME} · $status")
    }
}

@Composable
private fun WebhooksSection(cfg: AppConfig, up: ((AppConfig) -> AppConfig) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("POST") }
    var url by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Section("Ações (webhooks)") {
        Hint("Cada ação vira uma ferramenta da IA: “liga a luz da sala” chama a URL. Parâmetros {{nome}} e headers avançados: pelo painel web ou API.")
        cfg.webhooks.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.id, fontWeight = FontWeight.Medium)
                    Text("${w.method} ${w.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Switch(checked = w.enabled, onCheckedChange = { v -> up { c -> c.copy(webhooks = c.webhooks.map { if (it.id == w.id) it.copy(enabled = v) else it }) } })
                IconButton(onClick = { up { c -> c.copy(webhooks = c.webhooks.filterNot { it.id == w.id }) } }) { Icon(Icons.Default.Delete, "Remover") }
            }
        }
        if (adding) {
            OutlinedTextField(id, { id = it }, label = { Text("id (ex. luz_sala)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("Quando usar (ex. Liga a luz da sala)") }, modifier = Modifier.fillMaxWidth())
            Segmented(listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT"), method) { method = it }
            OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Corpo (opcional, JSON)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = id.isNotBlank() && url.isNotBlank() && desc.isNotBlank(), onClick = {
                    val def = WebhookDef(id = id.trim(), description = desc.trim(), method = method, url = url.trim(), body = body)
                    up { c -> c.copy(webhooks = c.webhooks.filterNot { it.id == def.id } + def) }
                    adding = false; id = ""; desc = ""; url = ""; body = ""
                }) { Text("Salvar ação") }
                TextButton(onClick = { adding = false }) { Text("Cancelar") }
            }
        } else {
            FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Nova ação") }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Field(
    label: String, value: String, secret: Boolean = false, singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Pick up changes made elsewhere (API, web panel) only while the user is not typing here.
    LaunchedEffect(value) { if (!focused && value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) ({ TextButton(onClick = { visible = !visible }) { Text(if (visible) "ocultar" else "ver") } }) else null,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { "%.2f".format(it) }, onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("token", text))
}

private fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()
