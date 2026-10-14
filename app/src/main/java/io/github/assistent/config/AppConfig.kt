package io.github.assistent.config

import kotlinx.serialization.Serializable

/**
 * Whole app configuration. Everything here is editable from the settings screen
 * and from the local REST API (`GET/PUT/PATCH /api/v1/config`).
 * Secrets (API keys, token) live only in app-private storage and are never backed up.
 */
@Serializable
data class AppConfig(
    /** Schema version of this file; [io.github.assistent.config.ConfigStore] migrates older files on load. */
    val version: Int = 0,
    val llm: LlmConfig = LlmConfig(),
    val personality: PersonalityConfig = PersonalityConfig(),
    val stt: SttConfig = SttConfig(),
    val tts: TtsConfig = TtsConfig(),
    val wake: WakeConfig = WakeConfig(),
    val search: SearchConfig = SearchConfig(),
    val weather: WeatherConfig = WeatherConfig(),
    val api: ApiConfig = ApiConfig(),
    val face: FaceConfig = FaceConfig(),
    val events: EventsConfig = EventsConfig(),
    val life: LifeConfig = LifeConfig(),
    val update: UpdateConfig = UpdateConfig(),
    val reminders: RemindersConfig = RemindersConfig(),
    val people: PeopleConfig = PeopleConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val spotify: SpotifyConfig = SpotifyConfig(),
    /** This physical device (never synced). */
    val device: DeviceConfig = DeviceConfig(),
    /** Other devices that are "the same robot" (never synced as config). */
    val mesh: MeshConfig = MeshConfig(),
    val webhooks: List<WebhookDef> = emptyList(),
)

@Serializable
data class MemoryConfig(
    val autoExtract: Boolean = true,
)

@Serializable
data class SpotifyConfig(
    val clientId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
)

/** OpenAI-compatible endpoint. Default target: CLIProxyAPI with ChatGPT (Codex) OAuth. */
@Serializable
data class LlmConfig(
    /**
     * "proxy" = any OpenAI-compatible `/chat/completions` endpoint ([baseUrl], e.g. CLIProxyAPI);
     * "chatgpt" = official "Sign in with ChatGPT" plan usage on the Responses API (no proxy, works on any network).
     */
    val provider: String = "proxy",
    val baseUrl: String = "http://192.168.0.10:8317/v1",
    val apiKey: String = "",
    val model: String = "gpt-6.1-sol",
    /** low | medium | high — "low" answers faster and spends less of the account's usage limit. Empty = provider default. */
    val reasoningEffort: String = "low",
    val maxToolRounds: Int = 6,
    val historySize: Int = 20,
    val timeoutSeconds: Int = 120,
)

@Serializable
data class PersonalityConfig(
    val name: String = "Nexo",
    val language: String = "pt-BR",
    val traits: String = "curious, warm, playful, a little witty, genuinely helpful",
    val userName: String = "",
    /**
     * Mood of the answers: natural, alegre, descontraido, rispido, formal, sarcastico, fofo, dramatico, or "custom"
     * (then [customTone] is used).
     */
    val tone: String = "natural",
    val customTone: String = "",
    /** short, medium, detailed */
    val verbosity: String = "short",
    val extraInstructions: String = "",
    /**
     * Topical jokes woven into answers (about the subject, search results, weather...): "auto" (only in the
     * descontraido/sarcastico/dramatico/alegre tones), "always" or "never".
     */
    val jokes: String = "auto",
    /** Max memories injected in the system prompt each turn. */
    val memoriesInPrompt: Int = 15,
)

@Serializable
data class SttConfig(
    /** "android" (system SpeechRecognizer) or "openai" (any Whisper-compatible /audio/transcriptions). */
    val provider: String = "android",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "whisper-1",
    val language: String = "pt",
    val silenceMs: Int = 1200,
    val maxSeconds: Int = 20,
)

@Serializable
data class TtsConfig(
    /** Robot effect applied to any voice: off, light, classic, heavy. */
    val robotVoice: String = "classic",
    /** "android", "openai" (OpenAI or any compatible server, e.g. Kokoro-FastAPI) or "elevenlabs". */
    val provider: String = "android",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini-tts",
    val voice: String = "nova",
    val instructions: String = "Friendly, upbeat little robot voice. Natural Brazilian Portuguese.",
    val speed: Float = 1.0f,
    val elevenLabsVoiceId: String = "",
    val elevenLabsModel: String = "eleven_multilingual_v2",
    /** Android TTS only: preferred voice name (empty = best available for the language). */
    val androidVoice: String = "",
    /** Android TTS only: prefer a voice whose engine metadata identifies it as masculine. */
    val androidPreferMale: Boolean = false,
    /** Android TTS only: lower pitch and slightly slower rhythm for a synthetic robot character. */
    val androidRobot: Boolean = false,
    val pitch: Float = 1.1f,
)

