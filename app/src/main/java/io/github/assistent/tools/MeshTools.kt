package io.github.assistent.tools

import io.github.assistent.core.Mesh
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lets the assistant use the other devices: where is someone, speak in another room, list rooms. */
class WhereIsTool(private val mesh: Mesh) : Tool {
    override val name = "where_is"
    override val description = "Where a person was last seen in the house (which room/device), using face recognition on all paired devices."
    override val parameters = params { string("person", "Person's name, e.g. 'Diogo'") }

    override suspend fun execute(args: JsonObject): String {
        val who = args.str("person") ?: return "missing person"
        val p = mesh.whereIs(who, withinMs = 24 * 3600_000L) ?: return "no recent sighting of $who"
        val time = Instant.ofEpochMilli(p.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val mins = (System.currentTimeMillis() - p.at) / 60_000
        return "$who was seen in ${p.room} at $time ($mins min ago)"
    }
}

class SayInRoomTool(private val mesh: Mesh) : Tool {
    override val name = "say_in_room"
    override val description = "Speak a message out loud on the device in another room (e.g. tell the kitchen that dinner is ready)."
    override val parameters = params {
        string("room", "Room name, e.g. Cozinha")
        string("message", "What to say there")
    }

    override suspend fun execute(args: JsonObject): String {
        val room = args.str("room") ?: return "missing room"
        val msg = args.str("message") ?: return "missing message"
        return if (mesh.sayInRoom(room, msg)) "said in $room" else "no device in room '$room'. Rooms: ${mesh.rooms().joinToString()}"
    }
}

class ListRoomsTool(private val mesh: Mesh) : Tool {
    override val name = "list_rooms"
    override val description = "List the rooms that have a paired device (this one included) and who is where."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val people = mesh.presence.value.values.joinToString { "${it.person} in ${it.room}" }
        return "rooms: ${mesh.rooms().joinToString()}. ${if (people.isBlank()) "" else "people: $people"}"
    }
}

class BroadcastTool(private val mesh: Mesh) : Tool {
    override val name = "broadcast"
    override val description = "Say a message on every paired room/device."
    override val parameters = params { string("text", "What to say") }

    override suspend fun execute(args: kotlinx.serialization.json.JsonObject): String {
        val text = args.str("text") ?: return "missing text"
        val count = mesh.broadcastSay(text)
        return "said on $count device(s)"
    }
}

class CallRoomTool(private val mesh: Mesh) : Tool {
    override val name = "call_room"
    override val description = "Start a simple intercom call request with another room. This announces the call; continuous voice channel is handled by the app UI/API."
    override val parameters = params { string("room", "Room name") }

    override suspend fun execute(args: JsonObject): String {
        val room = args.str("room") ?: return "missing room"
        return if (mesh.sayInRoom(room, "Chamando pelo interfone. Pode falar comigo por aqui.")) {
            "call request sent to $room"
        } else {
            "no device in room '$room'. Rooms: ${mesh.rooms().joinToString()}"
        }
    }
}
