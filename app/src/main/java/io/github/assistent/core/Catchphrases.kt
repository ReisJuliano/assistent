package io.github.assistent.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.random.Random

/** Everything a greeting can depend on. [name] null = someone the robot doesn't know. */
data class GreetContext(
    val name: String?,
    val now: LocalDateTime,
    val ambient: Ambient,
    /** Minutes since this person was last seen, null = first time ever. */
    val minutesAway: Long?,
    val remindersToday: Int,
    val isBirthday: Boolean,
    val assistantName: String,
)

/**
 * The robot's catchphrases. Each situation (time of day, weekday, weather, how long you've been away…) is a
 * [PhrasePool] category: the hand-written lines below are examples, the AI keeps writing new ones in the same
 * spirit, and a line is drawn at random among all that fit, with very specific situations weighted higher.
 * `{n}` is the person's name.
 */
object Catchphrases {
    /** Set at startup; null = only built-in lines. */
    @Volatile var pool: PhrasePool? = null

    private class Line(val text: String, val weight: Float, val fact: String? = null)

    fun pick(ctx: GreetContext, avoid: Collection<String> = emptyList(), random: Random = Random): String {
        val all = candidates(ctx)
        val lines = all.filter { it.text !in avoid }.ifEmpty { all }
        var r = random.nextFloat() * lines.sumOf { it.weight.toDouble() }.toFloat()
        var chosen = lines.last()
        for (l in lines) {
            r -= l.weight
            if (r <= 0f) { chosen = l; break }
        }
        chosen.fact?.let { pool?.consume("fact", it) } // a fact is told only once
        return chosen.text
    }

    /** A line of any [PhrasePool] category (startled, noticed, idle_quip, sleep_talk…). */
    fun line(category: String, examples: List<String>): String = pool?.pick(category, examples) ?: examples.random()

