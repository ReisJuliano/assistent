package io.github.assistent.tools

import io.github.assistent.core.PresenceTracker
import io.github.assistent.lists.ListRepository
import kotlinx.serialization.json.JsonObject

class ListAddTool(private val lists: ListRepository, private val presence: PresenceTracker) : Tool {
    override val name = "list_add"
    override val description = "Add an item to a named list, such as compras or tarefas."
    override val parameters = params {
        string("list", "List name", required = false)
        string("text", "Item text")
        string("qty", "Quantity", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val item = lists.add(args.str("list") ?: "compras", args.str("text") ?: return "missing text", args.str("qty").orEmpty(), personId = presence.currentPerson.value?.id)
        return "added #${item.id}"
    }
}

class ListShowTool(private val lists: ListRepository) : Tool {
    override val name = "list_show"
    override val description = "Show items in a named list."
    override val parameters = params { string("list", "List name", required = false) }

    override suspend fun execute(args: JsonObject): String {
        val items = lists.items(args.str("list") ?: "compras")
        return if (items.isEmpty()) "empty" else items.joinToString("\n") { "#${it.id} [${if (it.done) "x" else " "}] ${it.text}${if (it.qty.isBlank()) "" else " (${it.qty})"}" }
    }
}

class ListCheckTool(private val lists: ListRepository) : Tool {
    override val name = "list_check"
    override val description = "Mark a list item done or not done by id."
    override val parameters = params {
        integer("id", "Item id")
        boolean("done", "Whether it is done", required = false)
    }

    override suspend fun execute(args: JsonObject): String =
        if (lists.check(args.num("id")?.toLong() ?: -1, args.bool("done") ?: true)) "updated" else "not found"
}

class ListRemoveTool(private val lists: ListRepository) : Tool {
    override val name = "list_remove"
    override val description = "Remove a list item by id."
    override val parameters = params { integer("id", "Item id") }

    override suspend fun execute(args: JsonObject): String =
        if (lists.remove(args.num("id")?.toLong() ?: -1)) "removed" else "not found"
}
