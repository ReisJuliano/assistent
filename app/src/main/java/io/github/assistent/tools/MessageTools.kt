package io.github.assistent.tools

import io.github.assistent.messages.MessageManager
import kotlinx.serialization.json.JsonObject

class LeaveMessageTool(private val messages: MessageManager) : Tool {
    override val name = "leave_message"
    override val description = "Leave a message to be delivered once to a known person when they are recognized."
    override val parameters = params {
        string("to", "Known person name", required = false)
        string("text", "Message text")
        string("from", "Sender name", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val text = args.str("text") ?: return "missing text"
        val recipient = args.str("to")?.trim().orEmpty()
        if (recipient.isNotBlank() && messages.findPerson(recipient) == null) {
            return "error: person '$recipient' is not enrolled; cannot guarantee delivery"
        }
        val m = messages.leave(recipient.ifBlank { null }, text, args.str("from").orEmpty())
        return "message #${m.id} saved"
    }
}

class ListMessagesTool(private val messages: MessageManager) : Tool {
    override val name = "list_messages"
    override val description = "List pending person messages."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val list = messages.pending()
        return if (list.isEmpty()) "none" else list.joinToString("\n") { "#${it.id}: ${it.text}" }
    }
}
