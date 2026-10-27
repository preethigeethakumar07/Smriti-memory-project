import type { AiUnit, Hello, ModelSpec, PipelineEvent, ServerMessage, SessionRow, Status, SystemSample } from "./protocol";

/**
 * SIMULATED data source. Used only when no phone answers, and the UI says so everywhere.
 * It speaks the same protocol as the phone, so every page works identically against it.
 */

const CAPTIONS = [
  "I see a laptop and a cup.", "I see a book and a bottle.", "I see a keyboard and a mobile phone.",
  "Nothing distinctive in view.", "I see a chair and a table. Visible text: \"EXIT\".", "I see a backpack and a bottle. 1 person is nearby.",
];
const ZONES = ["desk", "kitchen", "somewhere", "desk"];
const OBJECT_SETS = [["laptop", "cup"], ["book", "bottle", "chair"], ["keyboard", "mobile phone"], ["backpack", "bottle"], ["table", "chair", "cup"]];

const MODELS: ModelSpec[] = [
  { id: "mlkit-objects", role: "DETECTOR", name: "ML Kit object detector", backend: "ML Kit (CPU/GPU, managed)", note: "boxes + 5 coarse categories", available: true, selected: true },
  { id: "mlkit-labeler-balanced", role: "LABELER", name: "ML Kit image labeler (balanced)", backend: "ML Kit (CPU/GPU, managed)", note: "400+ labels, confidence 0.6", available: true, selected: true },
  { id: "mlkit-labeler-strict", role: "LABELER", name: "ML Kit image labeler (strict)", backend: "ML Kit (CPU/GPU, managed)", note: "same model, confidence 0.8: fewer, safer labels", available: true, selected: false },
  { id: "mlkit-ocr", role: "OCR", name: "ML Kit text recognizer (Latin)", backend: "ML Kit (CPU/GPU, managed)", note: "reads signs and screens", available: true, selected: true },
  { id: "npu-vlm", role: "CAPTIONER", name: "NPU vision-language captioner", backend: "Hexagon NPU", note: "pending the iQOO 15 benchmark", available: false, selected: false },
];

const jitter = (base: number, spread = 0.25) => Math.max(1, Math.round(base * (1 + (Math.random() - 0.5) * 2 * spread)));

export class Simulator {
  private timers: ReturnType<typeof setTimeout>[] = [];
  private intervals: ReturnType<typeof setInterval>[] = [];
  private frameId = 0;
  private notes = 0;
  private seen = 0; private gated = 0; private processed = 0; private dropped = 0;
  private cfg = { preset: "balanced", detector: "mlkit-objects", labeler: "mlkit-labeler-balanced", ocr: "mlkit-ocr", detectOn: true, labelOn: true, ocrOn: true, preview: false, intervalSec: 4, memoryOn: true };
  private models: ModelSpec[] = MODELS.map((m) => ({ ...m }));
  private fault = new Set<string>();
  private fails: Record<string, number> = {};
  private tripped = new Set<string>();
  private startedAt = Date.now();
  private busy = false;

  constructor(private emit: (m: ServerMessage) => void) {}

  start() {
    this.emit(this.hello());
    this.every(1000, () => this.systemSample());
    this.every(1000, () => this.gateSummary());
    this.every(3600, () => this.frame("phone", false));
    this.after(500, () => this.frame("phone", false));
  }

  stop() {
    this.timers.forEach(clearTimeout);
    this.intervals.forEach(clearInterval);
    this.timers = []; this.intervals = [];
  }

  // ---- commands -------------------------------------------------------------------------------------------------------

