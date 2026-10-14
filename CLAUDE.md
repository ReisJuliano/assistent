# CLAUDE.md — Assistent

Guide for anyone (human or AI) writing code in this repository: what the system is, how every part works,
where things live, conventions, and the gotchas we already hit.

## What it is

An open-source Android "robot assistant": a full-screen animated face (neon eyes, brows, mouth, optional arms)
on a black background that talks by voice, searches the web, knows the weather, remembers things, sets
reminders/alarms, triggers webhooks, recognizes faces, lives a little life of its own (hobbies, dreams,
reactions), and can run on several devices as one robot. Everything is configurable at runtime and through a
local REST API.

- Language: **Kotlin**, UI in **Jetpack Compose**, single module `:app`, package `io.github.assistent`.
- minSdk 26, targetSdk 35, compileSdk 36, JDK 17.
- The LLM is any OpenAI-compatible `/chat/completions` endpoint. Default: **CLIProxyAPI** with ChatGPT (Codex)
  OAuth, model `gpt-6.1-sol`.
- User-facing text is **pt-BR**; code, identifiers, comments and logs are **English**.

## Build, run, test

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot"   # any JDK 17
./gradlew assembleDebug                 # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # JVM unit tests (app/src/test)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.assistent/.MainActivity
```

- Gradle wrapper is **8.14.3** on purpose (AGP 8.12, Kotlin 2.2.0, KSP 2.2.0-2.0.2). The locally cached Gradle
  9.3.1 distribution failed even on an empty Kotlin-DSL project; don't "upgrade" the wrapper without testing.
- `local.properties` (sdk.dir) is git-ignored; create it locally.
- Emulator used during development: AVD `assistent_tablet` (Pixel Tablet, API 34, landscape). Set its time
  zone if the clock looks wrong: `adb root && adb shell setprop persist.sys.timezone America/Sao_Paulo`.

### Testing the running app through its API

The fastest feedback loop. Forward the port, read the token from the app's private config, then drive it:

```bash
adb forward tcp:8765 tcp:8765
T=$(adb shell run-as io.github.assistent cat files/config.json | grep -o '"token":"[0-9a-f]*"' | cut -d'"' -f4)
curl -X POST localhost:8765/api/v1/emotion -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"emotion":"joy","holdMs":0}'
curl -X POST localhost:8765/api/v1/scene   -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"scene":"gaming"}'
adb exec-out screencap -p > shot.png
```

Send non-ASCII JSON from a UTF-8 file (`--data-binary @file.json`): Windows shells mangle accents inline.
When scripting edits from Git Bash, avoid heredocs with non-ASCII/Unicode characters (they break); write a
Python patch file instead.

## Architecture

```
AssistentApp / AppGraph   manual DI: every component is a process-wide singleton built in AppGraph
config/                   AppConfig (all settings, @Serializable), ConfigStore (JSON in filesDir, live StateFlow)
core/
  AssistantState          what the face shows: Activity, Emotion, bubble, scene, ambient weather, gaze
  AssistantRuntime        the "body": chat/say/listen/converse, emotions, sleep/wake, bubble, motion, reminders
  LifeDirector            hobbies while idle, dreams while asleep, wake-up reactions, real-weather polling, camera mimic
  Greeter + Catchphrases  greets people walking by (speech bubble), context-aware lines
  PhrasePool              AI-written phrase batches per category, saved and drawn at random
  Mesh                    multi-device: QR pairing, shared config/faces/memories, presence per room, say-in-room
  EventDispatcher         SSE stream + outgoing event webhooks
brain/
  LlmClient               OpenAI-compatible chat + /models
  Brain                   history, system prompt (personality, tone, length, memories), tool-call loop, [emotion] tag parsing
tools/                    Tool interface + ToolRegistry; web search, read page, weather, memory, reminders, device,
                          webhooks (one tool per configured webhook), mesh tools (where_is, say_in_room, list_rooms)
voice/
  WakeWordEngine          Vosk offline, fuzzy-matched free phrases ("{name}" + aliases), returns trailing command
  Listener (Stt.kt)       Android SpeechRecognizer or Whisper-compatible API with energy VAD
  Speaker (Tts.kt)        Android TTS / OpenAI-compatible (Kokoro) / ElevenLabs → PcmPlayer
  PcmPlayer               AudioTrack streaming; loudness per chunk indexed by playback head (lip sync)
