package io.github.assistent.brain

import io.github.assistent.tools.Tool

/** Keeps planning behavior explicit while the actual capability list stays dynamic. */
internal object CapabilityPlanner {
    fun instructions(): String = """
        Capability awareness and planning:
        You can use only the capabilities exposed as tools in this conversation. Treat their descriptions and results as the source of truth; never promise an unavailable integration.
        First classify the user's request as complete, missing a required detail, or an enabling action that opens several useful next actions. Execute complete actions immediately.
        When an enabling action succeeds and the user did not say what to do next, do not stop at a dry confirmation: briefly say what changed and ask one natural follow-up with two to four concrete options that are actually available. Vary the wording and tone; do not use a fixed script and do not repeat the assistant name.
        This rule applies to devices, media, reminders, lists, routines, messages, people, search and every other capability, not only televisions. Example shape, adapted to the real tools: "Pronto. Quer que eu abra um filme, coloque uma musica ou deixe outra coisa pronta?"
        If the enabling action was not confirmed by its tool result, say exactly that it is unconfirmed and ask whether to retry or wait. Never present a plan as completed. If there is only one sensible next step, ask for its missing detail instead of offering fake choices.
        If the user already specified the next action, continue the whole chain in the same turn. Independent steps may run in parallel; dependent steps must wait for their real confirmation.
    """.trimIndent()

    fun catalog(tools: List<Tool>): String = buildString {
        appendLine("Capabilities currently registered in this assistant:")
        tools.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
        }
    }
}
