"""Builds docs/Smriti-Architecture.pptx: the architecture, flows and use cases of Smriti.
Run:  python docs/make_deck.py      (needs python-pptx)
Every diagram is made of native PowerPoint shapes, so it stays editable."""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE, MSO_CONNECTOR
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml.ns import qn
from lxml import etree
import os

# ---- palette (matches the app: deep navy, race blue, race red) -------------------------------------------------
BG = RGBColor(0x0A, 0x10, 0x1C)
PANEL = RGBColor(0x13, 0x1D, 0x30)
PANEL2 = RGBColor(0x1A, 0x27, 0x40)
LINE = RGBColor(0x2C, 0x3D, 0x5E)
INK = RGBColor(0xEE, 0xF2, 0xFA)
MUTED = RGBColor(0x9A, 0xAB, 0xC7)
BLUE = RGBColor(0x3B, 0x8E, 0xEA)
BLUE_D = RGBColor(0x1E, 0x4F, 0x9A)
RED = RGBColor(0xE5, 0x48, 0x4D)
GREEN = RGBColor(0x3F, 0xBF, 0x8A)
AMBER = RGBColor(0xF2, 0xB8, 0x4B)
FONT = "Segoe UI"

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]
W, H = 13.333, 7.5
page_no = [0]


def rgb_fill(shape, color):
    shape.fill.solid()
    shape.fill.fore_color.rgb = color


def text_in(tf, paras, anchor=MSO_ANCHOR.MIDDLE, align=PP_ALIGN.LEFT, margin=0.08):
    """paras: list of (text, size, bold, color) or a plain string."""
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(margin)
    tf.margin_top = tf.margin_bottom = Inches(0.04)
    if isinstance(paras, str):
        paras = [(paras, 12, False, INK)]
    first = True
    for t, size, bold, color in paras:
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.alignment = align
        r = p.add_run()
        r.text = t
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.name = FONT
        r.font.color.rgb = color


def box(s, x, y, w, h, paras, fill=PANEL, line=LINE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, align=PP_ALIGN.LEFT,
        anchor=MSO_ANCHOR.MIDDLE, radius=0.08, lw=1.0):
    sh = s.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        sh.adjustments[0] = radius
    rgb_fill(sh, fill)
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line
        sh.line.width = Pt(lw)
    sh.shadow.inherit = False
    text_in(sh.text_frame, paras, anchor=anchor, align=align)
    return sh


def label(s, x, y, w, h, paras, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP):
    tb = s.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    text_in(tb.text_frame, paras, anchor=anchor, align=align, margin=0.0)
    return tb


def arrow(s, x1, y1, x2, y2, color=BLUE, width=2.0, dash=False):
    c = s.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    c.line.color.rgb = color
    c.line.width = Pt(width)
    ln = c.line._get_or_add_ln()
    if dash:
        d = etree.SubElement(ln, qn("a:prstDash"))
        d.set("val", "dash")
    tail = etree.SubElement(ln, qn("a:tailEnd"))
    tail.set("type", "triangle")
    tail.set("w", "med")
    tail.set("len", "med")
    return c


def new_slide(title, sub=None, kicker=None):
    s = prs.slides.add_slide(BLANK)
    page_no[0] += 1
    bg = s.background.fill
    bg.solid()
    bg.fore_color.rgb = BG
    # tri-stripe, the app's mark
    for i, c in enumerate((BLUE, BLUE_D, RED)):
        r = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.55 + i * 0.34), Inches(0.42), Inches(0.34), Inches(0.07))
        rgb_fill(r, c)
        r.line.fill.background()
    if kicker:
        label(s, 1.7, 0.33, 6, 0.25, [(kicker.upper(), 10, True, BLUE)])
    label(s, 0.55, 0.58, 12.2, 0.6, [(title, 28, True, INK)])
    if sub:
        label(s, 0.55, 1.18, 12.2, 0.4, [(sub, 14, False, MUTED)])
    label(s, 0.55, 7.08, 8, 0.25, [("Smriti  |  private visual memory, fully on the phone  |  iQOO 15", 9, False, MUTED)])
    label(s, 12.0, 7.08, 0.8, 0.25, [(str(page_no[0]), 9, False, MUTED)], align=PP_ALIGN.RIGHT)
    return s


def on(col):
    return BG if col in (AMBER, GREEN) else INK


def chip(s, x, y, w, text, color=BLUE, h=0.3, size=10):
    return box(s, x, y, w, h, [(text, size, True, INK)], fill=color, line=None, align=PP_ALIGN.CENTER, radius=0.5)


# =====================================================================================================================
# 1. Title
s = prs.slides.add_slide(BLANK)
page_no[0] += 1
s.background.fill.solid(); s.background.fill.fore_color.rgb = BG
for i, c in enumerate((BLUE, BLUE_D, RED)):
    r = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8 + i * 0.5), Inches(1.5), Inches(0.5), Inches(0.1))
    rgb_fill(r, c); r.line.fill.background()
label(s, 0.8, 1.85, 11.5, 1.2, [("Smriti", 66, True, INK)])
label(s, 0.8, 3.05, 11.5, 1.0, [("A private visual memory that finds what you saw, and tells you when.", 24, False, INK)])
label(s, 0.8, 4.0, 11.5, 0.8, [("Ask in your own words. Get the picture and the exact time, from video and sound that never leave the phone.", 15, False, MUTED)])
chip(s, 0.8, 5.2, 2.1, "100% on-device")
chip(s, 3.05, 5.2, 2.5, "Faces blurred before storage", RED)
chip(s, 5.7, 5.2, 2.6, "Gemma-4 + EmbeddingGemma-2")
label(s, 0.8, 6.3, 11.5, 0.6, [("iQOO hackathon  |  Snapdragon 8 Elite Gen 5 (NPU + GPU)  |  Android app, version 1.6.3", 12, False, MUTED)])