vision/
  FaceTracker             CameraX + ML Kit: face position/smile/tilt/winks, motion detection, object labels, QR scan, face crops
  People                  MobileFaceNet TFLite embeddings (downloaded), enroll/identify by cosine similarity
face/
  FaceParams/EyeShape     every visual knob; FacePresets = emotion + activity presets
  RobotFace               Canvas renderer + FaceAnimator (spring physics per parameter)
  LifeScenes              Scene enum, per-scene scripts (Life.frame) and neon props
  Arms                    pose per scene/emotion + chubby droplet arm renderer
  Ambience                rain/snow/fog/lightning background, heat sweat, cold breath
service/AssistantService  foreground (microphone) service: wake phrase ↔ manual trigger → converse loop
api/LocalApiServer        Ktor CIO REST + SSE + mesh endpoints; WebPanel = HTML control panel at "/"
reminders/                AlarmManager scheduling, notification, boot rescheduling
ui/                       Theme, SettingsScreen (long-press), SpeechBubble (VT323 retro font)
data/                     Room: memories, reminders, people (v2, migration 1→2)
```

### Data flow of a voice exchange

1. `AssistantService` waits on **either** `WakeWordEngine.awaitWake()` or `runtime.manualTrigger` (tap / API).
2. `runtime.converse(command)` → `listen()` if the command was empty → `chat(text)` → `Brain.ask()`.
3. `Brain` builds the system prompt (personality, tone, verbosity, date/time, location, recent memories),
   runs the tool loop (max `llm.maxToolRounds`), parses the leading `[emotion]` tag.
4. `runtime.say()` → `Speaker` → `PcmPlayer`; face shows `SPEAKING` and the mouth follows `player.level`.
5. Follow-up: listens again for `wake.followUpSeconds` without needing the wake phrase.

### The face

- **Never add per-emotion colors**: the face always uses the user's color (`face.color`). Expressions change
  shape and motion only. Sleep may dim brightness.
- `FaceParams` is flattened into a float array (`writeTo`/`paramsOf` in `RobotFace.kt`); **when you add a field,
  bump `FIELDS` and add it to both functions**. Each float is an independent damped spring (`animationStiffness`,
  `animationDamping`), so retargeting keeps velocity and never "jumps".
- Eye silhouettes are Cozmo-style (`EyeShape`: height, width, sloped top/bottom, top/bottom radii, crescent cut);
  slopes refer to the inner corner and are mirrored by the renderer. Prefer shaping the silhouette over drawing
  extra overlays.
- Activity overrides mood: `FacePresets.resolve(activity, emotion)`. `SPEAKING`/`IDLE` show the mood.
- Acting beats in `FaceAnimator.onExpressionChange`: blink on change, `enterPop`, `enterShake`.
- Eye styles: `solid`, `iris`, `cartoon`, `ring`, `orb` (`face.eyeStyle`).
- Layout is orientation-independent: everything derives from `unit = min(w*0.75, h*1.05) * scale`.
- Draw neon with `glow(path, color, alpha, stroke)` + the solid path; keep the black background.

### Life scenes (`face/LifeScenes.kt`)

- `Scene` has a key, duration, kind (`AWAKE` while idle, `DREAM` while sleeping, `WAKE` right after waking) and a
  pt-BR label (shown in Settings → Vida).
- `Life.frame(scene, t, seed, color, base)` returns a `SceneFrame` (target params, optional gaze, face offset, optional
  `stiffness` for snappy moments). It is evaluated every frame from the scene's local time `t` — deterministic,
  no state; use `seed` (scene start time) for per-run randomness.
- Props are drawn in `drawSceneProps` with the stroke/fill helpers (always the face color). Full-screen effects must
  draw an oversized rect because props are inside the head-tilt rotation.
- Arms: add a pose for the scene in `Arms.pose` (hand positions in face units relative to the face center; `null`
  tucks the arm away). Keep hands outside the face area unless touching it on purpose (chin, mouth, eye).
- To add a scene: enum entry → `Life.frame` branch → props (optional) → arms (optional) → maybe a weight in
  `LifeDirector.pickAwake` and a mimic keyword in `LifeDirector.MIMIC`. Then test with `POST /api/v1/scene`.

### Rhythm of the day, rarity and real-life rules (`core/LifeDirector.kt`)

- Life is **random, not scheduled** (owner's explicit wish): every 0.5 s tick each kind of thing has a small chance
  (`chance(everySeconds)`): a hobby about once per `(minGap+maxGap)/2`, a comment about every 20 min (half of them about
  the weather when it is notable: `weather_*` phrase categories, one-shot AI batches), weather moments every few hours.
  No queues, budgets or "N seconds since the last event" gates. Several things may overlap: hobbies keep going while
  it talks, comments can pop up during a hobby. Spontaneous speech (`say(spontaneous = true)`) never counts as a
  conversation, and it never *speaks* to an empty room.
- `pickAwake` encodes real-life rules: no dog walk in rain/fog/late night, stars only on clear nights, juice when hot,
  tea when cold, no workout in the heat, snacks around lunch/dinner, sleepier choices at night.
- Time of day: idle face gets heavier eyelids at night/early morning (`daytimeMood` in `RobotFace.kt`), the sleep timer
  is shortened by `sleepFactor()`, and idle quips use time-specific phrase categories (`quip_morning`, `quip_lunch`…).
- Weather is shown as **moments** (`startWeatherMoment`): a few times a day for 1–2 min when the weather is notable,
  with its own scene (`WX_RAIN` umbrella, `WX_COLD` scarf + hot drink, `WX_HOT` sunglasses + sun). `life.weatherAlways`
  shows it permanently. The umbrella geometry (`UmbrellaGeo`) is shared with the rain renderer so drops stop at the canopy.
- Sleep/wake rules: scares only come from sudden motion while asleep; it only *talks* when a real face is confirmed in
  front of the camera; after waking up on its own it dozes off again if nobody interacts (`dozeOffIfAlone`).

### Social interactions (`core/Interactions.kt`)

Reactions to what people do, each with its own cooldown, played as `Scene.Kind.REACT` scenes (allowed whenever
awake, not cleared by conversation):

| Input | Detection | Reaction |
|---|---|---|
| Waving at the camera | motion-centroid x oscillating ≥3 times in 2 s with a face present (`FaceTracker.detectMotion` → `onMotionCentroid`) | `WAVE_BACK` + "Oi! 👋" |
| Music | phone: `AudioManager.isMusicActive`; room: sustained sound + regular onset autocorrelation on the wake-word audio loop (`onAudioLevel`, 50 ms frames) | `DANCE` to the estimated BPM (`Life.danceBpm`) |
| Slow strokes / fast scribbles on the face | drag gestures in `MainActivity` | `PURR` / `GIGGLE` |
| Shaking the phone | accelerometer peaks | `WAKE_DIZZY` + complaint |
| Charger / low battery | `ACTION_POWER_CONNECTED` / `ACTION_BATTERY_LOW` | `CHARGE` / tired line |
| Covering the camera | mean brightness < 14 for 1.2 s | `WHERE_ARE_YOU`, then `FOUND_YOU` when uncovered |
| Face very close / long stare / nod / head shake | `Gaze.size`, gaze+yaw stable 14 s, yaw/pitch oscillation | `TOO_CLOSE` / `SHY` / `HEAD_NOD` / `HEAD_SHAKE` |
| Known person leaves after ≥1 min | face gone 20 s | `WAVE_BACK` + "Tchau, {n}!" |
| Loud sudden sound | RMS spike | quick surprise (or the motion wake-up logic while asleep) |

### Wake phrase (`voice/WakeWord.kt`) — works for any name, nothing is hard-coded

- `knows(model, word)` calls the native `vosk_model_find_word` through JNA: a Vosk grammar silently drops words that
  are not in the model vocabulary. Phrases whose words are all known are detected by a **grammar recognizer** (phrases +
  greetings + `[unk]`, word confidences); unknown names fall back to fuzzy matching of the free decoder (and Settings
  warns, suggesting `goodNames()`).
- A grammar hit only counts as a call when `isCall` agrees with the free decoder (word timings, same frames): no other
  word right before it (mid-sentence: "comprei um parafuso"), and no longer word containing it over the same stretch
  ("anexo" ⊃ "nexo"). The command is the free decoder's words after the name's end time.
- `POST /wake/selftest {"text"}` synthesises the text with the system voice and runs the detector offline; `GET /wake/names`
  lists unknown name words and good names. TTS self-test, 16 phrases × 8 names: 14 right; misses were "nexo, liga…"
  (heard as "anexo") and an article swallowed before a name.

### Speaking while generating and barge-in (`voice/SpeechPipeline.kt`, see docs/STREAMING_E_INTERRUPCAO.md)

- `Speaker.synthesize(text) → SpeechClip` and `Speaker.playClip` are separate (own locks): `SpeechPipeline` synthesises up
  to `tts.prefetch` clips ahead while playing. Measured on the emulator: 25 sentences, gaps 0–4 ms.
  `heardText()` = finished clips + the played fraction of the current one (`PcmPlayer.playedFraction`).
- `voice.bargeIn = name` (default): while speaking, `WakeWordEngine.awaitBargeIn` listens (VOICE_COMMUNICATION + AEC + NS)
  with a grammar of the wake phrases + `voice.bargeInStopWords`; words the robot is saying right now never count. Stop
  word → quiet; name → listen for the new command. `voice` mode = energy detector. `POST /stop` cancels the LLM stream,
  the pipeline and records what was heard; "o que você ia falar?" resumes from there (verified).

`WakeWordEngine.awaitWake` runs its own `AudioRecord` loop (feeding Vosk with `acceptWaveForm`) precisely so the same
microphone stream can be analysed for music and loud sounds. The Portuguese small model has the old flat layout
(`final.mdl` at the root), so the model root is detected by `final.mdl` or `am/final.mdl`.

### LLM providers (`brain/LlmClient.kt`, `ChatGptAuth.kt`, `ResponsesClient.kt`)

- `llm.provider = "proxy"`: any OpenAI-compatible `/chat/completions` (CLIProxyAPI on a PC, Gemini, Groq, Ollama...).
- `llm.provider = "chatgpt"`: official **Sign in with ChatGPT** plan usage for open-source/local apps
  (developers.openai.com/siwc). OAuth+PKCE in the system browser, `client_id=dynamic_agent_client` on first registration
  (then the issued `oaiapp_...`), stable `ext_agent_host_id`, loopback callback on `127.0.0.1:1455/auth/callback`
  (listener open only while signing in), ID token verified against the JWKS. Tokens live in `filesDir/chatgpt-auth.json`,
  never in `config.json` (so not shared over the mesh); each device signs in on its own. `ResponsesClient` translates
  Chat Completions history/tools to `POST api.openai.com/v1/responses` (`store=false`, `stream=true`, no system items,
  no sampling params). It spends the plan's Codex allowance; usage-limit errors pause calls via `LlmQuota`.
- Never automate the chatgpt.com web chat (`backend-api`): against the terms and blocked by anti-bot checks.

### Screen light (`face/LightGag.kt`)

The screen can work as a lamp (dark room, face recognition in low light, "acende a luz"): a gag scene
(`LIGHT_LAMP` pulls a desk-lamp chain, `LIGHT_PHONE` fumbles a phone flashlight that fails once, `LIGHT_BULB` a
bright idea that needs sunglasses) switches on a white panel. While lit, `RobotFace` draws the face in a layer,
re-inks it with a dark shade of the user's color (`SrcAtop`) and puts white behind it (`DstOver`); `MainActivity`
forces full backlight. Everything is a function of scene time plus `LightGag.offT` (from `AssistantState.lightOffAt`),
so switching off replays the gesture. Entry points: `runtime.lightOn/lightOff`, tool `screen_light`, `POST /light`.
Other scenes cannot interrupt a light scene.

### Self-update (`update/Updater.kt`)

- `./gradlew publishUpdate` builds and copies the APK to `updates/` (git-ignored) with `latest.json` (versionCode, SHA-256).
  `versionCode` = minutes since 2026-01-01, so every build is newer. Serve the folder with `tools/serve-updates.ps1`
  (port 8318) and set the app's `update.url` (Settings → Atualizações) to `http://<pc-ip>:8318/latest.json`.
