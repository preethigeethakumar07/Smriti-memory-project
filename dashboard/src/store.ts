import { create } from "zustand";
import type { CmdResult, Hello, MoveMsg, SavedWalk, PipelineEvent, PreviewHeader, ServerMessage, SessionRow, SystemSample } from "./protocol";
import {
  FrameAccumulator, MAX_FRAMES, MAX_LOGS, emptyGate, initialNodes, pushCapped, reduceGate, reduceNodes, toLog,
  type FrameRow, type GateStats, type LogRow, type NodeState,
} from "./reducer";
import { STAGE_ROLE } from "./graph";
import { eventToSignals, flowBus } from "./signals";

export type ConnState = "connecting" | "live" | "reconnecting" | "simulated" | "authRequired" | "authFailed";

export interface Preview { frameId: number; url: string; header: PreviewHeader; at: number }

export interface SysPoint { t: number; s: SystemSample }

export interface SensitiveRow { id: number; t: number; kind: string; label: string; masked: string; action: string; source: string; frameId: number }

export interface SpaceObj { name: string; label: string; x: number; y: number; z: number; dist: number; az: number; el: number; conf: number; lastSeen: number }
export interface SpaceState { objects: Record<string, SpaceObj>; pose: { az: number; pitch: number; valid: boolean } | null }

export interface CmdLogRow { id: string; name: string; args: Record<string, string>; ok: boolean | null; message: string; at: number }

interface State {
  conn: ConnState;
  connNote: string;
  latencyMs: number;
  hello: Hello | null;
  nodes: Record<string, NodeState>;
  gate: GateStats;
  frames: FrameRow[];
  logs: LogRow[];
  sensitive: SensitiveRow[];
  space: SpaceState;
  sys: SystemSample | null;
  sysHist: SysPoint[];
  sessions: SessionRow[];
  move: MoveMsg | null;
  moveSaved: { places: { name: string; kind: string; radiusM: number }[]; walks: SavedWalk[] };
  preview: Preview | null;
  cmdLog: CmdLogRow[];
  selected: string | null;
  tripped: string[];
  camera: string;
  setConn(c: ConnState, note?: string): void;
  setLatency(ms: number): void;
  setSelected(s: string | null): void;
  setSessions(r: SessionRow[]): void;
  setPreview(p: Preview | null): void;
  logCmd(row: CmdLogRow): void;
  resolveCmd(r: CmdResult): void;
  reset(): void;
}

const SYS_CAP = 180;

export const useStore = create<State>()((set) => ({
  conn: "connecting",
  connNote: "",
  latencyMs: 0,
  hello: null,
  nodes: initialNodes(),
  gate: emptyGate(),
  frames: [],
  logs: [],
  sensitive: [],
  space: { objects: {}, pose: null },
  sys: null,
  sysHist: [],
  sessions: [],
  move: null,
  moveSaved: { places: [], walks: [] },
  preview: null,
  cmdLog: [],
  selected: null,
  tripped: [],
  camera: "off",
  setConn: (conn, connNote = "") => set({ conn, connNote }),
  setLatency: (latencyMs) => set({ latencyMs }),
  setSelected: (selected) => set({ selected }),
  setSessions: (sessions) => set({ sessions }),
  setPreview: (preview) => set((s) => {
    if (s.preview?.url && s.preview.url !== preview?.url) URL.revokeObjectURL(s.preview.url);
    return { preview };
  }),
  logCmd: (row) => set((s) => ({ cmdLog: pushCapped(s.cmdLog, row, 60) })),
  resolveCmd: (r) => set((s) => ({
    cmdLog: s.cmdLog.map((c) => (c.id === r.id ? { ...c, ok: r.ok, message: r.message } : c)),
  })),
  reset: () => set({
    nodes: initialNodes(), gate: emptyGate(), frames: [], logs: [], sensitive: [], space: { objects: {}, pose: null }, sys: null, sysHist: [], hello: null, tripped: [], camera: "off",
  }),
}));

// ---- batched ingestion -------------------------------------------------------------------------------------------------

/** Name of the model currently selected for a model stage (detect / label / ocr), or "". */
export function modelNameFor(hello: Hello | null, stage: string): string {
  const role = STAGE_ROLE[stage];
  if (!role || !hello) return "";
  return hello.models.find((m) => m.role === role && m.selected)?.name ?? "";
}

const queue: ServerMessage[] = [];
const frames = new FrameAccumulator();
let logSeq = 0;
let replaying = false;
let replayUntil = 0;

/** Called by the connection for every message. Cheap: just queues; the flush applies a whole batch in one state update. */
export function ingest(m: ServerMessage) {
  queue.push(m);
}

/** After (re)connecting, the server replays recent history; update state from it but do not animate it. */
export function beginReplay(ms = 700) {
  replaying = true;
  replayUntil = performance.now() + ms;
}