@Serializable
data class WakeConfig(
    val enabled: Boolean = true,
    /** `{name}` is replaced by personality.name. Any of these wakes the assistant. */
    val phrases: List<String> = listOf(
        "{name}", "ei {name}", "oi {name}", "olá {name}", "e aí {name}",
        "fala {name}", "ok {name}", "hey {name}", "acorda {name}",
    ),
    /** Extra spellings the recognizer tends to produce for the name. */
    val nameAliases: List<String> = emptyList(),
    /** 0..1, higher = stricter matching. */
    val sensitivity: Float = 0.82f,
    val modelUrl: String = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
    val sleepAfterSeconds: Int = 600,
    /** After answering, keep listening this long without needing the wake phrase. 0 disables. */
    val followUpSeconds: Int = 6,
)

@Serializable
data class SearchConfig(
    /** "duckduckgo" (no key), "searxng", "brave" or "tavily". */
    val provider: String = "duckduckgo",
    val searxngUrl: String = "",
    val braveApiKey: String = "",
    val tavilyApiKey: String = "",
    val maxResults: Int = 5,
)

@Serializable
data class WeatherConfig(
    /** Use "city, state/province, country" so geocoding does not pick the wrong city. */
    val defaultLocation: String = "São Paulo, SP, Brasil",
)

@Serializable
data class ApiConfig(
    val enabled: Boolean = true,
    val port: Int = 8765,
    /** false = only reachable from the phone itself (127.0.0.1). */
    val allowLan: Boolean = true,
    /** Generated on first run. Send as `Authorization: Bearer <token>`. */
    val token: String = "",
)

/**
 * Face look. Changes apply live (no restart), from settings or `PATCH /api/v1/config`.
 * The screen always shows only the face on black; settings open with a long press.
 */
@Serializable
data class FaceConfig(
    /** Main color of eyes and mouth. Default: cyan. */
    val color: String = "#3DE8FF",
    /** 0.6..1.6 */
    val eyeSize: Float = 1f,
    /** 0.6..1.6 */
    val eyeSpacing: Float = 1f,
    /** 0 = square … 1 = pill/circle. */
    val eyeRoundness: Float = 0.45f,
    /** Height/width ratio of the eye. 1.1 = slightly tall (default robot look). */
    val eyeAspect: Float = 1.1f,
    /**
     * "solid" glowing shape, no iris (EMO-like) · "iris" dark iris + pupil · "cartoon" light eye, colored iris,
     * black pupil · "ring" neon outline with a glowing pupil · "orb" big black pupil with glints.
     */
    val eyeStyle: String = "iris",
    /** Iris diameter relative to the eye width, 0.3..0.9 */
    val irisSize: Float = 0.58f,
    /** Pupil diameter relative to the iris, 0.2..0.8 */
    val pupilSize: Float = 0.5f,
    /** Little eyebrow strokes that follow the emotion. */
    val showBrows: Boolean = true,
    /** 0.5..2.5 */
    val browThickness: Float = 1f,
    val showMouth: Boolean = true,
    /** Little arms that hold props and gesture (wave, shrug, hand on chin…). */
    val showArms: Boolean = true,
    /** Round droplet hands at the end of the arms (off = arms end in a rounded tip). */
    val armHands: Boolean = false,
    /** 0.5..1.6 */
    val mouthSize: Float = 1f,
    /** 1..3, stroke thickness. */
    val mouthThickness: Float = 1f,
    /** 0 = no glow … 1 = strong neon glow. */
    val glow: Float = 0.6f,
    /** Overall face scale relative to the screen. */
    val scale: Float = 1f,
    /** Vertical position, −0.3..0.3 (fraction of screen). */
    val offsetY: Float = 0f,
    /** Spring stiffness of expression changes. Lower = softer/slower. */
    val animationStiffness: Float = 120f,
    /** 0.4 bouncy … 1 no overshoot. */
    val animationDamping: Float = 0.65f,
    /** Random eye movement and micro-expressions while idle. */
    val idleMotion: Boolean = true,
    val cameraTracking: Boolean = true,
    /** Small text of what was heard/answered. Off = only the face. */
    val showSubtitles: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Small analog clock in a corner. */
    val showClock: Boolean = true,
    /** top_left, top_right, bottom_left, bottom_right */
    val clockCorner: String = "top_right",
    /** "digital" (retro 7-segment) or "analog". */
    val clockStyle: String = "digital",
    /** "auto" (follows sensor), "portrait" or "landscape". */
    val orientation: String = "auto",
)