- Normal path: `./gradlew publishRelease` (needs `gh auth login`) creates GitHub release `v<versionName>` with the APK and
  a `latest.json` pointing at that tag. The default `update.url` is `releases/latest/download/latest.json`, so devices
  update from anywhere. Releases must be built on the machine holding the signing key (debug keystore today).
- The app polls every `update.checkMinutes`, verifies SHA-256 and installs with `PackageInstaller`. The very first update of
  an ADB-installed app asks for one confirmation; afterwards the app is the installer of record and, with
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, updates silently. `MY_PACKAGE_REPLACED` reopens `MainActivity` **only if the
  face was on screen before the update and the phone is still on and unlocked** (`userIsLooking`); a dark/locked phone
  stays untouched, and the one-time confirmation becomes a notification instead of a dialog. Verified on the emulator.

### Recognition (`vision/People.kt`, `voice/VoiceIdentityManager.kt`)

- Face: ML Kit eye landmarks → ArcFace-style alignment to 112×112 (`FaceTracker.alignedFace`), quality gate (yaw ≤ 28°,
  pitch ≤ 22°, eyes open), embedding averaged with its mirror image, score = ½·centroid + ½·mean of top-3 samples, temporal
  stabilization, self-learning of new confident views (max 30). `PIPELINE_VERSION` bump clears incompatible old samples.