export function flush() {
  if (!queue.length) return;
  const batch = queue.splice(0, queue.length);
  if (replaying && performance.now() > replayUntil) replaying = false;
  const now = Date.now();
  const s = useStore.getState();

  let nodes = s.nodes;
  let gate = s.gate;
  let helloMsg = s.hello;
  let sys = s.sys;
  let sysHist = s.sysHist;
  let framesList = s.frames;
  let logs = s.logs;
  let sensitive = s.sensitive;
  let space = s.space;
  let tripped = s.tripped;
  let camera = s.camera;
  let preview: PreviewHeader | null = null;
  let sessions: SessionRow[] | null = null;
  let move: MoveMsg | null = null;
  let moveSaved: { places: { name: string; kind: string; radiusM: number }[]; walks: SavedWalk[] } | null = null;
  const cmdResults: CmdResult[] = [];

  for (const m of batch) {
    switch (m.type) {
      case "hello":
        helloMsg = m;
        tripped = m.tripped;
        // a node the phone says is tripped must look tripped even if we missed the event
        for (const st of m.tripped) if (nodes[st] && nodes[st].status !== "disabled") nodes = { ...nodes, [st]: { ...nodes[st], status: "disabled", message: "tripped, waiting for re-arm" } };
        break;
      case "SYSTEM": {
        const d = (m as PipelineEvent).data as unknown as SystemSample;
        sys = d;
        sysHist = pushCapped(sysHist, { t: (m as PipelineEvent).t, s: d }, SYS_CAP);
        camera = d.camera ?? camera;
        break;
      }
      case "cmdResult": cmdResults.push(m); break;
      case "preview": preview = m; break;
      case "sessions": sessions = m.rows; break;
      case "move": move = m; break;
      case "moveSaved": moveSaved = { places: m.places, walks: m.walks }; break;
      case "pong": case "authRequired": case "authFailed": break;
      default: {
        const e = m as PipelineEvent;
        if (!e.type) break;
        if (!replaying) for (const sig of eventToSignals(e, nodes)) flowBus.emit(sig);
        nodes = reduceNodes(nodes, e, now);
        gate = reduceGate(gate, e);
        const row = frames.handle(e);
        if (row) framesList = pushCapped(framesList, row, MAX_FRAMES);
        const log = toLog(e, ++logSeq, (stage) => modelNameFor(helloMsg, stage));
        if (log) logs = pushCapped(logs, log, MAX_LOGS);
        if (e.type === "SENSITIVE") {
          const d = e.data as Record<string, string>;
          sensitive = pushCapped(sensitive, { id: ++logSeq, t: e.t, kind: String(d.kind ?? ""), label: String(d.label ?? e.message), masked: String(d.masked ?? ""), action: String(d.action ?? ""), source: String(d.source ?? ""), frameId: e.frameId }, 300);
          logs = pushCapped(logs, { id: ++logSeq, t: e.t, level: "warn", tag: "privacy", message: `${d.label ?? "Sensitive item"} caught (${d.masked ?? ""})`, frameId: e.frameId, model: "", ms: 0 }, MAX_LOGS);
        }
        if (e.type === "SPATIAL") {
          const d = e.data as any;
          const objects = { ...space.objects };
          for (const o of (d.objects ?? []) as any[]) objects[String(o.name)] = { name: String(o.name), label: String(o.label), x: +o.x, y: +o.y, z: +o.z, dist: +o.dist, az: +o.az, el: +o.el, conf: +o.conf, lastSeen: e.t };
          // keep the 40 most recently seen
          const keep = Object.values(objects).sort((a, b) => b.lastSeen - a.lastSeen).slice(0, 40);
          space = { objects: Object.fromEntries(keep.map((k) => [k.name, k])), pose: d.pose ? { az: +d.pose.az, pitch: +d.pose.pitch, valid: !!d.pose.valid } : space.pose };
        }
        if (e.type === "CAMERA") camera = String(e.data.state ?? camera);
        if (e.type === "STAGE" && e.status === "DISABLED" && e.message.startsWith("tripped after") && e.stage && !tripped.includes(e.stage)) tripped = [...tripped, e.stage];
        if (e.type === "STAGE" && e.status === "OK" && e.message === "re-armed" && e.stage) tripped = tripped.filter((x) => x !== e.stage);
      }
    }
  }

  useStore.setState({ nodes, gate, hello: helloMsg, sys, sysHist, frames: framesList, logs, sensitive, space, tripped, camera });
  if (sessions) useStore.getState().setSessions(sessions);
  if (move) useStore.setState({ move });
  if (moveSaved) useStore.setState({ moveSaved });
  cmdResults.forEach((r) => { useStore.getState().resolveCmd(r); cmdSettler?.(r); });
  void preview;   // the preview header is also kept by the connection, which pairs it with the binary frame
}

let cmdSettler: ((r: CmdResult) => void) | null = null;
export function setCmdSettler(fn: (r: CmdResult) => void) { cmdSettler = fn; }

let timer: ReturnType<typeof setInterval> | null = null;
export function startFlushLoop() {
  if (timer) return;
  // setInterval, not requestAnimationFrame: it keeps running when the tab is in the background
  timer = setInterval(flush, 60);
}