  command(id: string, name: string, args: Record<string, string>) {
    const done = (ok: boolean, message: string) => {
      this.emit({ type: "cmdResult", id, name, ok, message });
      if (ok) this.emit(this.hello());
    };
    this.log("info", "admin", `command ${name} ${JSON.stringify(args)}`);
    this.after(120, () => {
      switch (name) {
        case "setPreset": {
          const p = args.preset;
          if (!["fast", "balanced", "rich"].includes(p)) return done(false, `unknown preset '${p}'`);
          this.cfg = { ...this.cfg, preset: p, detectOn: p !== "fast", labelOn: true, ocrOn: p !== "fast" };
          this.event({ type: "CONFIG", message: "config changed", data: { preset: p } });
          return done(true, `preset ${p}`);
        }
        case "setModel": {
          const m = this.models.find((x) => x.id === args.id);
          if (!m) return done(false, `Unknown model '${args.id}'`);
          if (!m.available) return done(false, `${m.name} is not installed yet`);
          const old = this.models.find((x) => x.role === m.role && x.selected);
          this.after(90, () => {
            this.models = this.models.map((x) => (x.role === m.role ? { ...x, selected: x.id === m.id } : x));
            if (m.role === "DETECTOR") this.cfg.detector = m.id;
            if (m.role === "LABELER") this.cfg.labeler = m.id;
            if (m.role === "OCR") this.cfg.ocr = m.id;
            this.cfg.preset = "custom";
            this.event({ type: "MODEL_SWAP", stage: m.role.toLowerCase(), durMs: 90, message: `${old?.name ?? "none"} -> ${m.name}`, data: { role: m.role, from: old?.id ?? "", to: m.id } });
            this.fails[m.role.toLowerCase()] = 0;
            done(true, `loaded ${m.id} in 90 ms`);
          });
          return;
        }
        case "toggleStage": {
          const on = args.on === "true";
          if (args.stage === "detect") this.cfg.detectOn = on;
          if (args.stage === "label") this.cfg.labelOn = on;
          if (args.stage === "ocr") this.cfg.ocrOn = on;
          this.cfg.preset = "custom";
          return done(true, "ok");
        }
        case "setPreview": this.cfg.preview = args.on === "true"; return done(true, `preview ${args.on}`);
        case "setInterval": return done(true, "interval set");
        case "setMemory": this.cfg.memoryOn = args.on === "true"; return done(true, `memory ${args.on}`);
        case "rearm": {
          this.tripped.delete(args.stage); this.fails[args.stage] = 0;
          this.event({ type: "STAGE", stage: args.stage, status: "OK", message: "re-armed" });
          return done(true, "re-armed");
        }
        case "fault": {
          if (args.on === "false") this.fault.delete(args.stage); else this.fault.add(args.stage);
          return done(true, `fault ${args.stage}`);
        }
        case "clearFaults": this.fault.clear(); return done(true, "faults cleared");
        case "describe": this.frame("ask", true); return done(true, "Looking now.");
        case "ask": this.query(args.text ?? ""); return done(true, "asked");
        case "forget": this.notes = Math.max(0, this.notes - 3); return done(true, "Done. I erased 3 memories.");
        case "listSessions": {
          const rows: SessionRow[] = [
            { id: 2, startedAt: this.startedAt, endedAt: Date.now(), user: "Demo user", language: "en-IN", device: "SIMULATOR", appVersion: "sim", consent: 1, frames: this.processed, notes: this.notes, errors: 0, live: true },
            { id: 1, startedAt: this.startedAt - 3_600_000, endedAt: this.startedAt - 3_000_000, user: "Demo user", language: "hi-IN", device: "SIMULATOR", appVersion: "sim", consent: 1, frames: 212, notes: 41, errors: 2, live: false },
          ];
          this.emit({ type: "sessions", rows });
          return done(true, `${rows.length} sessions`);
        }
        default: return done(false, `unknown command '${name}'`);
      }
    });
  }

  // ---- generators -----------------------------------------------------------------------------------------------------------