    private fun candidates(c: GreetContext): List<Line> {
        val out = mutableListOf<Line>()
        val known = c.name != null
        val n = c.name ?: ""
        val p = pool

        /** Category lines (AI + examples); the category [weight] is spread over its lines. */
        fun cat(weight: Float, key: String, withName: List<String>, anon: List<String> = emptyList()) {
            val k = if (known) key else "${key}_anon"
            val examples = if (known) withName else anon
            if (examples.isEmpty()) return
            val lines = p?.lines(k, examples) ?: examples
            val each = weight * 3f / lines.size.coerceAtLeast(1)
            lines.forEach { out += Line(it.replace("{n}", n), each) }
        }
        fun fixed(weight: Float, vararg texts: String, anon: List<String> = emptyList()) {
            (if (known) texts.toList() else anon).forEach { out += Line(it.replace("{n}", n), weight) }
        }

        val h = c.now.hour
        val dow = c.now.dayOfWeek
        val md = "%02d-%02d".format(c.now.monthValue, c.now.dayOfMonth)
        val temp = c.ambient.temperature

        // ---------------------------------------------------------- birthdays & holidays (fixed, highest priority)
        if (c.isBirthday) fixed(40f, "Feliz aniversário, {n}! 🎂 Hoje o dia é seu!", "Parabéns, {n}! Muitos anos de vida… e de bateria!", "{n}! Hoje é seu aniversário, quero bolo! 🎉")
        HOLIDAYS[md]?.let { lines -> fixed(12f, *lines.map { it.first }.toTypedArray(), anon = lines.map { it.second }) }

        // ---------------------------------------------------------- returning after a while
        when {
            c.minutesAway == null && known -> cat(6f, "first_time", listOf("Oi, {n}! Que bom te ver por aqui!", "Olha quem chegou: {n}!"))
            c.minutesAway != null && c.minutesAway > 60 * 24 * 3 -> cat(10f, "back_long", listOf("Sumido(a), hein {n}! Senti sua falta.", "{n}!!! Quanto tempo! Achei que tinha me esquecido.", "Até que enfim, {n}! Tava contando os dias."))
            c.minutesAway != null && c.minutesAway < 45 -> cat(3f, "back_short", listOf("De novo por aqui, {n}? Tá me vigiando? 👀", "Ué, {n}, esqueceu alguma coisa?", "Opa, {n} na área de novo!"))
        }

        // ---------------------------------------------------------- time of day
        when (h) {
            in 5..10 -> cat(4f, "greet_morning",
                listOf("Bom dia, {n}! Dormiu bem?", "Bom dia, {n}! Já tomou café?", "Bom diaaa, {n}! Bora começar bem o dia?", "Acordou cedo, hein {n}!"),
                listOf("Bom dia! Tudo certo por aí?", "Bom dia! Já tomou café?", "Madrugou, hein!"))
            in 11..13 -> cat(4f, "greet_lunch",
                listOf("Já almoçou, {n}?", "Hora do almoço, {n}! Tô com fome de bateria.", "Boa tarde, {n}! O que tem pro almoço?"),
                listOf("Boa tarde! Já almoçou?", "Hora do almoço, hein!"))
            in 14..17 -> cat(3f, "greet_afternoon",
                listOf("Boa tarde, {n}! Como tá o dia?", "Aquela sonequinha da tarde batendo, {n}?", "Bora um cafezinho da tarde, {n}?"),
                listOf("Boa tarde! Como tá o dia?"))
            in 18..22 -> cat(3f, "greet_evening",
                listOf("Boa noite, {n}! Como foi o dia?", "Chegou a hora de relaxar, {n}.", "Boa noite, {n}! Vai ver um filminho hoje?"),
                listOf("Boa noite! Como foi o dia?"))
            else -> cat(6f, "greet_late",
                listOf("{n}, isso é hora de estar acordado(a)? 😴", "Ainda acordado(a), {n}? Eu também não consigo dormir…", "Ronda da madrugada, {n}?"),
                listOf("Ei, ainda acordado(a) a essa hora?"))
        }

        // ---------------------------------------------------------- weekday mood
        when (dow) {
            DayOfWeek.MONDAY -> cat(3f, "monday", listOf("Segundou, {n}! Bora que essa semana rende.", "Segunda-feira, {n}… força! 💪"))
            DayOfWeek.FRIDAY -> cat(4f, "friday", listOf("Sextou, {n}! 🎉", "Sexta-feira, {n}! Quais os planos pro fim de semana?"))
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> cat(3f, "weekend", listOf("Fim de semana, {n}! Vai descansar um pouco?", "Bom fim de semana, {n}! Hoje é dia de preguiça."))
            else -> Unit
        }

        // ---------------------------------------------------------- real weather
        when (c.ambient.condition) {
            "rain", "drizzle" -> cat(5f, "rain", listOf("Tá chovendo, {n}! Não esquece o guarda-chuva ☔", "Dia de chuva, {n}… perfeito pra um chocolate quente."),
                listOf("Tá chovendo lá fora, leva o guarda-chuva!"))
            "storm" -> cat(6f, "storm", listOf("Que trovoada, {n}! Fica aqui dentro comigo.", "Tempestade lá fora, {n}! Eu morro de medo de raio ⚡"),
                listOf("Que tempestade, hein!"))
            "snow" -> fixed(8f, "Tá nevando, {n}!! ❄️ Bora fazer um boneco de neve?", anon = listOf("Tá nevando!! ❄️"))
        }
        if (temp != null) when {
            temp >= 30f -> cat(6f, "hot", listOf("Tá quente hoje, né {n}? 🥵", "Calor de derreter circuito, {n}!", "{n}, bebe água! Tá muito calor hoje."),
                listOf("Que calor hoje, hein!", "Tá quente, né? Bebe água!"))
            temp <= 12f -> cat(6f, "cold", listOf("Brrr… tá muito frio, {n}! Pega um casaco 🧥", "{n}, tá frio de bater os circuitos!", "Frio assim é dia de sopa, {n}."),
                listOf("Que frio, hein! Se agasalha."))
        }

        // ---------------------------------------------------------- reminders
        if (c.remindersToday > 0 && known) fixed(4f,
            if (c.remindersToday == 1) "{n}, você tem 1 lembrete hoje. Quer que eu fale?" else "{n}, você tem ${c.remindersToday} lembretes hoje!")

        // ---------------------------------------------------------- always available: playful generic lines
        cat(1.5f, "generic",
            listOf(
                "E aí, {n}!", "Eae, {n}! Beleza?", "Fala, {n}!", "Ô {n}, me conta uma novidade!", "Psiu, {n}! Tô aqui ó 👋",
                "{n}! Senti sua falta.", "Tá bonito(a) hoje, hein {n}!", "{n}, bora conversar um pouco?", "Olha o {n} passando! 😄",
                "{n}, tô entediado… me dá uma tarefa!",
            ),
            listOf(
                "Opa! Visita! 👋", "Oi! Não te conheço ainda… qual seu nome?", "Olá! Quer conversar comigo?", "Ei, você! Tudo bem?",
                "Seja bem-vindo(a)! Eu sou o ${c.assistantName}.", "Psiu! Me chama pelo nome que eu te ajudo.",
            ))

        // ---------------------------------------------------------- fun facts (AI-written, told once)
        val fact = (p?.lines("fact", FACTS) ?: FACTS).random()
        out += Line(if (known) "$n, sabia? $fact" else "Sabia? $fact", 2f, fact)

        return out
    }