# =====================================================================================================================
# 2. Problem
s = new_slide("People forget. Cameras record. Nobody can search.", "Hours of video exist, but finding one moment in it still means scrubbing through it by hand.", "The problem")
cards = [
    ("Memory loss is daily", "Where are my glasses? Did I take my pills? Who came to the door? For a person with Alzheimer's, these questions repeat many times a day and each one causes worry.", RED),
    ("Home cameras are not searchable", "A camera in the house records everything, but to find \"when did the parcel arrive\" you scrub for an hour. Cloud search sends your home to someone else's server.", AMBER),
    ("Privacy is the blocker", "Families will not stream a bedroom or a relative's face to the cloud. Anything that works here has to run on the device and keep faces out of storage.", BLUE),
]
for i, (t, b, c) in enumerate(cards):
    x = 0.55 + i * 4.15
    box(s, x, 1.9, 3.95, 3.6, [(t, 18, True, INK), (" ", 6, False, INK), (b, 13, False, MUTED)], fill=PANEL, line=c, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 0.55, 5.8, 12.25, 0.95, [("What people need is not more recording. They need to ask a question in plain words and get the moment back, with a picture and a time.", 16, True, INK)], fill=PANEL2, line=None, align=PP_ALIGN.CENTER)

# =====================================================================================================================
# 3. Solution
s = new_slide("Smriti: ask a question, get the frame and the timestamp", "Camera and microphone feed a private memory on the phone. A question brings back the moment.", "The idea")
steps = [("SEE and HEAR", "Phone camera, glasses or a home camera, plus the microphone"), ("REMEMBER", "Each few seconds becomes a meaning vector, a blurred picture and a list of objects"), ("ASK", "Voice or text, in any language: \"where did I leave my keys?\""), ("ANSWER", "A short reply, the best picture and a tappable time range")]
for i, (t, b) in enumerate(steps):
    x = 0.55 + i * 3.1
    box(s, x, 1.95, 2.75, 2.1, [(t, 15, True, BLUE), (" ", 5, False, INK), (b, 13, False, INK)], fill=PANEL, line=LINE, anchor=MSO_ANCHOR.TOP)
    if i < 3:
        arrow(s, x + 2.78, 3.0, x + 3.08, 3.0)
feats = [("Live answers", "Point the camera and ask. Gemma-4 sees the frame and replies by voice, and you can interrupt it."),
         ("Search past video", "Every camera session is saved as time windows. Ask about one session or all of them."),
         ("Look for something", "Ask first (\"where are my pills\"), then film. Smriti says \"Found at 00:18\" while you pan."),
         ("Do things", "FunctionGemma runs phone actions: flashlight, timer, calendar, map, contacts, Wi-Fi settings.")]
