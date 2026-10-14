package io.github.assistent.tools

import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.PresenceTracker
import io.github.assistent.quiz.QuizRepository
import io.github.assistent.sleep.SleepTracker
import kotlinx.serialization.json.JsonObject

class SleepReportTool(private val sleep: SleepTracker) : Tool {
    override val name = "sleep_report"
    override val description = "Report estimated sleep duration. Always explain it is only an estimate, not medical data."
    override val parameters = params { integer("days", "Number of days", required = false) }
    override suspend fun execute(args: JsonObject): String = sleep.report(args.num("days")?.toInt() ?: 7)
}

class StartQuizTool(
    private val llm: LlmClient,
    private val config: ConfigStore,
) : Tool {
    override val name = "start_quiz"
    override val description = "Generate one quiz question with alternatives A/B/C/D and the answer. The assistant should ask it aloud."
    override val parameters = params {
        string("topic", "Quiz topic", required = false)
        string("difficulty", "easy, medium or hard", required = false, enum = listOf("easy", "medium", "hard"))
    }

    override suspend fun execute(args: JsonObject): String {
        val topic = args.str("topic") ?: "conhecimentos gerais"
        val difficulty = args.str("difficulty") ?: "easy"
        val prompt = "Crie UMA pergunta de quiz em pt-BR sobre $topic, dificuldade $difficulty. Responda JSON com question, options {A,B,C,D}, answer e explanation."
        return llm.complete(config.current.llm, listOf(ChatMessage("user", prompt)), emptyList()).content.orEmpty()
    }
}

class RecordQuizScoreTool(private val quiz: QuizRepository, private val presence: PresenceTracker) : Tool {
    override val name = "record_quiz_score"
    override val description = "Record whether the current person's quiz answer was correct."
    override val parameters = params {
        string("topic", "Quiz topic", required = false)
        boolean("correct", "Whether the answer was correct")
    }

    override suspend fun execute(args: JsonObject): String {
        val score = quiz.record(presence.currentPerson.value?.id, args.str("topic") ?: "", args.bool("correct") == true)
        return "score ${score.correct}/${score.total}"
    }
}
