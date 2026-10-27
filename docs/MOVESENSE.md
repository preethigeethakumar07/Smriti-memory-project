# MoveSense: the Walk guide

A separate feature of Smriti for a person who is out and about and may not find the way home (the Alzheimer's use case). It needs
**no camera and no map**. The phone is held in the hand (or upright in front of the person; in a pocket after a few steps outdoors) and
tells them, in short spoken words and a buzz for each, "go straight", "turn left", "you have arrived".

It lives in its own card on Home, its own screen (`MoveScreen`), its own foreground service (`MoveService`) and its own code
(`app/src/main/java/com/smriti/app/move/`). Nothing in the camera, memory, Live voice or storage features was changed to make it work.

## What it does

| Mode | When | How it finds the way |
|---|---|---|
| **Retrace** | "I am going out" was said (or the button tapped) and then "take me home" | follows the path that was walked, in reverse, back to where it began |
| **Straight line** | a Home (or another place) was saved and the person is lost | points at the saved place from wherever they stand, and says how far is left |
| **Saved walk** | a recorded walk is replayed, forward or back | follows the stored path (works with no GPS, for a room or a corridor) |

Voice (English, and Hindi in Devanagari or typed in English letters): "take me home", "I am lost", "I am going out", "stop guiding",
"take me to the chemist" (a saved place). Every walk that is recorded is kept and listed under Saved walks.

## The path map

The Walk guide screen draws the walk while it happens, like a GPS track, so the person (or a caregiver looking over their shoulder) can
see where they have been and which way to go:

* **blue**: where you walked (the recorded walk); **green**: where you have walked since the guide began leading you back; **amber, dashed**:
  the way you are being led (the walk in reverse, a saved walk, or a straight line to a saved place);
* an **arrow every so often** on each line shows which way it runs; a **green dot** is where the walk began, a **red dot** where the guide
  is leading to (with its name), and a **blue dot with a pointer** is where you are now and which way you face, inside a faint circle of how
  far off the position may be;
* a **grid of round distances**, a **scale bar** and a **north arrow**; "Heading up" turns the map so that straight ahead is up;
* **pinch** to zoom, **drag** to move, **double-tap** (or Fit) to see the whole walk again; the map keeps the whole walk in view while you walk;
* every saved walk can be shown the same way ("Show path"), and the last walk stays on the map after it ends.

There are no map tiles: Smriti never goes online, so this is a plan of the walk (metres from where it began), not a street map.

**Demo walk.** "Try it without walking" (at the bottom of the Walk guide screen) feeds the real walk guide a simulated person: it remembers a
walk 30 m north and 25 m east, is asked to take the person home, and leads itself back with its voice, its buzz and the path map drawn live.
The phone does not move, the real sensors are paused while it runs, the screen says it is a demo, it can be stopped at any time, and nothing
is saved. It is for showing how the walk guide works (a pitch, training a caregiver, trying it indoors); it is not a measurement of anything.

## How it works

```
 step detector ----------------------------+
 gyroscope  (game rotation vector) --+     |
 compass    (rotation vector)  ------+--> HeadingFilter --> heading --+--> PdrTracker (where am I?) --+--> Guidance / StraightGuide
 magnetic field strength  -----------+     ^                         |        ^                      |        |
 GPS fix (position, accuracy, course) ----+-+-- GPS course teaches    |        | GPS fix pulls the    |        v
                                              how the phone is carried        | position back        |   CuePolicy -> voice + buzz
 Wi-Fi networks (hashed) ---------------------------------------------------- confirms "at home"     |   CompassBuzz -> steady pulses
```

* **Position (PdrTracker).** Every step moves the position one stride along the walking direction. The uncertainty grows 5 % per
  metre walked; every GPS fix with an accuracy of 30 m or better pulls the position back by an amount that depends on how far each
  is trusted. Fixes far apart on a straight walk also teach the length of the stride.
* **Direction (HeadingFilter).** The gyroscope carries the heading from moment to moment (smooth, not fooled by magnets, but no north
  and slow drift). The compass gives it north, slowly, and is held off while it looks disturbed: field strength outside 20 to 70 microtesla
  or jumping by 25 %, or the compass moving against the gyroscope (steel, speakers, lifts). While walking outdoors the GPS course
  (the direction the phone really travels) also teaches how the phone is carried: nothing in the hand, about a quarter turn in a pocket;
  that is forgotten when the phone is picked up.
* **Guidance.** The way to turn is towards a point 8 m ahead on the path, not just its end, with hysteresis so the cue does not flicker
  (straight within 15 to 25 degrees, bear 50 to 60, turn 135 to 150, otherwise turn around). More than 20 m from the path is "off the
  path, turn left to get back". A corner is announced at 20 m and at 5 m. Arrival is the path end, the saved radius, or the Wi-Fi
  networks of home being heard again.
* **Cues.** At most one spoken cue every 3 s; a cue is repeated every 14 s while the person still has to turn; "keep going" only every
  45 s; the distance left at 1000, 500, 300, 200, 100, 50 and 20 m. Buzz patterns: left = two short, right = one long, turn around =
  three long, arrived = a rising pattern, keep going = a light tick. A steady compass buzz (optional) gets firmer the closer the phone
  points the right way.

## Privacy

Positions, Wi-Fi fingerprints and walks stay in the phone's own database; nothing is sent anywhere (the app has no internet path
except to the user's own glasses on the local network). The Wi-Fi networks heard at a place are stored only as a hash (5 bytes of
SHA-256 of the address), never as the address. "Delete all places and walks" in the screen erases everything.