for i, (t, b) in enumerate(feats):
    x = 0.55 + (i % 2) * 6.2
    y = 4.3 + (i // 2) * 1.35
    box(s, x, y, 6.05, 1.2, [(t, 14, True, INK), (b, 12, False, MUTED)], fill=PANEL2, line=None, anchor=MSO_ANCHOR.MIDDLE)

# =====================================================================================================================
# 4. Use cases
s = new_slide("Two flagship use cases, one engine", "The same index answers both. Only the camera and the questions change.", "Use cases")
box(s, 0.55, 1.85, 6.05, 4.95, [("Alzheimer's and memory care", 19, True, INK), (" ", 6, False, INK),
    ("A calm memory aid on the person's own phone or glasses.", 13, False, MUTED), (" ", 6, False, INK),
    ("\"Where are my glasses?\"  The last picture of the glasses and the time.", 13, False, INK),
    ("\"Did I take my pills?\"  The moment the pill box was opened.", 13, False, INK),
    ("\"What did I do this morning?\"  A short summary with pictures.", 13, False, INK),
    ("\"Look for my keys.\"  Walk around the room, Smriti finds them.", 13, False, INK), (" ", 6, False, INK),
    ("Voice first, large type, replies in the person's language. A memory aid, not a medical device.", 12, False, AMBER)],
    fill=PANEL, line=RED, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 6.75, 1.85, 6.05, 4.95, [("Home camera feed search", 19, True, INK), (" ", 6, False, INK),
    ("A camera in your home. You want to find something. Search the feed and get the timestamp in real time.", 13, False, MUTED), (" ", 6, False, INK),
    ("\"When did the delivery arrive?\"  Timestamp and frame from the front door.", 13, False, INK),
    ("\"Did anyone enter the kitchen after 6?\"  Matching time ranges.", 13, False, INK),
    ("\"Where is the red bag?\"  The last frame that shows it.", 13, False, INK),
    ("\"Is the stove on?\"  A live look, answered now.", 13, False, INK), (" ", 6, False, INK),
    ("Video stays in the home. Only blurred stills and vectors are kept, so search is fast and private.", 12, False, AMBER)],
    fill=PANEL, line=BLUE, lw=1.5, anchor=MSO_ANCHOR.TOP)

# =====================================================================================================================
# 5. System architecture
s = new_slide("System architecture", "Every box runs on the phone. Nothing is sent to a cloud.", "Architecture")
cols = [
    ("1  SOURCES", BLUE_D, [("Phone camera", "CameraX, back camera"), ("Glasses / home camera", "JPEG snapshot over the local network"), ("Microphone", "Own capture stream, voice activity, echo guard")]),
    ("2  PRIVACY GATE", RED, [("Frame gate", "Skips unchanged frames"), ("Face redactor", "Faces blurred before any storage"), ("Sound handling", "Heard, embedded, then discarded")]),
    ("3  ON-DEVICE AI", BLUE, [("EmbeddingGemma-2", "NPU text, GPU image, audio: 768-d vectors"), ("Gemma-4 E2B", "GPU: see, listen, translate, answer, list objects"), ("FunctionGemma 270M", "Phone actions. EfficientDet + ML Kit detect"), ]),
    ("4  MEMORY (LOCAL)", GREEN, [("Moments", "Vector + blurred picture + object list + words heard"), ("Camera sessions", "4 s windows with start and end time"), ("Notes, scenes, zones", "Room database, private app folder")]),
    ("5  ENGINE and OUTPUT", AMBER, [("Intent router", "Where, when, what happened, do this"), ("Moment search", "Retrieve, verify, merge into ranges"), ("Answer", "Voice, chat, Memory tab, dashboard")]),
]
cw, gap, x0 = 2.25, 0.25, 0.55
for i, (head, col, items) in enumerate(cols):
    x = x0 + i * (cw + gap)
    box(s, x, 1.75, cw, 0.42, [(head, 11, True, on(col))], fill=col, line=None, align=PP_ALIGN.CENTER, radius=0.2)
    for j, (t, b) in enumerate(items):
        box(s, x, 2.3 + j * 1.2, cw, 1.07, [(t, 12, True, INK), (b, 10, False, MUTED)], fill=PANEL, line=col, anchor=MSO_ANCHOR.MIDDLE)
    if i < 4:
        arrow(s, x + cw + 0.01, 3.9, x + cw + gap - 0.01, 3.9, color=MUTED, width=2.0)
# ask path (question goes right to left)
box(s, 0.55, 6.3, 12.25, 0.55, [("Question path:  voice or text  >  translate to English  >  embed the question  >  search Memory  >  Gemma-4 verifies and answers  >  picture + time range", 12, True, INK)], fill=PANEL2, line=None, align=PP_ALIGN.CENTER)
label(s, 0.55, 5.95, 12.2, 0.25, [("Offline by design: an in-app network blocker keeps the app from using the internet.", 10, False, MUTED)])

# =====================================================================================================================
# 6. Ingest flow
s = new_slide("How a moment is remembered", "Runs every couple of seconds while the camera is on. A hot phone slows it down instead of stopping it.", "Flow 1: ingest")
steps = [("Camera frame", "Back camera, about 2 s apart", BLUE_D), ("Blank check", "Skip a covered or black lens", BLUE_D), ("Blur faces", "Redactor, on the frame itself", RED),
         ("Embed", "EmbeddingGemma-2 turns picture + sound into a 768-d vector", BLUE), ("Skip repeats", "Same scene (cosine > 0.99) skipped, with a heartbeat every 8 s", BLUE),
         ("Save", "Blurred 360 px picture + vector + time + place + words heard", GREEN)]
bw = 1.9
for i, (t, b, c) in enumerate(steps):
    x = 0.55 + i * (bw + 0.17)
    box(s, x, 1.9, bw, 1.95, [(t, 15, True, INK), (" ", 4, False, INK), (b, 12.5, False, MUTED)], fill=PANEL, line=c, lw=1.5, anchor=MSO_ANCHOR.TOP)
    if i < 5:
        arrow(s, x + bw + 0.01, 2.9, x + bw + 0.16, 2.9, width=1.75)
box(s, 0.55, 4.15, 6.0, 1.15, [("Object inventory (background)", 13, True, INK), ("Gemma-4 writes a list of every object in the picture, only when it is free and the phone is not hot. It is stored with the moment and searched by name.", 11, False, MUTED)], fill=PANEL2, line=BLUE, lw=1.0)
arrow(s, 3.5, 3.88, 3.5, 4.12, color=BLUE, width=1.5, dash=True)
box(s, 6.75, 4.15, 6.05, 1.15, [("Camera sessions", 13, True, INK), ("Opening the camera starts a session. Closing it, or 60 s without frames, ends it. Under 15 s is dropped. 30 min per chapter. The moments become the session's timeline.", 11, False, MUTED)], fill=PANEL2, line=GREEN, lw=1.0)
facts = [("~19 KB", "per moment (16 KB picture + 3 KB vector)"), ("6,000", "moments kept, about 115 MB at most"), ("0", "raw frames written to storage")]
for i, (n, t) in enumerate(facts):
    x = 0.55 + i * 4.15
    box(s, x, 5.6, 3.95, 1.1, [(n, 26, True, BLUE), (t, 11, False, MUTED)], fill=PANEL, line=None, align=PP_ALIGN.CENTER)

# =====================================================================================================================
# 7. Search flow
s = new_slide("How a question finds the moment", "Fast vector search first, then a careful look by Gemma-4, so the answer is both quick and right.", "Flow 2: search")
row1 = [("Question", "Voice or text, any language", BLUE_D), ("Translate", "Gemma-4 to English (answer goes back in your language)", BLUE_D), ("Embed", "EmbeddingGemma-2 turns the question into a vector", BLUE), ("Score all windows", "Cosine against every moment, plus a match on the stored object names", BLUE)]
row2 = [("Loose cut", "Keep windows above the typical score (floor 0.28)", GREEN), ("Verify", "Gemma-4 looks at the best picture of the top 3 ranges: YES or NO", GREEN), ("Merge", "Neighbouring windows become one range, e.g. 00:18 - 00:22", GREEN), ("Answer", "Gemma-4 writes the reply from the picture and notes; a chip opens the video at that time", AMBER)]
bw = 2.85
for i, (t, b, c) in enumerate(row1):
    x = 0.55 + i * (bw + 0.28)
    box(s, x, 1.85, bw, 1.55, [(t, 15, True, INK), (b, 12.5, False, MUTED)], fill=PANEL, line=c, lw=1.5, anchor=MSO_ANCHOR.TOP)
    if i < 3:
        arrow(s, x + bw + 0.01, 2.6, x + bw + 0.27, 2.6)
arrow(s, 12.0, 3.43, 12.0, 3.85, color=MUTED)
arrow(s, 12.0, 3.85, 1.9, 3.85, color=MUTED, width=1.25, dash=True)
arrow(s, 1.9, 3.85, 1.9, 4.2, color=MUTED)
for i, (t, b, c) in enumerate(row2):
    x = 0.55 + i * (bw + 0.28)
    box(s, x, 4.25, bw, 1.55, [(t, 15, True, INK), (b, 12.5, False, MUTED)], fill=PANEL, line=c, lw=1.5, anchor=MSO_ANCHOR.TOP)
    if i < 3:
        arrow(s, x + bw + 0.01, 5.0, x + bw + 0.27, 5.0)
box(s, 0.55, 6.1, 12.25, 0.75, [("Why two steps: a text-to-picture score is weak and narrow (about 0.3 to 0.55), so a small object barely beats an empty scene. The score shortlists, Gemma-4 decides, and the stored object list rescues small things.", 12, False, INK)], fill=PANEL2, line=None)

# =====================================================================================================================
# 8. Home camera feed
s = new_slide("Home camera feed: search it and get the timestamp", "The use case: a camera in your home, you want to find something, and the answer comes back with the time.", "Use case flow")
lanes = [("HOME CAMERA", BLUE_D, ["Records the room", "Serves a JPEG snapshot on the home network"]),
         ("SMRITI (phone or edge device)", BLUE, ["Pulls a frame every few seconds", "Blurs faces, embeds, saves the moment", "Writes an object list in the background"]),
         ("YOU", AMBER, ["Ask by voice or text", "\"When did the parcel arrive?\"", "Tap the time range to see the clip"])]
lx = 0.55
lw_ = 3.95
for i, (t, c, items) in enumerate(lanes):
    x = lx + i * (lw_ + 0.2)
    box(s, x, 1.85, lw_, 0.42, [(t, 12, True, on(c))], fill=c, line=None, align=PP_ALIGN.CENTER, radius=0.2)
    box(s, x, 2.35, lw_, 1.75, [(it, 14, False, INK) for it in items], fill=PANEL, line=c, anchor=MSO_ANCHOR.MIDDLE)
arrow(s, 0.55 + lw_ + 0.01, 3.2, 0.55 + lw_ + 0.19, 3.2)
arrow(s, 0.55 + 2 * (lw_ + 0.2) - 0.01, 3.2, 0.55 + 2 * (lw_ + 0.2) - 0.19, 3.2)  # you -> smriti (question), drawn right to left
# example answer card
box(s, 0.55, 4.4, 6.1, 2.4, [("Example answer", 11, True, BLUE), (" ", 4, False, INK), ("\"The parcel was left at the front door between 14:32 and 14:36.\"", 18, True, INK), (" ", 4, False, INK), ("[ best frame, faces blurred ]    14:32 - 14:36  |  Front door", 13, False, MUTED), (" ", 4, False, INK), ("Example for illustration.", 10, False, AMBER)], fill=PANEL2, line=BLUE, lw=1.25, anchor=MSO_ANCHOR.TOP)
box(s, 6.85, 4.4, 5.95, 2.4, [("What works today, and what is next", 12, True, INK), (" ", 4, False, INK),
    ("Today: the phone camera, smart glasses, and any camera on the local network that serves a JPEG snapshot address (ESP32-CAM style).", 13, False, INK),
    ("Search of past video, timestamps and clickable ranges work on the saved moments.", 13, False, INK),
    ("Next: RTSP and ONVIF cameras, several rooms in one search, and alerts when an object appears.", 13, False, AMBER)], fill=PANEL, line=LINE, anchor=MSO_ANCHOR.TOP)

# =====================================================================================================================
# 9. Alzheimer's
s = new_slide("Alzheimer's care: a memory that answers back", "Built for repeated questions, low effort and zero embarrassment.", "Use case flow")
scen = [("Lost item", "\"Where are my glasses?\"", "Last picture of the glasses, \"on the kitchen table at 9:28 AM\"."),
        ("Routine check", "\"Did I take my pills?\"", "Shows the moment the pill box was handled, with the time."),
        ("Look around", "\"Look for my keys\" (ask first, then film)", "Pan the camera. \"Found at 00:18\" appears and the phone vibrates."),
        ("Day recap", "\"What did I do this morning?\"", "A short spoken summary and a few pictures from the sessions.")]
for i, (t, q, a) in enumerate(scen):
    x = 0.55 + (i % 2) * 6.2
    y = 1.85 + (i // 2) * 1.75
    box(s, x, y, 6.05, 1.6, [(t, 12, True, BLUE), (q, 15, True, INK), (a, 12, False, MUTED)], fill=PANEL, line=LINE, anchor=MSO_ANCHOR.MIDDLE)
design = [("Voice first", "Talk, and interrupt at any time"), ("Own language", "Questions and answers in the language set in Settings"), ("Private", "Faces blurred, nothing leaves the phone"), ("Caregiver friendly", "Timeline of sessions to review with the person")]
for i, (t, b) in enumerate(design):
    x = 0.55 + i * 3.1
    box(s, x, 5.45, 2.95, 1.0, [(t, 13, True, INK), (b, 11, False, MUTED)], fill=PANEL2, line=None)
label(s, 0.55, 6.6, 12.2, 0.3, [("Smriti is a memory aid and does not diagnose or treat. Care decisions stay with the person's family and doctors.", 10, False, AMBER)])

# =====================================================================================================================
# 9b. Walk guide (MoveSense)
s = new_slide("Out and about: the walk guide (MoveSense)", "For the person who has left the house and cannot find the way back. No camera, no map, nothing leaves the phone.", "Use case flow")
stages = [("1  Sensors", BLUE_D, ["Step detector", "Gyroscope", "Compass + field strength", "GPS (L1 + L5) fix and course", "Wi-Fi networks, hashed"]),
          ("2  Where and which way", BLUE, ["Gyroscope carries the heading, the compass gives north, GPS course learns how the phone is carried", "Each step moves the position; each GPS fix pulls it back"]),
          ("3  The way", AMBER, ["Retrace the path that was walked", "or a straight line to a saved place", "or a saved walk, forward or back"]),
          ("4  Tell the person", GREEN, ["Short voice cue in English or Hindi", "A buzz for each cue: left two short, right one long, turn around three long", "The walk drawn as a path with arrows, live on the screen"])]
sw = 2.95
for i, (t, c, items) in enumerate(stages):
    x = 0.55 + i * (sw + 0.15)
    box(s, x, 1.85, sw, 0.42, [(t, 12, True, on(c))], fill=c, line=None, align=PP_ALIGN.CENTER, radius=0.2)
    box(s, x, 2.35, sw, 2.0, [(it, 12, False, INK) for it in items], fill=PANEL, line=c, anchor=MSO_ANCHOR.TOP)
    if i < 3:
        arrow(s, x + sw + 0.01, 3.35, x + sw + 0.14, 3.35, width=1.75)
box(s, 0.55, 4.55, 4.0, 2.3, [("Say it or tap it", 13, True, BLUE), (" ", 4, False, INK), ("\"I am going out\": it remembers the walk.", 12, False, INK), ("\"Take me home\" or \"I am lost\": it leads back along the walk, or straight to the saved Home.", 12, False, INK), ("\"Stop guiding\".", 12, False, INK)], fill=PANEL2, line=BLUE, anchor=MSO_ANCHOR.TOP)
box(s, 4.7, 4.55, 4.0, 2.3, [("Checked", 13, True, GREEN), (" ", 4, False, INK), ("Simulated walks with noisy GPS, a biased compass and a phone in a pocket: back within about 6 to 10 m of the start, and a straight walk home from 340 m ends inside the saved radius. Step detector, compass, gyroscope, voice, service and database checked on the iQOO 15.", 12, False, INK)], fill=PANEL2, line=GREEN, anchor=MSO_ANCHOR.TOP)
box(s, 8.85, 4.55, 3.95, 2.3, [("Honest limits", 13, True, AMBER), (" ", 4, False, INK), ("Outdoors GPS holds it to 5 to 10 m. Indoors steps and compass drift a few metres: a room or a floor, not a building. No maps, so a straight line ignores walls. An aid, not a safety device.", 12, False, INK)], fill=PANEL2, line=AMBER, anchor=MSO_ANCHOR.TOP)

# =====================================================================================================================
# 10. Look-for flow
s = new_slide("Look for something: ask first, then film", "The question comes before the video, so the camera knows what to watch for while you move.", "Flow 3: query-first")
st = [("1  Ask", "Type \"steel bottle\" in the Look card on Home"), ("2  Film", "Camera opens, a session starts, banner shows \"Looking for: steel bottle\""), ("3  Watch live", "Each saved window is compared to the target; likely ones are checked by Gemma-4"), ("4  Found", "\"Found at 00:18\" and a vibration, while you keep filming"), ("5  Stop", "Tap the red X. Smriti merges the hits into time ranges"), ("6  Answer", "Chat gets the reply, the picture and a tappable range. Tapping opens the session at that time")]
bw = 1.9
for i, (t, b) in enumerate(st):
    x = 0.55 + i * (bw + 0.17)
    col = AMBER if i == 5 else (GREEN if i == 3 else BLUE)
    box(s, x, 1.9, bw, 2.35, [(t, 16, True, col), (" ", 4, False, INK), (b, 13, False, INK)], fill=PANEL, line=col, lw=1.5, anchor=MSO_ANCHOR.TOP)
    if i < 5:
        arrow(s, x + bw + 0.01, 3.05, x + bw + 0.16, 3.05, width=1.75)
box(s, 0.55, 4.6, 6.0, 2.2, [("Tested on the iQOO 15", 13, True, INK), (" ", 4, False, INK), ("Target \"laptop\": found at 00:18, answer \"The picture shows a laptop screen with code visible, between 00:18 and 00:22.\"", 12, False, INK), (" ", 4, False, INK), ("Target \"steel bottle\" (not in view): \"I didn't see it in this recording\", with a chip to open the whole recording.", 12, False, MUTED)], fill=PANEL2, line=GREEN, anchor=MSO_ANCHOR.TOP)
box(s, 6.75, 4.6, 6.05, 2.2, [("Why query-first helps", 13, True, INK), (" ", 4, False, INK), ("The target is known before the video exists, so the search runs while you film and you get feedback in seconds.", 12, False, INK), (" ", 4, False, INK), ("The video-first path (search afterwards) still works from the Memory tab, so both orders are supported.", 12, False, MUTED)], fill=PANEL, line=LINE, anchor=MSO_ANCHOR.TOP)

# =====================================================================================================================
# 11. Privacy and storage
s = new_slide("Privacy and storage: keep the meaning, not the video", "Video would grow without limit and expose faces. A small vector and a blurred still do the job.", "Privacy by design")
box(s, 0.55, 1.85, 6.0, 2.75, [("Stored on the phone", 15, True, GREEN), (" ", 4, False, INK),
    ("A 768-number vector per moment (3 KB)", 13, False, INK), ("A blurred 360 px picture (about 16 KB)", 13, False, INK),
    ("Time, place name, the words heard, an object list", 13, False, INK), ("Sessions, look-for answers, saved places and walks", 13, False, INK)], fill=PANEL, line=GREEN, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 6.75, 1.85, 6.05, 2.75, [("Never stored", 15, True, RED), (" ", 4, False, INK),
    ("The raw camera frame", 13, False, INK), ("Any unblurred face, or a scene that looks undressed", 13, False, INK), ("The recorded sound (it is embedded, then discarded)", 13, False, INK), ("Anything on a server: there is no cloud and no account", 13, False, INK)], fill=PANEL, line=RED, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 0.55, 4.75, 12.25, 1.0, [("Encrypted at rest", 14, True, BLUE), ("The database (SQLCipher, AES-256) and every picture (AES-256-GCM) are encrypted. The keys live in the phone's secure hardware (StrongBox on the iQOO 15) and cannot be read out. Optional app lock with fingerprint, face or PIN. Backups are off.", 12, False, INK)], fill=PANEL2, line=BLUE, lw=1.25, anchor=MSO_ANCHOR.TOP)
rules = [("Dedupe", "Near-identical scenes are skipped"), ("Budget", "A storage budget you choose; old moments are thinned, then removed"), ("Control", "Forget 5 min, 1 hour, one session or everything"), ("Offline", "Network blocker inside the app")]
for i, (t, b) in enumerate(rules):
    x = 0.55 + i * 3.1
    box(s, x, 5.9, 2.95, 1.0, [(t, 13, True, INK), (b, 11, False, MUTED)], fill=PANEL2, line=None)

# =====================================================================================================================
# 12. AI stack
s = new_slide("The on-device AI stack", "Four small models share the phone's NPU, GPU and CPU. Each does one job.", "Models")
hdr = ["Model", "Job in Smriti", "Runs on"]
rows = [("EmbeddingGemma-2 740M (Qualcomm SM8850 build)", "Turns questions, pictures and sound into 768-number vectors for search", "NPU (text), GPU (picture), CPU (audio)"),
        ("Gemma-4 E2B", "Sees the live frame, hears speech, translates, writes answers, lists every object, verifies matches", "GPU, CPU fallback"),
        ("FunctionGemma 270M (Mobile Actions)", "Turns \"turn on the flashlight\" or \"add a meeting\" into a phone action", "CPU"),
        ("EfficientDet + ML Kit", "Quick object boxes, text and scene labels for the live overlay and the dashboard", "CPU / GPU")]
colx = [0.55, 5.0, 10.2]
colw = [4.35, 5.1, 2.6]
for j, h in enumerate(hdr):
    box(s, colx[j], 1.85, colw[j], 0.4, [(h, 11, True, INK)], fill=BLUE_D, line=None, radius=0.15)
for i, r in enumerate(rows):
    for j, c in enumerate(r):
        box(s, colx[j], 2.35 + i * 0.95, colw[j], 0.85, [(c, 12 if j == 0 else 11, j == 0, INK if j != 2 else MUTED)], fill=PANEL, line=LINE)
box(s, 0.55, 6.2, 12.25, 0.65, [("Measured on the iQOO 15: a full object list of one picture takes 8 to 12 s on a hot phone, so it runs in the background, one at a time, and waits while you are talking to Smriti.", 12, False, INK)], fill=PANEL2, line=None)

# =====================================================================================================================
# 13. Software architecture
s = new_slide("Software architecture", "One engine owns the models and the memory. Screens only observe its state.", "Modules")
layers = [("UI  (Jetpack Compose)", "Home, Live, Memory, Sensors, Settings, Walk guide with its path map, floating Smriti eye, app lock", BLUE_D),
          ("Live and voice", "LiveSession, VoiceCapture (adaptive VAD, echo guard, barge-in), look-for LookAnalysis, TargetWatcher", BLUE),
          ("Engine  (SmritiEngine)", "Router, scheduler, thermal governor and power plan, privacy redactor and sensitive-scene filter, moment capture, answerer", AMBER),
          ("AI services", "GemmaEmbedder, Brain (Gemma-4), Actions (FunctionGemma), VisualMemory, MomentSearch, SessionRecorder, Inventory, StorageManager", GREEN),
          ("Walk guide  (move/)", "Step detector, gyroscope, compass, GPS -> position and heading -> retrace or bearing -> voice and buzz; own service, tables and tests", BLUE),
          ("Storage and security", "Room 10 under SQLCipher, encrypted pictures, keys in the secure hardware (KeyVault), DataStore settings", RED),
          ("Sources and dashboard", "PhoneCameraSource (CameraX), Esp32Source (LAN snapshot), NanoHTTPD server + React dashboard", BLUE_D)]
for i, (t, b, c) in enumerate(layers):
    y = 1.85 + i * 0.7
    box(s, 0.55, y, 3.3, 0.6, [(t, 13, True, on(c))], fill=c, line=None)
    box(s, 3.95, y, 8.85, 0.6, [(b, 11, False, INK)], fill=PANEL, line=LINE)
label(s, 0.55, 6.75, 12, 0.25, [("Kotlin, LiteRT-LM 0.18, Room 10 + SQLCipher, CameraX, ML Kit, MediaPipe. About 270 unit tests: ranges, sessions, parsing, storage rules, privacy maths, encryption, voice detection, and whole simulated walks.", 10, False, MUTED)])

# =====================================================================================================================
# 13b. Full architecture
s = new_slide("Full architecture: every feature on one page", "Everything runs on the phone. Sensors and cameras go in, memory, answers, guidance and alerts come out.", "Architecture")
cols = [
    ("INPUTS", BLUE_D, ["Phone camera (CameraX, 10 to 24 fps)", "Smart glasses (ESP32 snapshot over LAN)", "Microphone (adaptive voice detector)", "Step detector, gyroscope, compass", "GPS (L1+L5), Wi-Fi networks (hashed)"]),
    ("ON-DEVICE ENGINES", BLUE, ["Gate, face blur, sensitive-scene filter", "EmbeddingGemma-2 on the NPU: meaning vectors", "Gemma-4 E2B on the GPU: see, hear, answer", "FunctionGemma: phone actions", "Walk guide: position, heading, guidance"]),
    ("MEMORY AND SECURITY", GREEN, ["Room 10 under SQLCipher (AES-256)", "Pictures AES-256-GCM, blurred 360 px", "Keys in StrongBox, optional app lock", "Storage budget with thinning", "No cloud, no account, no backup"]),
    ("OUTPUTS", AMBER, ["Voice answers in the user's language", "Camera guide ring for objects", "Walk guide: voice, buzz, path map", "Time ranges that open the recording", "Thermal-aware frame rate"]),
]
cw = 2.95
for i, (t, c, items) in enumerate(cols):
    x = 0.55 + i * (cw + 0.15)
    box(s, x, 1.85, cw, 0.42, [(t, 12, True, on(c))], fill=c, line=None, align=PP_ALIGN.CENTER, radius=0.2)
    box(s, x, 2.35, cw, 3.4, [(it, 12, False, INK) for it in items], fill=PANEL, line=c, anchor=MSO_ANCHOR.TOP)
    if i < 3:
        arrow(s, x + cw + 0.01, 4.0, x + cw + 0.14, 4.0, width=1.75)
box(s, 0.55, 5.95, 12.25, 0.85, [("Foreground services keep the voice (microphone) and the walk guide (location) alive with the screen off. A network blocker refuses every connection except the user's own glasses.", 12, False, INK)], fill=PANEL2, line=None)

# =====================================================================================================================
# 13c. Tech stack
s = new_slide("Tech stack", "Open, on-device components; nothing that needs a server.", "Technology")
rows = [("App", "Kotlin, Jetpack Compose, Material 3, Navigation, Coroutines and Flow"),
        ("AI runtime", "LiteRT-LM 0.18 (Gemma-4 E2B GPU, EmbeddingGemma-2 NPU via Qualcomm runtime, FunctionGemma), ML Kit, MediaPipe"),
        ("Camera and audio", "CameraX 1.4 with Camera2 frame-rate control, AudioRecord with an adaptive voice detector, Android TextToSpeech"),
        ("Sensors", "Step detector, game rotation vector (gyroscope), rotation vector (compass), magnetic field, GPS, Wi-Fi scan"),
        ("Storage", "Room 10 + SQLCipher 4.13, DataStore, AES-256-GCM files"),
        ("Security", "Android Keystore (StrongBox), platform BiometricPrompt, FLAG_SECURE, backups off"),
        ("Tooling", "Gradle, KSP, JUnit (272 tests), python-pptx, adb debug commands"),
        ("Target", "iQOO 15, Snapdragon 8 Elite Gen 5, Android 16 (minSdk 31)")]
for i, (k, v) in enumerate(rows):
    y = 1.85 + i * 0.62
    box(s, 0.55, y, 2.4, 0.54, [(k, 13, True, INK)], fill=BLUE_D, line=None)
    box(s, 3.05, y, 9.75, 0.54, [(v, 12, False, INK)], fill=PANEL, line=LINE)

# =====================================================================================================================
# 13d. What is novel
s = new_slide("What is novel", "Not one trick: a private memory that also guides, on one phone.", "Novelty")
nov = [("Search by meaning, offline", "A question is matched to remembered pictures by meaning on the NPU, then checked by a language model; a stored object list rescues small things."),
       ("Query first, then film", "Say what to find, film, and Smriti watches live and says \"found at 00:18\" while you move."),
       ("Memory that guides", "The same phone leads you to an object with the camera, and a person home with no camera at all."),
       ("Sensor fusion without maps", "Gyroscope carries direction, compass gives north, GPS course learns how the phone is carried; steps and GPS give position."),
       ("Privacy as a feature", "Faces blurred, undressed scenes dropped, no video kept, keys in secure hardware, no cloud."),
       ("Heat-aware by design", "Frame rate, analysis tick and capture spacing follow the phone's thermal headroom.")]
for i, (t, b) in enumerate(nov):
    x = 0.55 + (i % 2) * 6.2
    y = 1.85 + (i // 2) * 1.6
    box(s, x, y, 6.05, 1.45, [(t, 14, True, BLUE), (b, 12, False, INK)], fill=PANEL, line=LINE, anchor=MSO_ANCHOR.MIDDLE)

# =====================================================================================================================
# 13e. Cost saving and ROI
s = new_slide("Cost saving and ROI", "Figures marked (measured) come from the phone; the rest are stated assumptions. Replace them with your own prices.", "Business case")
box(s, 0.55, 1.85, 6.0, 2.55, [("Running cost", 15, True, GREEN), (" ", 4, False, INK),
    ("Servers, accounts and per-query AI fees: none. The marginal cost of a question is the phone's battery.", 12, False, INK),
    ("Storage (measured): about 19 KB per remembered moment, a chosen budget (default 300 MB), no video.", 12, False, INK),
    ("Home camera, assumption 2 Mbps day and night: about 650 GB of video a month to store in a cloud; Smriti keeps at most its budget, roughly 2,000 times less.", 12, False, INK)], fill=PANEL, line=GREEN, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 6.75, 1.85, 6.05, 2.55, [("Where the value is", 15, True, AMBER), (" ", 4, False, INK),
    ("Caregiver time: fewer repeated questions answered by hand and fewer searches for lost items.", 12, False, INK),
    ("Safety: a way home for a person who is out, with no extra device.", 12, False, INK),
    ("Privacy cost avoided: no footage in anyone's cloud, so no breach or consent burden to manage.", 12, False, INK),
    ("No new hardware: it runs on a phone the family already has.", 12, False, INK)], fill=PANEL, line=AMBER, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 0.55, 4.6, 12.25, 1.2, [("Simple payback formula", 13, True, INK),
    ("Monthly saving = (cloud storage + per-query AI fees + subscription avoided) + (caregiver minutes saved per day x 30 x value of a minute). Smriti's recurring cost is zero, so payback is the one-time cost of building and deploying it.", 12, False, INK)], fill=PANEL2, line=None, anchor=MSO_ANCHOR.TOP)
label(s, 0.55, 6.0, 12.2, 0.7, [("Not measured yet: caregiver time saved and the number of times the walk guide gets a person home. These need a pilot with real users; no such result is claimed here. Smriti is an aid, not a medical or safety device.", 11, False, AMBER)])

# =====================================================================================================================
# 14. Status and roadmap
s = new_slide("What is built and what comes next", "Version 1.6.3, running on a real iQOO 15.", "Status")
box(s, 0.55, 1.85, 6.0, 4.9, [("Built and tested", 16, True, GREEN), (" ", 4, False, INK),
    ("Live camera Q&A by voice, with interrupt; voice follows the room noise", 12, False, INK), ("Continuous private memory: picture, sound, object list", 12, False, INK),
    ("Camera sessions, look for something, and a ring that guides you to an object", 12, False, INK), ("Clickable time ranges that open the video at that moment", 12, False, INK),
    ("Cooler and clearer: adaptive frame rate, full answers on screen and aloud", 12, False, INK), ("Storage budget with thinning; sensitive scenes are dropped", 12, False, INK),
    ("Encrypted pictures and database, keys in the secure hardware, optional app lock", 12, False, INK), ("Walk guide (MoveSense): steps, gyroscope, compass and GPS lead a person home, with voice, buzz and a path map", 12, False, INK),
    ("Replies in the chosen language, light and dark themes, LAN dashboard", 12, False, INK)], fill=PANEL, line=GREEN, lw=1.5, anchor=MSO_ANCHOR.TOP)
box(s, 6.75, 1.85, 6.05, 4.9, [("Next", 16, True, AMBER), (" ", 4, False, INK),
    ("Walk guide: a real walk outdoors with GPS (only simulated so far), Bluetooth beacons at the door, a caregiver alert when the person leaves a zone", 12, False, INK),
    ("Object arrow that also counts the steps you walk", 12, False, INK),
    ("Search across all sessions in one question, with time words like \"this morning\"", 12, False, INK), ("Home camera: RTSP and ONVIF, several rooms, alerts", 12, False, INK),
    ("Auto zoom: digital crop, then the telephoto lens, then look again", 12, False, INK), ("Sensitive-scene filter checked on real examples by the team", 12, False, INK),
    ("Hindi and other UI languages, caregiver companion screen", 12, False, INK)], fill=PANEL, line=AMBER, lw=1.5, anchor=MSO_ANCHOR.TOP)

# =====================================================================================================================
# 15. Closing
s = prs.slides.add_slide(BLANK)
page_no[0] += 1
s.background.fill.solid(); s.background.fill.fore_color.rgb = BG
for i, c in enumerate((BLUE, BLUE_D, RED)):
    r = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8 + i * 0.5), Inches(1.4), Inches(0.5), Inches(0.1))
    rgb_fill(r, c); r.line.fill.background()
label(s, 0.8, 1.7, 11.8, 1.6, [("Search what you saw, the way you remember it.", 40, True, INK)])
label(s, 0.8, 3.4, 11.5, 1.0, [("Private because it never leaves the phone. Useful because the answer comes with a picture and a time.", 18, False, MUTED)])
pts = [("Alzheimer's care", "Fewer worried questions, more independence"), ("Home camera search", "Find the moment in seconds, not hours"), ("On-device AI", "NPU + GPU, no cloud, no account")]
for i, (t, b) in enumerate(pts):
    box(s, 0.8 + i * 4.0, 4.7, 3.8, 1.3, [(t, 16, True, INK), (b, 12, False, MUTED)], fill=PANEL, line=LINE)
label(s, 0.8, 6.6, 11.5, 0.4, [("Smriti  |  iQOO hackathon  |  Thank you", 12, False, MUTED)])

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Smriti-Architecture.pptx")
prs.save(out)
print("saved", out, "slides:", len(prs.slides))