  private frame(source: string, forced: boolean) {
    if (this.busy) { this.dropped++; this.gateEvent({ busy: 1 }); return; }
    if (!forced && !this.cfg.memoryOn) return;
    this.busy = true;
    const id = ++this.frameId;
    this.seen += 14; this.gated += 13; this.processed++;
    this.event({ type: "FRAME_START", frameId: id, stage: "camera", status: "OK", message: source, data: { source, w: 640, h: 480, forced } });
    this.event({ type: "GATE", frameId: id, stage: "gate", status: "OK", message: forced ? "forced" : "changed", data: { score: +(0.09 + Math.random() * 0.3).toFixed(3), threshold: 0.08, reason: "changed" } });

    const t: Record<string, number> = {};
    let at = 40;
    const run = (stage: string, ms: number, enabled = true, summary = "", data: Record<string, any> = {}) => {
      this.after(at, () => {
        if (!enabled) return this.stageEv(id, stage, "SKIPPED", 0, "turned off");
        if (this.tripped.has(stage)) return this.stageEv(id, stage, "DISABLED", 0, "tripped, waiting for re-arm");
        this.stageEv(id, stage, "START", 0, "");
        this.after(ms, () => {
          if (this.fault.has(stage)) {
            const n = (this.fails[stage] = (this.fails[stage] ?? 0) + 1);
            this.stageEv(id, stage, "ERROR", 2, `Injected fault in ${stage}`, { consecutive: n, limit: 3 });
            if (n >= 3) { this.tripped.add(stage); this.stageEv(id, stage, "DISABLED", 0, `tripped after ${n} failures`); }
            t[stage] = -1;
          } else {
            this.fails[stage] = 0;
            this.stageEv(id, stage, "OK", ms, summary, data);
            t[stage] = ms;
          }
        });
      });
    };

    const priv = jitter(330);
    const faces = Math.random() < 0.3 ? 1 : 0;
    run("privacy", priv, true, `${faces} face(s) blurred`, { faces });
    at += priv + 20;
    const dMs = jitter(120), lMs = jitter(135), oMs = jitter(390);
    const objs = OBJECT_SETS[id % OBJECT_SETS.length];
    run("detect", dMs, this.cfg.detectOn, `${objs.length} object(s)`, { count: objs.length, labels: objs });
    run("label", lMs, this.cfg.labelOn, `${objs.length} label(s)`, { count: objs.length, labels: [...objs].reverse() });
    run("ocr", oMs, this.cfg.ocrOn, "12 chars", { chars: 12 });
    // SIMULATED sensitive findings, so the Privacy page has something to show without a phone
    if (id % 4 === 0) {
      const samples = [
        { kind: "CARD", label: "Card number", masked: "4••• •••• •••• ••••", action: "removed from text before saving" },
        { kind: "PERSON", label: "Person's name", masked: "D•••••• K••••", action: "masked, not saved" },
        { kind: "PHONE", label: "Phone number", masked: "9•••• •••••", action: "removed from text before saving" },
        { kind: "SCREEN", label: "Screen showing text", masked: "84 characters visible", action: "text kept only with identifiers removed" },
      ];
      const pick = samples[(id / 4) % samples.length | 0];
      this.after(at, () => this.event({ type: "SENSITIVE", frameId: id, stage: "privacy", status: "OK", message: `${pick.label} detected`, data: { ...pick, source } }));
    }
    if (faces) this.after(at, () => this.event({ type: "SENSITIVE", frameId: id, stage: "privacy", status: "OK", message: "Face detected", data: { kind: "FACE", label: "Face", masked: `${faces} blurred`, action: "blurred in the picture before analysis", source } }));
    at += Math.max(this.cfg.detectOn ? dMs : 0, this.cfg.labelOn ? lMs : 0, this.cfg.ocrOn ? oMs : 0) + 25;
    // SIMULATED spatial graph: a fixed room layout and a heading that sweeps, so the Space page has something to show
    const layout: Record<string, [number, number, number]> = { laptop: [20, 0.9, -8], cup: [35, 0.8, -12], book: [-30, 1.4, -5], bottle: [75, 1.6, 4], chair: [-70, 2.4, -3], keyboard: [10, 0.7, -14], "mobile phone": [-12, 0.6, -10], backpack: [150, 2.2, -6], table: [-120, 2.0, -2] };
    const heading = ((Date.now() / 120) % 360);
    const placed = objs.filter((l) => layout[l]).map((l, k) => {
      const [a, d, el] = layout[l]; const r = (a * Math.PI) / 180;
      return { id: k + 1, name: `${l}#${k + 1}`, label: l, x: d * Math.sin(r), y: d * Math.cos(r), z: d * Math.sin((el * Math.PI) / 180), dist: d, az: (a + 360) % 360, el, conf: l === "table" ? 0.2 : 0.6 };
    });
    if (placed.length) this.after(at, () => this.event({ type: "SPATIAL", frameId: id, stage: "zone", status: "OK", message: `${placed.length} object(s) placed in the room`, data: { objects: placed, pose: { az: heading, pitch: -4, valid: true }, source } }));
    run("zone", 1, true, "desk", { zone: "desk" });
    at += 4;
    const caption = CAPTIONS[this.frameId % CAPTIONS.length];
    run("compose", jitter(5), true, caption, { objects: 3 });
    at += 12;
    const stored = Math.random() < 0.8;
    run("store", jitter(26), true, stored ? "note saved" : "same as last note, not duplicated", { saved: stored });
    at += 34;
    run("ledger", jitter(12), true, "3 item(s) updated", { items: 3 });
    at += 24;
    this.after(at, () => {
      if (stored) this.notes++;
      const errors = Object.values(t).filter((v) => v === -1).length;
      this.event({ type: "FRAME_END", frameId: id, durMs: at, status: errors ? "ERROR" : "OK", message: caption, data: { stored, errors, faces, zone: ZONES[id % ZONES.length], source } });
      this.busy = false;
    });
  }

  private query(text: string) {
    const steps: [string, number, string][] = [["intent", 3, "Where"], ["retrieve", 24, "memory"], ["answer", 31, "I last saw it near the desk 2 minutes ago."]];
    let at = 0;
    this.event({ type: "QUERY", stage: "mic", status: "OK", message: "listening" });
    for (const [stage, ms, msg] of steps) {
      this.after(at, () => this.event({ type: "QUERY", stage, status: "START", message: text }));
      at += 60;
      this.after(at, () => this.event({ type: "QUERY", stage, status: "OK", durMs: ms, message: msg }));
      at += 40;
    }
    this.after(at, () => this.event({ type: "QUERY", stage: "voice", status: "OK", durMs: 800, message: "spoken" }));
  }