## Honest limits

* **Outdoors, with GPS:** the position is kept within the GPS accuracy (5 to 10 m typical on this phone), so retracing is good.
* **Indoors, no GPS:** steps and the compass drift. About 3 to 6 % of the distance walked plus whatever the compass is bent by (10 to
  25 degrees near steel). Good for a room, a corridor, a floor. Not a whole building.
* **No maps:** a straight line to a place does not know about walls or roads. The retrace follows what was actually walked.
* **A phone in a pocket indoors** has no GPS to learn from, so the direction is unreliable there.
* It does not see obstacles or traffic. It is an aid, not a safety device; a caregiver should still know the person is out.
* Android only lets the walk start while the app is on screen (a location service cannot be started from the background); once
  started it keeps going with the screen off.

## What was measured

Simulated walks (the same code, run on a computer by the unit tests and on the iQOO 15 by `moveSim`; GPS noise 4 m, compass noise 2 degrees,
a gyroscope that drifts a degree a minute, a person who does what the guide says):

| Walk | Result |
|---|---|
| Out 120 m north, 80 m east, 40 m north, 30 m back, then "take me home" (GPS on) | arrived after about 370 strides, 7 m from the start |
| The same with the phone in a pocket (pointing a quarter turn off) | arrived, 3.5 m from the start: the GPS course taught the carry |
| Indoors, no GPS, compass 4 degrees off, 51 m out and back | arrived, 4.5 m from the start (arrival radius 5 m when there is no GPS) |
| Lost 340 m from a saved Home, facing away | told to turn around first, distance said at 300, 200, 100, 50, 20 m, arrived inside the saved radius |

On the phone itself (iQOO 15, Android 16): the compass, the gyroscope-based rotation and the magnetometer register and report a heading;
the step detector counts real steps once "physical activity" is allowed; 8 Wi-Fi networks are heard; the foreground service starts as type
"location" with its notification; "I am going out", "I am lost" and "stop guiding" said through Live voice reach the walk guide and get the
right answers; the database moves from version 9 to 10 with every existing row kept (before: 443 moments, 171 notes, 45 chat lines, 51 sessions;
after: the same, plus the one note and one session the running app added meanwhile).
Not measured by me: GPS outdoors, and a real walk. Both need a person with the phone outside.

## Testing

* `app/src/test/.../MoveSenseTest.kt`, `MoveControllerTest.kt`, `MoveTextTest.kt`: the maths, the cue policy, the heading filter, and
  whole simulated walks (out and back outdoors with GPS noise, indoors with no GPS, a phone in a pocket, a straight walk home from
  340 m, Hindi, buzz and voice switches, Wi-Fi arrival).
* Debug commands (debug builds only): `moveSim scenario=all|retrace|indoor|bearing|pocket` runs the simulated walks on the phone itself;
  `moveState` shows the live state and the sensors; `moveProbe seconds=6` runs the real sensors for a few seconds; `moveVoice text=... buzz=left|right|uturn|arrive`
  plays a cue so it can be heard and felt.
