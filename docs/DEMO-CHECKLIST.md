# Demo test checklist

Run this the day before and again 30 minutes before. Tick each line; if one fails, note what you saw.

## Phone ready (30 min before)
- [ ] Battery 70% or more, **charger unplugged**, case off, brightness about 60%.
- [ ] Screen mirroring off. Close other apps. Let the phone rest until it is cool to touch (no "Phone warm" pill on Home).
- [ ] Airplane mode on is fine. The app must still work (no cloud).
- [ ] Open the app once and wait 10 seconds so the models load. Do not start the camera yet.

## Phone camera, Live
- [ ] Home, tap **Phone camera**. Say "what do you see". It answers in a sentence.
- [ ] Say "where is my bottle" with a bottle in view. A ring appears on it and the hint says which way to turn.
- [ ] Say "stop looking". The ring goes away.
- [ ] Close Live. A new session shows in Memory with pictures.

## Memory
- [ ] Memory, open the last recording. The big picture fills the width.
- [ ] Tap a picture in "All windows". It opens full screen. Pinch zooms, double-tap resets, arrows step through.
- [ ] Type "Ask about this recording: bottle". It lists time ranges and jumps there.

## Where did I see it (camera on)
- [ ] Record a bottle for 10 seconds, put it away, open Live and ask "where did I see my bottle". The memory answer comes first, then "Move the phone slowly around and I will show you if I see it now."

## AI Glasses (only if the unit is available)
- [ ] AI Glasses tab: the glasses connect and Home shows "Connected".
- [ ] Home, tap **AI Glasses**. The preview shows the glasses picture. Ask "what do you see".
- [ ] Say "where is my keys". The ring follows the picture; expect about 2 pictures a second, so move slowly.
- [ ] The phone mic hears you; keep the phone with you.

## Way Home
- [ ] Home, tap **Way Home**. Press **Try it without walking**: the map draws a path with arrows and the voice leads back. Stop it at the end.
- [ ] Outdoors only: "I am going out", walk 50 m, say "take me home". Not yet measured outdoors, so try it before the demo.

## Dashboard (laptop)
- [ ] USB cable in, then `powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1 -SkipInstall` from the Smriti folder.
- [ ] `http://localhost:8765/#flow` shows "Live" (not "Simulated data").
- [ ] `#walk`: press **Demo walk**. The path, arrows and guidance update.

## During the demo
- [ ] Keep camera sessions to 30 to 60 seconds. Rest the phone between runs.
- [ ] If "Phone is hot" appears: stop, wait, carry on with the dashboard or Memory.

## Known not yet checked
- Glasses end to end, the walk guide outdoors, the app lock prompt, the dashboard Walk tab in a browser.
