# Smriti: private visual memory (working title)

Offline Android app for the iQOO 15 (Snapdragon 8 Elite Gen 5). Camera in, text and face-blurred thumbnails out: raw camera frames are never stored.
It remembers what the camera saw as timestamped text, answers "what do you see" live, and later answers
"where are my keys" / "what happened in the last 10 minutes". Faces are pixelated before any model runs, personal
numbers are scrubbed from text, and "forget the last 5 minutes" really deletes.

Plans: `C:\Users\HP.DESKTOP-O3DFJA1\.claude\plans\jaunty-mixing-meadow.md` (v2 NPU, v3 product UX, v4 backend + dashboard)

## Demo in one command
```
scripts\demo.ps1          # installs, grants permissions, starts the dashboard, forwards the port, opens the site
```
Judges' website: **http://localhost:8765** (USB cable, works in airplane mode). It is served BY THE PHONE; the site is
bundled in the APK, with no CDN and no internet. In Settings > Developer dashboard you can also allow Wi-Fi with a PIN.

## Pipeline (backend)
```
phone camera | ESP32 glasses -> Gate (CPU) -> Privacy (face blur, FAILS CLOSED)
   -> { Detect | Label | OCR }   in parallel, on the blurred copy, each model swappable at runtime
   -> Zone -> Compose -> Store (text rows) -> Ledger (object last seen)
Question: Mic -> Speech -> Intent -> Retrieve (reads Memory + Ledger) -> Answer (rows only) -> Voice
```
- Every stage runs inside `StageGuard`: START/OK/ERROR/SKIPPED/DISABLED events with real durations; a failing stage is
  reported and the frame continues; 3 failures in a row trip the stage until it is re-armed.
- **Privacy fails closed:** if blurring fails the frame is discarded; no model sees it and nothing is stored.
- `PipelineBus` carries every event (ring buffer of 400 for late-joining dashboards); `CommandRouter` is the single
  place that turns a command (preset, model, toggle, re-arm, ask, forget, fault) into an action. The website's admin
  page and the debug `adb` broadcast both use it.
- Model catalog per role (detector / labeler / OCR / captioner). NPU captioner is listed as "not installed" until the
  iQOO 15 benchmark; it will plug in behind the same interface.

## Live dashboard (website)
Seven tabs, one job each, so nothing is crowded:
- **Live flow**: every stage is an icon tile (camera, gate, privacy, detect, label, text, place, note, memory, ledger, then
  mic, speech, intent, retrieve, answer, voice) joined by curved connectors whose dashed arrows flow. When the camera opens
  the Camera tile lights up and the lines start flowing; each frame sends glowing packets down the edges; a tile shows its
  model name, live milliseconds and what it found; a working tile spins a gear; frames the gate skips fall off; a tripped
  stage turns red and hatched. The question lane has READS arrows back from memory. Click a tile for details and a model swap.
- **Frames** (recent frames with a stacked time split, face-blurred preview, why frames were skipped), **Models** (presets, a
  card per role with latency stats, swap, on/off, re-arm), **Metrics** (speedometer gauges, area charts, funnel, network),
  **Logs** (level badges, stage icon, model name, labels found, ms, frame id; filter/search/pause/export), **Admin** (capture
  controls, actions, connection, fault injection, command history), **Users** (logged sessions).
- A clearly labelled SIMULATED mode appears if no phone answers.