/** "Alive" behaviors: hobbies while idle, dreams, wake-up reactions, weather ambience, mimicking the camera. */
@Serializable
data class LifeConfig(
    val enabled: Boolean = true,
    /** Seconds without conversation before it starts doing things on its own. */
    val idleSeconds: Int = 120,
    /** Random gap between two activities: hours, on purpose — it should feel spontaneous, never busy. */
    val minGapSeconds: Int = 300,
    val maxGapSeconds: Int = 20 * 60,
    /** While sleeping, sometimes wakes up on its own, looks around and asks if anyone is there. */
    val selfWake: Boolean = true,
    val dreams: Boolean = true,
    val wakeReactions: Boolean = true,
    /** While sleeping, movement in front of the camera may wake it up (startled, groggy, or a quick peek). */
    val wakeOnMotion: Boolean = true,
    /** Rain/snow on screen, sweating when hot, shivering when cold (real weather of weather.defaultLocation). */
    val weatherEffects: Boolean = true,
    /** false = weather shows up as "moments" a few times a day (with its own animation); true = always on screen. */
    val weatherAlways: Boolean = false,
    /** Copy what the camera sees: head tilt, winks, headphones, a cup, a book… */
    val mimic: Boolean = true,
    /** Dance to music heard in the room (microphone analysis; can be fooled by noise). Phone music always counts. */
    val roomMusic: Boolean = true,
    /** Recognise sounds in the room (YAMNet on the phone): cat, dog, doorbell, sneeze, music vs speech... */
    val soundEvents: Boolean = true,
    val soundModelUrl: String = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite",
    /** Lower camera/frame rates and lighter processing to keep the phone cool. */
    val powerSave: Boolean = true,
    /** Scene keys allowed to play on their own. Empty = all. */
    val scenes: List<String> = emptyList(),
)

/** Face recognition (on-device MobileFaceNet) and greetings when someone walks by. */
@Serializable
data class PeopleConfig(
    val recognition: Boolean = true,
    /** Recognise who is talking in the room (speaker ID on speech the microphone hears), not only on the wake phrase. */
    val voiceInRoom: Boolean = true,
    /** Remember each person's clothes of the day and guess them by their body when the face isn't visible. */
    val outfits: Boolean = true,
    /** Object/body detector (EfficientDet-Lite0, COCO), downloaded on first use. */
    val objectModelUrl: String = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite",
    /** TFLite face-embedding model, downloaded on first use. */
    val modelUrl: String = "https://raw.githubusercontent.com/MCarlomagno/FaceRecognitionAuth/master/assets/mobilefacenet.tflite",
    /** Cosine similarity needed to say "that's you", 0..1. */
    val threshold: Float = 0.62f,
    val greet: Boolean = true,
    /** Also say the greeting out loud (otherwise only the speech bubble). */
    val greetSpeak: Boolean = false,
    /** Greet unknown faces too (without a name). */
    val greetUnknown: Boolean = false,
    /** Don't greet the same person again before this many minutes. */
    val greetAfterMinutes: Int = 180,
    /** Speaker-embedding model (sherpa-onnx CAM++), downloaded on first use. */
    val voiceModelUrl: String = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
    /** Cosine score needed to recognize someone by voice (0..1). */
    val voiceThreshold: Float = 0.5f,
    /** Ask the AI for a fresh one-liner instead of the built-in catchphrases. */
    val greetWithAi: Boolean = false,
)

@Serializable
data class DeviceConfig(
    /** Generated on first run. */
    val id: String = "",
    /** Room this device lives in: Sala, Cozinha, Quarto… */
    val room: String = "Sala",
)

/** Multi-device: every paired device shares config, faces, memories and knows where people are. */
@Serializable
data class MeshConfig(
    /** Shared secret of the mesh, set when pairing. Empty = not paired. */
    val secret: String = "",
    val peers: List<MeshPeer> = emptyList(),
)

@Serializable
data class MeshPeer(
    val id: String,
    val room: String,
    val host: String,
    val port: Int,
    val lastSeen: Long = 0,
)

@Serializable
data class RemindersConfig(
    /** Let the AI rewrite each reminder as a fresh line in the configured tone (falls back to templates). */
    val aiPhrases: Boolean = true,
)

/** Self-update from a `latest.json` manifest (see `./gradlew publishUpdate`). */
@Serializable
data class UpdateConfig(
    val enabled: Boolean = true,
    /** Manifest URL: the project's GitHub releases by default, or e.g. http://<pc-ip>:8318/latest.json. Empty = off. */
    val url: String = DEFAULT_URL,
    val checkMinutes: Int = 10,
) {
    companion object {
        const val DEFAULT_URL = "https://github.com/ReisJuliano/assistent/releases/latest/download/latest.json"
    }
}

/** Outgoing notifications: every URL receives `POST {event, data, ts}` for the listed events. */
@Serializable
data class EventsConfig(
    val webhookUrls: List<String> = emptyList(),
    /** Empty = all. Known: wake, sleep, user_said, reply, reminder, emotion, face_seen. */
    val events: List<String> = emptyList(),
)

/**
 * A user-defined HTTP action. Each one becomes an LLM tool named `hook_<id>`,
 * so the assistant can call it ("liga a luz da sala") and it can also be fired via API.
 * `{{param}}` placeholders are replaced in url, headers and body.
 */
@Serializable
data class WebhookDef(
    val id: String,
    val description: String,
    val method: String = "POST",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val params: List<WebhookParam> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class WebhookParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
)
