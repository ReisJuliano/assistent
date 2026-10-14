package io.github.assistent.tools

import io.github.assistent.vision.People
import kotlinx.serialization.json.JsonObject

/** Voice-driven enrollment of the face currently visible to the camera. */
class RegisterFaceTool(private val people: People) : Tool {
    override val name = "register_face"
    override val description = "Register the face currently visible in the camera. Use when the user asks to register or save this face as a person. If name is missing, ask for it. After success, ask for an optional relationship or observation and call set_person_observation."
    override val parameters = params {
        string("name", "Name to give this person", required = false)
        string("observation", "Optional relationship or note, such as wife, friend, or coworker", required = false)
    }

    @Volatile var pendingName: String? = null

    override suspend fun execute(args: JsonObject): String {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isBlank()) return "question: Qual nome devo dar a essa pessoa?"
        val person = people.enroll(name)
            ?: return "error: could not register the visible face; keep the face in the camera and try again"
        val observation = args.str("observation")?.trim().orEmpty()
        if (observation.isBlank()) {
            pendingName = person.name
            return "registered face for '${person.name}'. question: ask whether the user wants to add an observation about this person"
        }
        people.updateNotes(person.name, observation)
        pendingName = null
        return "registered face for '${person.name}' with observation saved"
    }
}

class SetPersonObservationTool(
    private val people: People,
    private val registration: RegisterFaceTool,
) : Tool {
    override val name = "set_person_observation"
    override val description = "Save an observation or relationship for a person after face registration. If personName is omitted, use the person from the pending registration."
    override val parameters = params {
        string("observation", "Relationship or note about the person")
        string("personName", "Person name, optional when answering a registration question", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val observation = args.str("observation")?.trim().orEmpty()
        if (observation.isBlank()) return "error: observation is empty"
        val name = args.str("personName")?.trim().takeUnless { it.isNullOrBlank() } ?: registration.pendingName
            ?: return "error: there is no pending person registration"
        val saved = people.updateNotes(name, observation)
            ?: return "error: person '$name' is not enrolled"
        registration.pendingName = null
        return "observation saved for '${saved.name}'"
    }
}