  private gateSummary() {
    const reasons: Record<string, number> = { cooldown: Math.floor(Math.random() * 3), static: Math.floor(Math.random() * 3) };
    this.gateEvent(reasons);
    this.seen += 4;
    this.gated += reasons.cooldown + reasons.static;
  }

  private gateEvent(counts: Record<string, number>) {
    const total = Object.values(counts).reduce((a, b) => a + b, 0);
    if (!total) return;
    this.event({ type: "GATE", stage: "gate", status: "SKIPPED", message: Object.entries(counts).map(([k, v]) => `${k} x${v}`).join(", "), data: { counts, total, score: 0.006, threshold: 0.08 } });
  }

  private systemSample() {
    const s: SystemSample = {
      ramMb: 224 + Math.round(Math.random() * 20), sysUsedMb: 2050, sysTotalMb: 3568, cpu: +(6 + Math.random() * 8).toFixed(1), threads: 95,
      battery: 64, charging: true, tempC: +(31 + Math.random()).toFixed(1), currentMa: 40 + Math.round(Math.random() * 30), thermal: "Normal", headroom: -1,
      tx: 12_000, rx: 8_000, blocked: 0, lanConn: 0, seen: this.seen, gated: this.gated, processed: this.processed, dropped: this.dropped,
      notes: this.notes, busyPct: this.busy ? 70 + Math.random() * 20 : Math.random() * 25, clients: 1, camera: "open", ready: true,
    };
    this.emit({ v: 1, type: "SYSTEM", t: Date.now(), frameId: 0, durMs: 0, message: "", data: { ...s, poseAz: (Date.now() / 120) % 360, posePitch: -4, poseValid: true, poseSensor: "FULL", ai: this.aiUnits() } } as PipelineEvent);
  }

  /** simulated engines, shaped like the phone's real measurements */
  private aiUnits(): AiUnit[] {
    const wave = (a: number, b: number) => +(a + Math.random() * (b - a)).toFixed(1);
    return [
      { name: "EmbeddingGemma-2", role: "search by meaning (text, picture, sound)", backend: "NPU (text) + GPU (vision)", state: "ready", loadMs: 1900, calls: this.processed * 3, lastMs: 8, avgMs: 22, busyPct: this.busy ? wave(8, 20) : wave(0, 6) },
      { name: "Gemma-4 E2B", role: "answers, vision, speech, translation", backend: "GPU", state: "ready", loadMs: 2600, calls: this.processed, lastMs: 3900, avgMs: 2400, busyPct: wave(0, 30) },
      { name: "FunctionGemma 270M", role: "phone actions", backend: "CPU", state: "ready", loadMs: 180, calls: 2, lastMs: 540, avgMs: 520, busyPct: wave(0, 3) },
      { name: "ML Kit + EfficientDet", role: "camera frames: faces, objects, text", backend: "CPU / GPU", state: "ready", loadMs: 0, calls: this.processed, lastMs: 380, avgMs: 400, busyPct: this.busy ? wave(40, 80) : wave(0, 20) },
    ];
  }

  private hello(): Hello {
    return {
      type: "hello",
      app: { name: "Smriti", version: "simulated", debug: true },
      device: { summary: "SIMULATOR | generated data", soc: "none" },
      user: { name: "Demo user", language: "en-IN", source: "phone" },
      session: { id: 2, startedAt: this.startedAt },
      config: { ...this.cfg } as Hello["config"],
      tripped: [...this.tripped],
      stages: { frame: ["camera", "gate", "privacy", "detect", "label", "ocr", "zone", "compose", "store", "ledger"], query: ["mic", "speech", "intent", "retrieve", "answer", "voice"] },
      models: this.models.map((m) => ({ ...m })),
      transport: "sim",
      uptimeMs: Date.now() - this.startedAt,
    };
  }

  private stageEv(frameId: number, stage: string, status: Status, durMs: number, message: string, data: Record<string, any> = {}) {
    this.event({ type: "STAGE", frameId, stage, status, durMs, message, data });
  }

  private log(level: string, tag: string, message: string) {
    this.event({ type: "LOG", stage: tag, message, data: { level } });
  }

  private event(p: Partial<PipelineEvent> & { type: PipelineEvent["type"] }) {
    this.emit({ v: 1, t: Date.now(), frameId: 0, durMs: 0, message: "", data: {}, ...p } as PipelineEvent);
  }

  private after(ms: number, fn: () => void) { this.timers.push(setTimeout(fn, ms)); }
  private every(ms: number, fn: () => void) { this.intervals.push(setInterval(fn, ms)); }
}