- Voice: sherpa-onnx CAM++ speaker embeddings (AAR downloaded by the `downloadSherpaOnnx` Gradle task into git-ignored
  `app/libs/`; model downloaded at runtime). The wake loop keeps the last 4 s of audio; the wake phrase audio identifies who
  called (`PresenceTracker`). Enrollment captures ~10 s of voiced audio from the same loop. WAV routes
  `/voice/enroll-audio` and `/voice/identify` allow testing: 7/7 correct on held-out sherpa sample speakers, unknowns ~0.36.

### Presence around the house (`vision/BodyWatcher.kt`, `core/RoomPresence.kt`)

- `FaceTracker` hands an upright 480 px frame ~1/s to `BodyWatcher`: EfficientDet-Lite0 with built-in NMS
  (`people.objectModelUrl`, COCO labels read from the model's embedded zip) finds people, pets and objects
  (`POST /vision/detect`; COCO samples: person 0.91, two cats). Objects feed mimicry and pet reactions.
- Outfit of the day: when a face is recognised, the torso below it is stored as an HSV histogram; later a body
  without a recognised face is compared (histogram intersection ≥ 0.72 with a 0.08 margin) → "noticed".
- Voice in the room: YAMNet "speech" → speaker ID on the last 3 s of the wake-loop ring buffer (every ≥15 s, never
  with music on).
- These are weak signals: `PresenceTracker.onNoticed` updates last-seen, mesh presence and the `person_noticed`
  event, but never `currentPerson` (the trusted, face-only identity used by the brain).

### Robot voice (`voice/RobotVoice.kt`)

Real-time DSP on every TTS output in `PcmPlayer`: ring modulation + feedback comb (metallic) + optional bit-crush, presets
`off/light/classic/heavy/dalek/radio/arcade` (`tts.robotVoice`, default `classic`); radio/dalek add one-pole band limiting.

### Reminder lines and humour (`core/ReminderPhrases.kt`, `Brain.humorInstruction`)

- A firing reminder is spoken through `runtime.reminderLine(r, owner)`: the reminder's own `phrase` (exact words,
  `{n}`/`{msg}`) → an AI line in the configured tone + the reminder's `style` (8 s timeout, skipped while
  `LlmQuota.blocked`, toggle `reminders.aiPhrases`) → hand-written templates per type. Unconfirmed repeats (nags)
  get more insistent. Medicine lines must keep name/dose and never joke. Preview with `GET /reminders/{id}/preview`.
