package io.github.assistent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mood shown on the face. The LLM picks one per reply with a leading `[tag]`. */
@Serializable
enum class Emotion {
    @SerialName("neutral") NEUTRAL,
    @SerialName("happy") HAPPY,
    @SerialName("joy") JOY,
    @SerialName("sad") SAD,
    @SerialName("surprised") SURPRISED,
    @SerialName("confused") CONFUSED,
    @SerialName("angry") ANGRY,
    @SerialName("love") LOVE,
    @SerialName("thinking") THINKING,
    @SerialName("sleepy") SLEEPY;

    val tag: String get() = name.lowercase()

    companion object {
        fun fromTag(tag: String?): Emotion? =
            entries.firstOrNull { it.tag == tag?.trim()?.lowercase() }
    }
}

/** What the assistant is doing right now. Overrides the mood on the face while active. */
@Serializable
enum class Activity {
    @SerialName("idle") IDLE,
    @SerialName("sleeping") SLEEPING,
    @SerialName("listening") LISTENING,
    @SerialName("thinking") THINKING,
    @SerialName("searching") SEARCHING,
    @SerialName("speaking") SPEAKING,
}

/** Normalized position of the user's face in the camera, -1..1, plus smile probability. */
@Serializable
data class Gaze(
    val x: Float = 0f,
    val y: Float = 0f,
    val smiling: Float = 0f,
    val present: Boolean = false,
    /** User head roll in degrees, already mirrored (the robot copies it like a reflection). */
    val tilt: Float = 0f,
    /** Eye open probabilities, mirrored: "left" is the eye on the robot's left side of the screen. */
    val leftEyeOpen: Float = 1f,
    val rightEyeOpen: Float = 1f,
    /** Face width relative to the camera frame (0..1): big = very close. */
    val size: Float = 0f,
    /** Head turn left/right and up/down, degrees. */
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

@Serializable
data class AssistantState(
    val activity: Activity = Activity.IDLE,
    val emotion: Emotion = Emotion.NEUTRAL,
    val lastHeard: String = "",
    val lastReply: String = "",
    val partial: String = "",
    val error: String? = null,
    val wakeReady: Boolean = false,
    val wakeModelProgress: Float? = null,
    val serviceRunning: Boolean = false,
    /** Life scene being played (see face/LifeScenes.kt), null = none. */
    val scene: String? = null,
    val sceneStartedAt: Long = 0L,
    val ambient: Ambient = Ambient(),
    /** A weather moment is happening: show the weather on screen now. */
    val weatherActive: Boolean = false,
    /** Speech bubble next to the face (greetings, quips), null = hidden. */
    val bubble: String? = null,
    val bubbleAt: Long = 0L,
    /** Visual feedback for the camera identity pipeline. */
    val faceStatus: String = "no_face",
    val faceIdentity: String? = null,
    val faceSimilarity: Float = 0f,
    /** Temporary visual light-assist effect used when face recognition is unsafe in darkness. */
    val lightAssist: Boolean = false,
)

/** Real weather where the robot lives, shown as ambience on the face (rain, snow, heat, cold). */
@Serializable
data class Ambient(
    /** clear, cloudy, fog, drizzle, rain, storm, snow */
    val condition: String = "clear",
    val temperature: Float? = null,
    val isDay: Boolean = true,
)
