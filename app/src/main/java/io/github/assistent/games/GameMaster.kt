package io.github.assistent.games

import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import io.github.assistent.core.Emotion
import io.github.assistent.core.PresenceTracker
import io.github.assistent.quiz.QuizRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import kotlin.random.Random

/**
 * What the game screen shows. [options] for quiz/true-false; [reveal] = right option after an answer, [picked] = the
 * player's; [you]/[me] = rock-paper-scissors moves (0 rock, 1 paper, 2 scissors); [countdown] while counting.
 */
@kotlinx.serialization.Serializable
data class GameView(
    val title: String,
    val progress: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val reveal: Int? = null,
    val picked: Int? = null,
    val score: String = "",
    val hint: String = "",
    val you: Int? = null,
    val me: Int? = null,
    val countdown: String = "",
    val result: String = "",
    val finished: Boolean = false,
)

/** What the robot does on its turn: say [say] (also shown in the bubble with [show]), with a face. */
data class GameTurn(val say: String, val emotion: Emotion = Emotion.HAPPY, val show: String = "", val done: Boolean = false)

/**
 * Voice games run by the robot itself (no round-trip to the LLM per answer, so they are quick and reliable):
 * quiz, guess the number, rock-paper-scissors with the hand on camera, true or false. The LLM is only used to write
 * a batch of questions. While a game is on, everything the player says goes to [handle].
 */