    /** Fixed-date Brazilian holidays / dates: line with name, line without. */
    private val HOLIDAYS = mapOf(
        "01-01" to listOf("Feliz Ano Novo, {n}! 🎆 Que esse ano seja incrível!" to "Feliz Ano Novo! 🎆"),
        "02-14" to listOf("Feliz Valentine's, {n}! 💘" to "Feliz Valentine's Day! 💘"),
        "04-21" to listOf("Feriado de Tiradentes, {n}! Descansa aí." to "Feriado hoje! Aproveita."),
        "05-01" to listOf("Feliz Dia do Trabalhador, {n}! Hoje é folga, né?" to "Feliz Dia do Trabalhador!"),
        "06-12" to listOf("Dia dos Namorados, {n}! 💕 Vai ter date?" to "Feliz Dia dos Namorados! 💕"),
        "06-24" to listOf("Arraiá, {n}! Cadê a pamonha? 🌽" to "Viva São João! 🌽"),
        "09-07" to listOf("Feliz Dia da Independência, {n}! 🇧🇷" to "Feliz 7 de Setembro! 🇧🇷"),
        "10-12" to listOf("Feliz Dia das Crianças, {n}! Bora brincar? 🎈" to "Feliz Dia das Crianças! 🎈"),
        "10-31" to listOf("Gostosuras ou travessuras, {n}? 🎃" to "Feliz Halloween! 🎃"),
        "11-02" to listOf("Feriado de Finados, {n}. Dia de descansar." to "Hoje é feriado de Finados."),
        "11-15" to listOf("Feriado da República, {n}! Aproveita o descanso." to "Feriado hoje!"),
        "11-20" to listOf("Dia da Consciência Negra, {n}." to "Hoje é Dia da Consciência Negra."),
        "12-24" to listOf("Véspera de Natal, {n}! 🎄 Já embrulhou os presentes?" to "Feliz véspera de Natal! 🎄"),
        "12-25" to listOf("Feliz Natal, {n}! 🎅🎄" to "Feliz Natal! 🎄"),
        "12-31" to listOf("Último dia do ano, {n}! Qual a meta pro próximo? 🥂" to "Último dia do ano! 🥂"),
    )

    val FACTS = listOf(
        "Polvos têm três corações!", "O mel nunca estraga.", "As vacas têm melhores amigas.", "Um dia em Vênus dura mais que um ano lá.",
        "Os flamingos são rosa por causa do que comem.", "A Torre Eiffel cresce uns centímetros no verão.", "Os golfinhos dormem com um olho aberto.",
        "Pra botânica, a banana é uma baga!", "O coração de uma baleia-azul é do tamanho de um carro.", "Lontras dão as mãos pra dormir e não se perderem.",
    )
}
