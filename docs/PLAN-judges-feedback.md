# Plan: judges' feedback (round 1)

Status: plan written before any change. Facts below were measured on the iQOO 15 (Android 16) with v1.4.0.
Every phase is additive, has its own tag, and can be rolled back with `git checkout <tag>`.

## Where each item stands

| # | Item | Status | Tag |
|---|---|---|---|
| 1 | Heat, slow camera, long answers | done: adaptive frame rate, quiet Live screen, full answers on screen and aloud | v1.5.0 |
| 2 | Storage overload | done: budget, meter, thinning, Free up space | v1.5.1 |
| 4 | Undressed scenes dropped | done: layered filter (recall not validated by me, see below) | v1.5.2 |
| 5 | Encrypt and protect | done: hardware-backed key vault, encrypted pictures (v1.5.3), encrypted database (SQLCipher) with a checked conversion, optional app lock, backups spelled out as off, telemetry queue cleared; see `docs/SECURITY.md` | v1.5.3, v1.6.1 |
| 5b | Alzheimer's outdoors, no camera | done: Walk guide (MoveSense), see `docs/MOVESENSE.md` | v1.6.0 |
| 3 | Navigate to an object or a place | places: done through the Walk guide (saved places, straight bearing, retrace, and the walk drawn as a path with arrows); the object arrow does not yet use steps walked | v1.6.0, v1.6.2 |

## What was measured

| Fact | Value |
|---|---|
| Camera service CPU while the camera is bound | 90 to 130 % of one core, even on Home (the app itself is only 21 to 29 % on Home) |
| App CPU in Live camera | 69 to 82 % of a core; main thread 37 %, detector thread pool 18 %, RenderThread 11 % |
| Display modes | up to 144 Hz (default mode is 144 Hz) |
| Camera frame rates offered | 10, 15, 24, 30 fps (fixed or ranges) |
| Sensors present | accelerometer, gyroscope, magnetometer, rotation vector, game rotation vector, gravity, linear acceleration, step detector, step counter, significant motion, stationary detect, proximity, light |
| Sensors missing | barometer (no floor detection), Wi-Fi RTT |
| Location | GPS with L1 + L5 dual frequency, geofencing capability |
| Radios | Bluetooth LE with Channel Sounding, Wi-Fi (scan only) |
| Security hardware | hardware keystore with StrongBox, fingerprint, face unlock |
| Stored per remembered moment | about 19 KB (11 to 16 KB picture + 3 KB vector); cap 6,000 moments; no video is ever stored |
| Data at rest today | plain SQLite and plain JPEG in the app's private folder; backups already off; dashboard is localhost only unless LAN + PIN is switched on |
| Local telemetry found | ML Kit's `com.google.android.datatransport.events` queue (2.4 MB) is written inside the app folder |

## Items

### 1. Heat, slow camera, long answers cut off
Feasible: high. Risk: low to medium (camera settings are device specific, so each change is measured).
- Camera: lower the frame rate (24 fps in Live, 10 fps on Home) with Camera2 interop, YUV analysis frames instead of RGBA, a 4:3 preview capped at 1280 x 960.
- Detector: tick 250 ms becomes 500 ms in Live and 1000 ms on Home, doubled when the phone is warm.
- UI: the microphone level and compass pose no longer redraw the whole Live screen; the Live screen asks for 60 Hz.
- Thermal: use the OS thermal headroom forecast so the app slows down before the phone is hot, and say so on screen.
- Answers: the Live caption shows the whole answer (scrollable) until the next question; the voice reads a full sentence or the whole list (today only the first line, so "On the left:" was spoken alone).
- Acceptance: re-measure the same numbers; target Live app CPU under 45 %, camera service under 60 %.

### 2. Storage overload
Feasible: high. Risk: low.
- Video is never stored, so the growth is stills and vectors. What is missing is a byte budget.
- Add a budget (default 500 MB, choices 200 MB / 500 MB / 1 GB), a meter in Memory, and thinning when over 80 %: smaller pictures for older moments, then fewer moments per old session, then oldest first. Session covers and look-for results are protected.
- Slower saving when the scene does not change (home camera pointed at a still room).
- Pure rules, unit tested.

### 3. Navigate to an object or a place
Feasible: medium. Indoor position from steps and compass drifts; it is good for one room or one floor (a few metres), not for a whole building. Outdoors GPS (L1 + L5) gives a few metres.
- Today: an arrow that points to where an object was seen (compass only, it ignores that you walked).
- Plan: one shared movement tracker (item 6) so the arrow also accounts for steps walked, plus the live camera ring (v1.4.0) for the last metres.
- Cross-session memory of object positions is limited to room level unless the same start point is used.

### 4. Sensitive scenes (undressed people)
Feasible: medium. Accuracy cannot be proven by me: I will not collect explicit images, so recall is validated by your team, not by me.
- Layered and conservative: a cheap check on every remembered frame (EmbeddingGemma similarity to a set of "undressed / nude" descriptions, plus person and skin-area signal), then Gemma-4 confirms. If it says yes, or refuses to answer, the frame is dropped.
- Dropped means: not embedded into memory, no picture, no caption, no object list, no sound, not sent to Gemma, not used by the guide. A short "paused" notice is shown and capture resumes after a few seconds.
- Default on; false alarms (swimwear, bare arms) are accepted on purpose.

### 5. Encrypt and protect sensitive data
Feasible: high. Risk: medium (data migration). The old data is backed up and checked before the plain copy is removed.
- AES-256-GCM key in the hardware keystore (StrongBox when present).
- Database: SQLCipher (open source) via Room; existing data is exported into the encrypted file and the row counts are compared.
- Pictures: each thumbnail encrypted; chat images decrypted only in memory.
- Optional app lock with fingerprint or face for Memory and Chat; screenshots blocked on those screens.
- Telemetry queue purged; logs carry no personal text; dashboard stays localhost only unless LAN + PIN.

### 5b. Alzheimer's outdoors, no camera: MoveSense Guide
Feasible: medium to high outdoors, medium indoors.
- Step detector + gyroscope + compass give direction and distance walked (error about 3 to 8 % of distance, worse near steel or electronics); GPS corrects outdoors; Wi-Fi networks seen identify rooms; the vibration motor and short voice phrases give the feedback.
- Features: save places (Home, Temple), record a route, "Take me home" along the way the person came, "Go straight / turn left / you reached it", a buzz-when-facing-home compass for people who cannot read the screen.
- Limits: no maps while offline, so it follows the walked path and a straight bearing, not streets; pocket carry is best effort (hand carry is the reliable mode).
- Not built now: sending location to a caregiver (needs SMS permission and consent design).

## Safety rails for the current features
1. New code lives in new files; existing screens get small, reviewed edits.
2. New settings default to the safe choice and can be switched off.
3. Database changes use explicit migrations; the data is counted before and after on the phone.
4. All unit tests run before each commit; the on-device smoke checks are: AI status, a live question, the guide, a camera session, voice status.
5. One tag per phase: v1.5.0 cool and clear, v1.5.1 storage, v1.5.2 sensitive scenes, v1.5.3 encryption, v1.6.0 MoveSense and navigation.

## Decisions taken by default (tell me to change any)
- No model download: sensitive-scene checks use models already on the phone.
- SQLCipher is added as a dependency (open source).
- Location, activity recognition and Bluetooth permissions are asked only when MoveSense is opened.