Design: **iQOO Racing**: carbon-black metal panels, white type, the motorsport tri-stripe (light blue, deep blue, red) as
chrome accents, Rajdhani + JetBrains Mono, Phosphor icons. Chart series colours are the validated dark palette from the
`dataviz` skill (blue, orange-red, aqua, violet, checked with its validator) with dash patterns as a backup channel; gated or
skipped work is blue (good), not red. Source in `dashboard/`, built into `app/src/main/assets/dashboard/` by
`scripts\build-dashboard.ps1`. (The earlier strict brutalist style was replaced at the user's request.)

Security: inbound only. USB mode binds 127.0.0.1; LAN mode requires a PIN and rejects non-private clients; browser
connections must be same-origin. Preview (opt-in, off by default) streams only the already face-blurred frame, from
memory, never stored; the phone shows a badge while it is on.

## Voice, languages, haptics, privacy shield
- **Voice:** on-device recogniser first; if the phone has no pack for the language it falls back to the phone's system speech service (the Live strip then says SYSTEM VOICE). Settings > Privacy > Strict offline voice never falls back.
- **Languages:** Hindi is understood and answered in full (also when spoken in Latin letters). Tamil, Telugu, Kannada, Malayalam, Marathi, Bengali and Gujarati are understood (where-is, what-do-you-see, last-N-minutes, forget, common objects); answers are in English with an English voice. English text is always the fallback. Source: `query/MultiLang.kt`, `query/Localizer.kt`.
- **Haptics:** every button, tab and switch ticks; mic start, "heard you", answer start, errors, session end and "Hey Smriti" each have their own pattern (`util/Haptics.kt`). Strength and on/off in Settings.
- **Privacy shield:** `privacy/SensitiveScanner.kt` classifies faces, card numbers (Luhn-checked), ID numbers, phone numbers, emails, plates, names, passwords/OTPs, documents and screens, and emits `SENSITIVE` events with a MASKED preview only. The website's Privacy tab shows them blurred; the Sensors > Network tab shows counts.
- **Scenes:** `scene/SceneBuilder.kt` groups notes into scenes with time span, importance, motion and interaction. "Where did you see my mouse" answers with the scene.
- **Hey Smriti and the widget:** Settings > Hey Smriti (experimental), Floating button, and the Smriti home-screen widget (Ask, Phone, Glasses).

## Spatial layer (Space tab, navigation arrow)
Sensor-based, phone-only version of a "3D scene object graph": `spatial/PoseSensor.kt` (orientation: rotation vector, else accelerometer + compass, else gravity only), `spatial/Spatial3D.kt` (ray through the detection box, distance estimated from the object's real-world size, x/y/z in a North-up room frame), `spatial/ObjectTracker.kt` (stable ids such as cup#2 by room direction). Asking "where is my mouse" adds "about 2 m away, 40 degrees to your left" and the Live screen shows an arrow that turns with the phone. The website's Space tab shows the live heading cone, a top-down room view and the object graph (label + xyz).
- Measured: phone orientation. Estimated: distance and therefore position. Heading and the arrow need a compass/gyroscope (iQOO 15 has them; the Samsung test phone is gravity-only and says so).
- Not built: true visual odometry / re-localization (needs ARCore or a SLAM library) and a learned depth map (Depth Anything V2 on the NPU). Both would slot behind `PoseSensor` and `Spatial3D.place`.

## Network policy (the "zero cloud" claim)
INTERNET permission exists for the glasses link and the dashboard server. `net/LanGuard.kt` refuses non-private
addresses and `net/InternetBlocker.kt` sends every other `java.net` connection into a dead proxy and counts it (shown on
the Metrics page). Limit: this covers `java.net`, not raw native sockets, so also check the system's per-app data usage.

## Walk guide, security and the plan (v1.5 to v1.6)
- **Walk guide (MoveSense):** a separate feature for a person who is out and cannot find the way home. Step detector, gyroscope, compass and GPS; no camera and no map. It retraces the walked path or points straight at a saved Home, with a short voice cue (English or Hindi) and a buzz for each instruction, and draws the walk as a path with arrows. See [docs/MOVESENSE.md](docs/MOVESENSE.md).
- **Security at rest:** keys in the phone's secure hardware, encrypted pictures, an encrypted database (SQLCipher), an optional app lock. See [docs/SECURITY.md](docs/SECURITY.md).
- **Plan and status of the judges' feedback** (heat, storage, undressed scenes, encryption, navigation, the Alzheimer's use case): [docs/PLAN-judges-feedback.md](docs/PLAN-judges-feedback.md). Architecture deck: `docs/Smriti-Architecture.pptx`.

## Tests
- `gradlew testDebugUnitTest`: about 270 JVM tests (gate math, stage guard, intent parsing, LAN guard, word matching, config, storage rules, privacy maths, encryption, voice detection, and whole simulated walks).
- `gradlew connectedDebugAndroidTest`: 12 on-device tests of the REAL pipeline (privacy fails closed, a failing model does
  not stop the frame, trip and re-arm, OCR reads and scrubs numbers, pixelation, model swap, no image columns in the DB,
  duplicate-note suppression). Note: Gradle uninstalls the app afterwards, so run setup again.
- `dashboard`: `npm test` (20 tests: reducer, signal mapping, graph geometry).
- `scripts\smoke.ps1`: unit tests + install + dashboard over USB + protocol check + fault injection and recovery, on a phone.

## Build
```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
scripts\build-dashboard.ps1                  (only after changing dashboard/)
gradlew.bat testDebugUnitTest assembleDebug
```

## Status
- [x] Backend hardened and observable; dashboard built and verified against a real phone (Samsung SM-M025F, Snapdragon 450)
- [x] Full app flow (onboarding, Home, Memory, Sensors, Glasses, Settings)
- [x] Runs on a real iQOO 15 (Snapdragon 8 Elite Gen 5): EmbeddingGemma-2 on the NPU, Gemma-4 E2B on the GPU, live voice and camera, memory, the object guide, the walk guide
- [ ] Not built: proactive alerts, embedding search, Hindi UI strings, glasses MJPEG streaming, dashboard Playwright tests

## Honest limits
- "Accelerator busy" is an estimate (inference time / wall time); Android gives apps no NPU counters.
- Today's models are ML Kit (CPU/GPU, managed), not the NPU. The dashboard says so. The two "labeler" choices are the same
  model with different confidence thresholds, labelled as such.
- Voice questions are parsed in English. Glasses path is untested without ESP32 hardware.
- Check model licences before shipping (FastVLM weights may be research-only). Ideas from MemoryLens and SceneIQ (MIT);
  nothing copied from the Mira (TreeHacks) repo.

## First-time setup on a new machine

1. Install the app on the phone (`gradlew :app:installDebug`).
2. Download **EmbeddingGemma-2 NPU**, **Gemma-4 E2B** and **MobileActions-270M** in Google AI Edge Gallery on the phone, then run
   `scripts\install-gallery-model.ps1` (copies them into Smriti's private storage; they are never committed).
3. For the NPU embedding model run `scripts\fetch-qnn-libs.ps1` once and rebuild (Qualcomm libraries come from the Gallery app on the phone).
4. Versions are git tags; see [docs/VERSIONING.md](docs/VERSIONING.md) for releasing and rolling back.