- `personality.jokes` (`auto` = descontraido/sarcastico/dramatico/alegre tones, `always`, `never`) adds a system-prompt
  line asking for one short joke about the subject itself (search results, weather, facts) after the useful answer,
  never on sad/health/emergency topics or at the user's expense.

### Hearing (`voice/SoundEars.kt`, `core/MusicDetector.kt`)

- YAMNet (AudioSet, 521 classes; `life.soundModelUrl`, downloaded at runtime) classifies the last ~1 s of the wake-word
  microphone stream once a second (`onPcm` feed). `SoundEars.GROUPS` maps class indices to kinds (speech, music, cat,
  dog, doorbell, knock, sneeze, laughter, baby_cry, thunder...) → `Interactions.onSound` reacts sometimes
  (`CAT_CUDDLE` kitten in the arms, `DOG_PAT`, "Saúde!", door/thunder bubbles). `POST /sounds/classify` (WAV) shows the
  scores; ESC-50 clips scored cat 0.98, dog 0.89, knock 0.94, thunder 0.85, applause 0.89.
- Music (YAMNet or the beat detector) sets `Interactions.musicPlaying`: wake phrases then need a near-exact match
  (0.86, no partial hand-off) and there is no hands-free follow-up, so lyrics are not taken as commands.

### Phrases (`core/PhrasePool.kt`, `core/Catchphrases.kt`)

