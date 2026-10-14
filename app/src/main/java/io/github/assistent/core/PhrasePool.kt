package io.github.assistent.core

import android.util.Log
import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Every short line the robot says on its own (greetings, scares, quips, fun facts, sleep talk…) comes from
 * here. Each category has a description and a few hand-written examples; the AI writes new lines in batches
 * of 10 in the same spirit, they are saved, and lines are drawn at random from built-in + AI ones, avoiding
 * recent repeats. When a category runs low, a new batch is requested in the background.
 */
class PhrasePool(
    dir: File,
    private val config: ConfigStore,
    private val llm: LlmClient,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class Store(val lines: Map<String, List<String>> = emptyMap(), val used: Map<String, List<String>> = emptyMap())

    private val file = File(dir, "phrases.json")
    private var store = runCatching { AppJson.decodeFromString(Store.serializer(), file.readText()) }.getOrDefault(Store())
    private val lock = Mutex()
    private val refilling = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastRefillAt = 0L
    private val recent = ConcurrentHashMap<String, ArrayDeque<String>>()

    /** All AI-written lines, for the API. */
    fun all(): Map<String, List<String>> = store.lines

    /**
     * A random line of [category]: mixes built-in [examples] with saved AI lines (AI ones weighted higher once
     * there are enough). Triggers a refill when the category is running low.
     */
    fun pick(category: String, examples: List<String>, random: Random = Random): String {
        val ai = store.lines[category].orEmpty()
        if (ai.size < MIN_AI) refill(category, examples)
        val candidates = (ai.map { it to 2f } + examples.map { it to 1f }).distinctBy { it.first }
        if (candidates.isEmpty()) return ""
        val recentQ = recent.getOrPut(category) { ArrayDeque() }
        val pool = candidates.filter { it.first !in recentQ }.ifEmpty { candidates }
        var r = random.nextFloat() * pool.sumOf { it.second.toDouble() }.toFloat()
        var chosen = pool.last().first
        for ((t, w) in pool) {
            r -= w
            if (r <= 0f) { chosen = t; break }
        }
        recentQ.addLast(chosen)
        while (recentQ.size > 40) recentQ.removeFirst()
        if (chosen in ai) markUsed(category, chosen)
        return chosen
    }

    /** All lines (AI + examples) of a category, for weighted pickers that build their own candidate lists. */
    fun lines(category: String, examples: List<String>): List<String> {
        val ai = store.lines[category].orEmpty()
        if (ai.size < MIN_AI) refill(category, examples)
        return (ai + examples).distinct()
    }

    /** Marks a line as said (facts are removed so they are told only once). */
    fun consume(category: String, line: String) = markUsed(category, line)

    private fun markUsed(category: String, line: String) = scope.launch {
        lock.withLock {
            // Facts are one-shot: once told, drop them. Other lines can come back later.
            if (category == "fact" || category.startsWith("weather_")) {
                store = store.copy(
                    lines = store.lines + (category to (store.lines[category].orEmpty() - line)),
                    used = store.used + (category to (store.used[category].orEmpty() + line).takeLast(300)),
                )
                save()
            }
        }
    }

    fun refill(category: String, examples: List<String>) {
        // Spend the account's AI quota sparingly: one batch at a time, at most every 30 min, never while rate-limited.
        val now = System.currentTimeMillis()
        if (io.github.assistent.brain.LlmQuota.blocked || now - lastRefillAt < 30 * 60_000 || refilling.isNotEmpty()) return
        if (!refilling.add(category)) return
        lastRefillAt = now
        scope.launch {
            try {
                val cfg = config.current
                val desc = CATEGORIES[category] ?: category.replace('_', ' ')
                val avoid = (store.lines[category].orEmpty() + store.used[category].orEmpty()).takeLast(40)
                val prompt = buildString {
                    append("Você escreve falas curtas para ${cfg.personality.name}, um robozinho de rosto animado que mora num celular. ")
                    append("Personalidade: ${cfg.personality.traits}. Tom: ${io.github.assistent.brain.Brain.toneInstruction(cfg.personality.tone, cfg.personality.customTone)} ")
                    append("Escreva 10 falas NOVAS e variadas em ${cfg.personality.language} para esta situação: $desc. ")
                    append("Cada fala com no máximo 14 palavras, natural, engraçadinha, como alguém vivo falaria. ")
                    if (examples.any { "{n}" in it }) append("Use {n} onde entraria o nome da pessoa (em todas as falas). ")
                    append("Sem emojis. Uma fala por linha, sem numeração, sem aspas.\n")
                    append("Exemplos do estilo:\n")
                    examples.shuffled().take(6).forEach { append("- $it\n") }
                    if (avoid.isNotEmpty()) {
                        append("Não repita estas:\n")
                        avoid.forEach { append("- $it\n") }
                    }
                }
                val text = llm.complete(cfg.llm, listOf(ChatMessage("user", prompt)), emptyList()).content.orEmpty()
                val needsName = examples.any { "{n}" in it }
                val fresh = text.lines()
                    .map { it.trim().trimStart('-', '•', '*', ' ').trim().trim('"') }
                    .filter { it.length in 3..140 && (!needsName || "{n}" in it) }
                    .filter { it !in avoid }
                if (fresh.isNotEmpty()) lock.withLock {
                    val merged = (store.lines[category].orEmpty() + fresh).distinct().takeLast(MAX_PER_CATEGORY)
                    store = store.copy(lines = store.lines + (category to merged))
                    save()
                }
            } catch (e: Exception) {
                Log.w(TAG, "refill $category failed: ${e.message}")
            } finally {
                refilling.remove(category)
            }
        }
    }

    suspend fun clear(category: String? = null) = lock.withLock {
        store = if (category == null) Store() else store.copy(lines = store.lines - category)
        save()
    }

    private fun save() = runCatching {
        file.writeText(AppJson.encodeToString(Store.serializer(), store))
    }

    companion object {
        private const val TAG = "PhrasePool"
        private const val MIN_AI = 4
        private const val MAX_PER_CATEGORY = 80

        /** What each category is about, for the AI. Keys ending in _anon are for people the robot doesn't know. */
        val CATEGORIES = mapOf(
            "low_light_identity" to "uma fala curta e variada quando alguém pergunta quem está na frente, mas a imagem está escura; não invente o nome e brinque com o abajur ou a visão noturna",
            "greet_morning" to "cumprimentar de manhã alguém conhecido que passou na frente",
            "greet_morning_anon" to "cumprimentar de manhã uma pessoa desconhecida (sem nome)",
            "greet_lunch" to "cumprimentar alguém na hora do almoço",
            "greet_lunch_anon" to "cumprimentar uma pessoa desconhecida na hora do almoço",
            "greet_afternoon" to "cumprimentar alguém à tarde",
            "greet_afternoon_anon" to "cumprimentar uma pessoa desconhecida à tarde",
            "greet_evening" to "cumprimentar alguém à noite",
            "greet_evening_anon" to "cumprimentar uma pessoa desconhecida à noite",
            "greet_late" to "falar com alguém acordado de madrugada",
            "greet_late_anon" to "falar com uma pessoa desconhecida acordada de madrugada",
            "monday" to "comentário de segunda-feira para alguém",
            "friday" to "comentário de sexta-feira (sextou) para alguém",
            "weekend" to "comentário de fim de semana para alguém",
            "hot" to "comentar que está muito calor hoje",
            "cold" to "comentar que está muito frio hoje",
            "rain" to "comentar que está chovendo",
            "storm" to "comentar que tem tempestade com trovões",
            "back_long" to "alguém que não aparecia há dias voltou",
            "back_short" to "alguém que passou de novo poucos minutos depois",
            "first_time" to "conhecer e cumprimentar alguém pela primeira vez",
            "generic" to "puxar assunto de forma divertida com alguém conhecido que passou",
            "generic_anon" to "puxar assunto com uma pessoa desconhecida que passou",
            "thinking_wait" to "uma fala curta e descontraída enquanto o robô pensa ou pesquisa antes de responder",
            "alone_camera" to "uma fala curta, natural e divertida quando o robô não vê ninguém por vários segundos; pode brincar com fantasmas, luz apagada, casa vazia ou pedir desculpas por não ter visto alguém",
            "startled" to "o robô (um rosto animado numa tela) estava cochilando e levou um susto porque alguém passou na frente dele. Ninguém mexeu nem sacudiu o aparelho; não fale de celular, tela, terremoto ou sacudir",
            "noticed" to "o robô estava cochilando, acordou devagar e só agora percebeu que tinha uma pessoa ali na frente dele (não fale de celular nem de mexer no aparelho)",
            "peek" to "o robô estava dormindo, ouviu/viu alguém passar, abriu um olho, viu que não era nada importante e voltou a dormir resmungando com sono",
            "idle_quip" to "comentário aleatório que o robô faz sozinho quando está à toa",
            "sleep_talk" to "o robô falando dormindo, frases sem sentido e engraçadas, sonhando",
            "sneeze" to "o robô acabou de espirrar",
            "hiccup" to "o robô está com soluço",
            "stretch" to "o robô se espreguiçou",
            "bored" to "o robô está entediado sem nada pra fazer",
            "anyone_there" to "o robô acordou sozinho, sem ninguém por perto, e pergunta se tem alguém ali ou fala sozinho",
            "quip_morning" to "comentário sozinho de manhã cedo, ainda com sono, querendo café",
            "weather_rain" to "um comentário curto e aconchegante sobre a chuva lá fora agora (dormir, chá, cobertor, filme)",
            "weather_cold" to "um comentário curto sobre o frio que está fazendo agora",
            "weather_hot" to "um comentário curto sobre o calor que está fazendo agora",
            "weather_fog" to "um comentário curto sobre a neblina lá fora",
            "weather_nice" to "um comentário curto e animado sobre o dia bonito e ensolarado lá fora",
            "quip_lunch" to "comentário sozinho perto da hora do almoço, com fome, falando de comida",
            "quip_afternoon" to "comentário sozinho no meio da tarde, com moleza pós-almoço",
            "quip_evening" to "comentário sozinho no começo da noite, relaxando depois do dia",
            "quip_night" to "comentário sozinho tarde da noite, com sono, quase dormindo",
            "wave_back" to "alguém acenou para o robô e ele acena de volta, cumprimentando",
            "dance" to "o robô está dançando porque está tocando música",
            "pet" to "alguém está fazendo carinho no robô (passando o dedo no rosto dele) e ele gosta",
            "tickle" to "alguém está fazendo cócegas no robô e ele ri",
            "shaken" to "alguém sacudiu o celular onde o robô mora e ele ficou tonto reclamando",
            "charge" to "colocaram o robô para carregar a bateria e ele fica feliz com a energia",
            "low_battery" to "a bateria do robô está acabando e ele está cansado",
            "peek_where" to "taparam a câmera do robô (brincadeira de esconde-esconde) e ele procura a pessoa",
            "peek_found" to "destaparam a câmera, o robô achou a pessoa no esconde-esconde",
            "too_close" to "a pessoa chegou com o rosto muito perto da câmera do robô",
            "shy" to "a pessoa ficou encarando o robô por muito tempo e ele ficou com vergonha",
            "goodbye" to "a pessoa {n} está indo embora e o robô se despede",
            "fact" to "curiosidade curta, verdadeira e surpreendente de tema variado (animais, espaço, corpo humano, história, comida, tecnologia)",
        )
    }
}