class GameMaster(
    private val llm: LlmClient,
    private val config: ConfigStore,
    private val scores: QuizRepository,
    private val presence: PresenceTracker,
    /** Latest hand gesture seen on camera and when (MediaPipe names: Closed_Fist, Open_Palm, Victory…). */
    private val lastGesture: () -> Pair<String, Long>,
) {
    sealed interface Session { val name: String }

    private class Quiz(val topic: String, val questions: List<Q>, var i: Int = 0, var right: Int = 0, val trueFalse: Boolean = false) : Session {
        override val name = if (trueFalse) "verdade ou mentira" else "quiz"
    }
    private class Guess(val secret: Int, var tries: Int = 0, var low: Int = 1, var high: Int = 100) : Session { override val name = "adivinhe o número" }
    private class Rps(var round: Int = 0, var me: Int = 0, var you: Int = 0) : Session { override val name = "pedra, papel e tesoura" }

    data class Q(val question: String, val options: List<String>, val answer: Int, val explanation: String)

    @Volatile var session: Session? = null
        private set
    private val _view = kotlinx.coroutines.flow.MutableStateFlow<GameView?>(null)
    /** The game on screen (null = no game). Stays a few seconds after the end with the final result. */
    val view: kotlinx.coroutines.flow.StateFlow<GameView?> = _view
    /** Set by `start_game`; the runtime starts the game right after the current reply. */
    @Volatile var pending: Pair<String, String>? = null

    val active get() = session != null

    fun request(game: String, topic: String) { pending = game to topic }

    /** Starts the pending/asked game and returns its first turn. */
    suspend fun begin(game: String, topic: String): GameTurn {
        pending = null
        val kids = config.current.personality.kidsMode
        return when (normalize(game)) {
            "numero", "adivinhe o numero", "adivinha", "guess" -> {
                val max = if (kids) 20 else 100
                session = Guess(Random.nextInt(1, max + 1), high = max)
                _view.value = GameView("Adivinhe o número", "Tentativa 0", "Pensei num número", hint = "entre 1 e $max")
                GameTurn("Pensei num número de 1 a $max. Tenta adivinhar!", Emotion.HAPPY, "1 a $max")
            }
            "pedra papel e tesoura", "pedra papel tesoura", "jokenpo", "rps" -> {
                session = Rps()
                _view.value = GameView("Pedra, papel e tesoura", "Melhor de três", "Mostre a mão para a câmera", score = "Você 0 x 0 eu")
                GameTurn("Melhor de três! Mostra a mão pra câmera ou fala pedra, papel ou tesoura quando eu disser já.", Emotion.HAPPY) .copy(show = "Pedra, papel ou tesoura")
            }
            "verdade ou mentira", "verdade", "true false" -> startQuiz(topic, trueFalse = true)
            else -> startQuiz(topic, trueFalse = false)
        }
    }

    private suspend fun startQuiz(topic: String, trueFalse: Boolean): GameTurn {
        val t = topic.ifBlank { "conhecimentos gerais" }
        val qs = runCatching { writeQuestions(t, trueFalse) }.getOrDefault(emptyList())
        if (qs.isEmpty()) {
            session = null
            return GameTurn("Não consegui preparar as perguntas agora. Quer jogar adivinhe o número?", Emotion.SAD, done = true)
        }
        session = Quiz(t, qs, trueFalse = trueFalse)
        showQuestion(session as Quiz)
        return GameTurn((if (trueFalse) "Verdade ou mentira sobre $t! " else "Quiz de $t, ${qs.size} perguntas! ") + ask(session as Quiz).say, Emotion.HAPPY, ask(session as Quiz).show)
    }

    /** The robot's line before listening (rock-paper-scissors needs a countdown). */
    suspend fun prompt(say: suspend (String) -> Unit): Unit = when (val s = session) {
        is Rps -> {
            // The countdown on screen follows the voice.
            uiScope.launch {
                for (w in listOf("Pedra...", "Papel...", "Tesoura...", "Já!")) {
                    _view.value = _view.value?.copy(countdown = w, you = null, me = null, result = "")
                    kotlinx.coroutines.delay(420)
                }
            }
            say("Pedra, papel, tesoura... já!")
        }
        else -> Unit
    }

    /** A player's answer. Returns null when no game is on. */
    suspend fun handle(text: String): GameTurn? {
        val s = session ?: return null
        val n = normalize(text)
        if (n.matches(Regex(".*\\b(parar|para|chega|sair|cansei|acabou)\\b.*")) && !(s is Rps && n.contains("papel"))) return end("Beleza, fim de jogo!")
        return when (s) {
            is Guess -> guess(s, n)
            is Quiz -> quizAnswer(s, n)
            is Rps -> rps(s, n)
        }
    }

    /** Nobody answered. */
    fun timeout(): GameTurn = end("Acho que você foi fazer outra coisa. Fim de jogo!")

    private fun guess(s: Guess, n: String): GameTurn {
        val v = numberIn(n) ?: return GameTurn("Fala um número!", Emotion.CONFUSED)
        s.tries++
        if (v < s.secret) s.low = maxOf(s.low, v + 1) else if (v > s.secret) s.high = minOf(s.high, v - 1)
        _view.value = _view.value?.copy(
            progress = "Tentativa ${s.tries}", question = if (v == s.secret) "Era ${s.secret}!" else "Você disse $v: é ${if (v < s.secret) "maior" else "menor"}",
            hint = "entre ${s.low} e ${s.high}", result = if (v == s.secret) "Acertou em ${s.tries}" else "",
        )
        return when {
            v < s.secret -> GameTurn("É maior que $v.", Emotion.THINKING)
            v > s.secret -> GameTurn("É menor que $v.", Emotion.THINKING)
            else -> end("Acertou! Era ${s.secret}, em ${s.tries} tentativas.", Emotion.JOY)
        }
    }

    private suspend fun quizAnswer(s: Quiz, n: String): GameTurn {
        val q = s.questions[s.i]
        val picked = pickOption(n, q, s.trueFalse) ?: return GameTurn(
            if (s.trueFalse) "Verdade ou mentira?" else "Qual letra? A, B, C ou D.", Emotion.CONFUSED, optionsText(q, s.trueFalse),
        )
        val ok = picked == q.answer
        if (ok) s.right++
        _view.value = _view.value?.copy(picked = picked, reveal = q.answer, score = "Acertos ${s.right} de ${s.i + 1}", result = if (ok) "Acertou!" else "Errou")
        runCatching { scores.record(presence.currentPerson.value?.id, s.topic, ok) }
        val feedback = (if (ok) listOf("Acertou!", "Isso aí!", "Mandou bem!").random() else "Não... era ${answerText(q, s.trueFalse)}.") +
            (q.explanation.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        s.i++
        if (s.i >= s.questions.size) return end("$feedback Fim! Você acertou ${s.right} de ${s.questions.size}.", if (s.right * 2 >= s.questions.size) Emotion.JOY else Emotion.HAPPY)
        val next = ask(s)
        nextQuestionLater(s)
        return GameTurn("$feedback Próxima: ${next.say}", if (ok) Emotion.JOY else Emotion.SAD)
    }

    private suspend fun rps(s: Rps, n: String): GameTurn {
        // The hand on camera wins over the voice when both exist (shown within the last 2.5 s).
        val (g, at) = lastGesture()
        val fromHand = if (System.currentTimeMillis() - at < 2_500) when (g) {
            "Closed_Fist" -> 0; "Open_Palm" -> 1; "Victory" -> 2; else -> null
        } else null
        val you = fromHand ?: when {
            n.contains("pedra") -> 0; n.contains("papel") -> 1; n.contains("tesoura") -> 2; else -> null
        } ?: return GameTurn("Não vi nem ouvi sua jogada. De novo: pedra, papel ou tesoura?", Emotion.CONFUSED)
        val me = Random.nextInt(3)
        s.round++
        val names = listOf("pedra", "papel", "tesoura")
        val result = when ((you - me + 3) % 3) { 0 -> 0; 1 -> 1; else -> -1 }
        if (result == 1) s.you++ else if (result == -1) s.me++
        val line = "Eu: ${names[me]}. Você: ${names[you]}. " + when (result) { 0 -> "Empate!"; 1 -> "Você ganhou essa!"; else -> "Ganhei!" }
        _view.value = _view.value?.copy(
            you = you, me = me, countdown = "", progress = "Rodada ${s.round}", score = "Você ${s.you} x ${s.me} eu",
            result = when (result) { 0 -> "Empate"; 1 -> "Você ganhou"; else -> "Eu ganhei" }, question = "",
        )
        if (s.you == 2 || s.me == 2 || s.round >= 5) {
            return end("$line ${if (s.you > s.me) "Você venceu a melhor de três!" else if (s.me > s.you) "Venci a melhor de três!" else "Deu empate geral!"}",
                if (s.you > s.me) Emotion.SAD else Emotion.JOY)
        }
        return GameTurn("$line Placar: você ${s.you}, eu ${s.me}.", if (result == -1) Emotion.JOY else if (result == 1) Emotion.SURPRISED else Emotion.HAPPY,
            "Você ${s.you} x ${s.me} eu")
    }

    private fun end(line: String, emotion: Emotion = Emotion.HAPPY): GameTurn {
        session = null
        _view.value = _view.value?.copy(finished = true, result = _view.value?.result?.ifBlank { "Fim de jogo" } ?: "Fim de jogo")
        val shown = _view.value
        hideLater(shown)
        return GameTurn(line, emotion, done = true)
    }

    private val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private fun showQuestion(s: Quiz) {
        val q = s.questions[s.i]
        _view.value = GameView(
            title = if (s.trueFalse) "Verdade ou mentira: ${s.topic}" else "Quiz: ${s.topic}",
            progress = "Pergunta ${s.i + 1}/${s.questions.size}", question = q.question,
            options = if (s.trueFalse) listOf("Verdade", "Mentira") else q.options, score = "Acertos ${s.right}",
        )
    }

    /** Keep the answer (right/wrong colours) on screen for a moment, then show the next question. */
    private fun nextQuestionLater(s: Quiz) = uiScope.launch {
        kotlinx.coroutines.delay(2_200)
        if (session === s) showQuestion(s)
    }

    /** The final screen stays a few seconds, then the game panel goes away. */
    private fun hideLater(shown: GameView?) = uiScope.launch {
        kotlinx.coroutines.delay(6_000)
        if (session == null && _view.value === shown) _view.value = null
    }

    private fun ask(s: Quiz): GameTurn {
        val q = s.questions[s.i]
        val say = if (s.trueFalse) q.question else q.question + " " + q.options.mapIndexed { k, o -> "${'A' + k}: $o" }.joinToString(". ")
        return GameTurn(say, Emotion.THINKING, optionsText(q, s.trueFalse))
    }

    private fun optionsText(q: Q, tf: Boolean) = if (tf) "Verdade ou mentira?" else q.options.mapIndexed { k, o -> "${'A' + k}) $o" }.joinToString("\n")
    private fun answerText(q: Q, tf: Boolean) = if (tf) (if (q.answer == 0) "verdade" else "mentira") else "${'A' + q.answer}, ${q.options[q.answer]}"

    private suspend fun writeQuestions(topic: String, trueFalse: Boolean): List<Q> {
        val kids = config.current.personality.kidsMode
        val prompt = buildString {
            append(if (trueFalse) "Crie 5 afirmações curtas de verdade ou mentira em pt-BR sobre $topic, metade verdadeiras. "
            else "Crie 5 perguntas de quiz em pt-BR sobre $topic, com 4 alternativas curtas cada. ")
            if (kids) append("Para crianças de 6 a 10 anos: palavras simples e assuntos leves. ")
            append("Responda SÓ um JSON array: [{\"question\":\"...\",")
            append(if (trueFalse) "\"answer\":\"verdade|mentira\"," else "\"options\":[\"..\",\"..\",\"..\",\"..\"],\"answer\":\"A|B|C|D\",")
            append("\"explanation\":\"curiosidade de uma frase\"}]. Fatos corretos e verificáveis.")
        }
        val raw = llm.complete(config.current.llm, listOf(ChatMessage("user", prompt)), emptyList()).content.orEmpty()
        return parseQuestions(raw, trueFalse)
    }

    companion object {
        fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        private val NUMBERS = mapOf(
            "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
            "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14,
            "quatorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19,
            "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "setenta" to 70,
            "oitenta" to 80, "noventa" to 90, "cem" to 100,
        )

        /** "42", "quarenta e dois", "é o 7" → number. */
        fun numberIn(n: String): Int? {
            Regex("\\d+").find(n)?.let { return it.value.toIntOrNull() }
            var total = 0
            var found = false
            for (w in n.split(' ')) NUMBERS[w]?.let { total += it; found = true }
            return if (found) total else null
        }

        /** Letter ("letra b", "é a c"), or the option's own words, or verdade/mentira. */
        fun pickOption(n: String, q: Q, trueFalse: Boolean): Int? {
            if (trueFalse) return when {
                Regex("\\b(verdade|verdadeiro|verdadeira|sim|certo)\\b").containsMatchIn(n) -> 0
                Regex("\\b(mentira|falso|falsa|nao|errado)\\b").containsMatchIn(n) -> 1
                else -> null
            }
            Regex("\\b(?:letra |a |e a |e |opcao |alternativa )?([abcd])\\b").findAll(n).lastOrNull()?.let { m ->
                val words = n.split(' ')
                // A bare "a"/"e" is usually an article; accept it only when it is the whole answer or after "letra".
                val letter = m.groupValues[1]
                if (letter !in setOf("a", "e") || words.size <= 2 || n.contains("letra $letter")) return letter[0] - 'a'
            }
            val scored = q.options.mapIndexed { k, o ->
                val ow = normalize(o).split(' ').filter { it.length > 2 }.toSet()
                k to ow.count { it in n.split(' ') }.toFloat() / ow.size.coerceAtLeast(1)
            }.maxByOrNull { it.second }
            return scored?.takeIf { it.second >= 0.5f }?.first
        }

        fun parseQuestions(raw: String, trueFalse: Boolean): List<Q> = runCatching {
            val arr = AppJson.parseToJsonElement(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1)).jsonArray
            arr.mapNotNull { e ->
                val o = e.jsonObject
                val q = o["question"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val ans = o["answer"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: return@mapNotNull null
                val expl = o["explanation"]?.jsonPrimitive?.content.orEmpty()
                if (trueFalse) Q(q, listOf("verdade", "mentira"), if (ans.startsWith("v")) 0 else 1, expl)
                else {
                    val opts = o["options"]?.jsonArray?.map { it.jsonPrimitive.content }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                    val idx = ans.firstOrNull()?.let { it - 'a' }?.takeIf { it in 0..3 } ?: return@mapNotNull null
                    Q(q, opts, idx, expl)
                }
            }
        }.getOrDefault(emptyList())
    }
}