- Every short line said spontaneously comes from a **category** with hand-written examples. The AI generates batches
  of 10 in the same style (`PhrasePool.refill`), they are saved in `filesDir/phrases.json` (max 80/category), and
  `pick()` draws among AI + examples avoiding recent repeats. Facts are consumed once.
- Categories that address someone by name use `{n}`; unknown-person variants use the `_anon` suffix.
- To add a category: add it to `PhrasePool.CATEGORIES` (description for the AI) and call
  `Catchphrases.line("category", examples)` or `cat(...)` inside `Catchphrases.candidates`.

### Config

- Add settings to `AppConfig.kt` with defaults (old JSON files keep working: unknown keys ignored, missing keys get
  defaults). Changes apply live: observe `config.config` (StateFlow) or read `config.current` at use time.
- Expose new settings in `SettingsScreen`: pick the tab (`SettingsTab`: FACE, BRAIN, VOICE, LIFE, PEOPLE, HOME, SYSTEM; branch of
  `when (tab)` in `SettingsList`) and use the section helpers (`Section`/`Field`/`SliderRow`/`SwitchRow`/`Segmented`) — every
  edit is saved immediately; there is no save button.
- `device`, `mesh` and `api` are per-device; everything else is shared across the mesh (`Mesh.shared`).

### Tools

Implement `Tool` (`name`, `description`, JSON-schema `parameters` via `params { … }`, `activity` for the face while
it runs, `execute(args)` returning text for the model) and register it in `AppGraph` (or `tools.register` at runtime).
It becomes available to the LLM and at `POST /api/v1/tools/{name}`. Webhooks from config become `hook_<id>` tools.

### Local API

All routes under `/api/v1` require `Authorization: Bearer <token>` (or `?token=`), except `/health` and the
device-to-device `/api/v1/mesh/*` routes (mesh secret header `X-Mesh-Secret`, or the one-time pairing code for
`/mesh/join`). Document new routes in `docs/API.md`.

## Conventions

- Match the surrounding style: small classes, KDoc on public pieces explaining *why*, no comment noise.
- Coroutines: long work on `Dispatchers.IO`/`Default`; `AppGraph.scope` for app-lifetime jobs; rethrow
  `CancellationException` when catching broad exceptions.
- Never block the UI thread; the face redraws every frame through a `tick` state read only in the draw phase.
- Don't add color tints, bundled models or binaries; large models are downloaded at runtime to `filesDir`
  (Vosk, MobileFaceNet). The VT323 font is OFL-licensed (`docs/VT323-OFL.txt`).
- No secrets in code or repo: keys/tokens live only in the app's private config (backups disabled).
- After any change: `./gradlew assembleDebug testDebugUnitTest`, then check it on the emulator via the API and a screenshot.

## Not done yet / known limits

- Mesh pairing was only tested on one emulator (QR/pair API); verify with two real devices.
- Face recognition quality depends on the downloaded MobileFaceNet and lighting; threshold is configurable.
- ML Kit image labels for mimicry (headphones, cup…) are best-effort; tune `LifeDirector.MIMIC`.
- No streaming LLM responses yet (replies take 3–10 s); no barge-in; no speaker identification.
