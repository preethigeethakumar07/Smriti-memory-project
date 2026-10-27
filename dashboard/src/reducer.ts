import type { PipelineEvent, SystemSample } from "./protocol";

/**
 * Pure state logic: events in, state out. No React, no DOM, so it is covered by unit tests.
 * The visual layer (packets flying along edges) listens to the same events separately.
 */

export type NodeStatus = "idle" | "running" | "ok" | "skipped" | "error" | "disabled";

export interface NodeState {
  status: NodeStatus;
  ms: number;
  message: string;
  frameId: number;
  runs: number;
  errors: number;
  /** last durations in ms, oldest first, capped */
  history: number[];
  /** wall-clock ms when the current run started, for the live timer */
  startedAt: number;
}

export interface FrameRow {
  frameId: number;
  t: number;
  source: string;
  totalMs: number;
  caption: string;
  stored: boolean;
  errors: number;
  faces: number;
  zone: string;
  stages: Record<string, number>;
  ok: boolean;
}

export interface LogRow {
  id: number;
  t: number;
  level: "debug" | "info" | "warn" | "error";
  tag: string;
  message: string;
  frameId: number;
  /** name of the model that ran this stage, when the stage is a model stage */
  model: string;
  ms: number;
}

export interface GateStats {
  accepted: number;
  rejected: number;
  reasons: Record<string, number>;
  lastScore: number;
  threshold: number;
}

export const HISTORY = 24;
export const MAX_LOGS = 1500;
export const MAX_FRAMES = 40;

export const ALL_STAGES = [
  "camera", "gate", "privacy", "detect", "label", "ocr", "zone", "compose", "store", "ledger",
  "mic", "speech", "intent", "retrieve", "answer", "voice",
];

export const emptyNode = (): NodeState => ({
  status: "idle", ms: 0, message: "", frameId: 0, runs: 0, errors: 0, history: [], startedAt: 0,
});

export function initialNodes(): Record<string, NodeState> {
  return Object.fromEntries(ALL_STAGES.map((s) => [s, emptyNode()]));
}

export const emptyGate = (): GateStats => ({ accepted: 0, rejected: 0, reasons: {}, lastScore: 0, threshold: 0.08 });

function push(history: number[], ms: number): number[] {
  const next = history.length >= HISTORY ? history.slice(history.length - HISTORY + 1) : history.slice();
  next.push(ms);
  return next;
}

/** Applies one STAGE / QUERY / GATE / CAMERA event to the node map. Returns a new map only when something changed. */
export function reduceNodes(nodes: Record<string, NodeState>, e: PipelineEvent, now: number): Record<string, NodeState> {
  const stage = e.stage;
  if (!stage || !(stage in nodes)) return nodes;
  if (e.type !== "STAGE" && e.type !== "QUERY" && e.type !== "GATE" && e.type !== "CAMERA" && e.type !== "FRAME_START") return nodes;
  const cur = nodes[stage];
  let next: NodeState;

  // "frames skipped" summaries mean the gate is WORKING (rejecting frames), not that it is off
  if (e.type === "GATE" && e.status === "SKIPPED") {
    const total = Number(e.data.total ?? 0);
    const status: NodeStatus = cur.status === "idle" ? "ok" : cur.status === "running" ? "ok" : cur.status;
    return { ...nodes, [stage]: { ...cur, status, message: `${total} skipped: ${e.message}` } };
  }

  switch (e.status) {
    case "START":
      next = { ...cur, status: "running", startedAt: now, frameId: e.frameId, message: e.message };
      break;
    case "OK":
      if (e.type === "FRAME_START") {
        next = { ...cur, status: "ok", frameId: e.frameId, message: e.message, runs: cur.runs + 1 };
      } else {
        next = {
          ...cur, status: "ok", ms: e.durMs, message: e.message, frameId: e.frameId,
          runs: cur.runs + 1, history: e.durMs > 0 ? push(cur.history, e.durMs) : cur.history,
        };
      }
      break;
    case "SKIPPED":
      next = { ...cur, status: "skipped", message: e.message, frameId: e.frameId };
      break;
    case "ERROR":
      next = { ...cur, status: "error", ms: e.durMs, message: e.message, frameId: e.frameId, errors: cur.errors + 1 };
      break;
    case "DISABLED":
      next = { ...cur, status: "disabled", message: e.message, frameId: e.frameId };
      break;
    default:
      return nodes;
  }
  return { ...nodes, [stage]: next };
}

export function reduceGate(g: GateStats, e: PipelineEvent): GateStats {
  if (e.type !== "GATE") return g;
  const threshold = typeof e.data.threshold === "number" ? e.data.threshold : g.threshold;
  const score = typeof e.data.score === "number" ? e.data.score : g.lastScore;
  if (e.status === "OK") {
    return { ...g, accepted: g.accepted + 1, lastScore: score, threshold };
  }
  const counts = (e.data.counts ?? {}) as Record<string, number>;
  const reasons = { ...g.reasons };
  let rejected = g.rejected;
  for (const [k, n] of Object.entries(counts)) {
    reasons[k] = (reasons[k] ?? 0) + n;
    rejected += n;
  }
  return { ...g, rejected, reasons, lastScore: score, threshold };
}

/** `modelOf` maps a model stage (detect / label / ocr) to the name of the model currently selected for it. */
export function toLog(e: PipelineEvent, id: number, modelOf: (stage: string) => string = () => ""): LogRow | null {
  const row = (level: LogRow["level"], tag: string, message: string, frameId = e.frameId, ms = 0): LogRow => ({
    id, t: e.t, level, tag, message, frameId, ms, model: modelOf(tag),
  });

  if (e.type === "LOG") {
    const level = (e.data.level as string) === "error" ? "error" : (e.data.level as string) === "warn" ? "warn" : "info";
    return row(level, e.stage ?? "app", e.message);
  }
  if (e.type === "STAGE" && e.status === "ERROR") return row("error", e.stage ?? "stage", e.message, e.frameId, e.durMs);
  if (e.type === "STAGE" && e.status === "DISABLED" && e.message.startsWith("tripped after")) return row("error", e.stage ?? "stage", e.message);
  if (e.type === "MODEL_SWAP") return row("info", "models", `swapped ${e.message}, loaded in ${e.durMs} ms`, 0, e.durMs);
  if (e.type === "CAMERA") return row(e.status === "ERROR" ? "error" : "info", "camera", `camera ${e.message}`, 0);
  if (e.type === "CONFIG") return row("info", "config", `pipeline preset is now ${e.data.preset}`, 0);
  if (e.type === "QUERY" && e.stage === "answer") return row("info", "answer", e.message, 0, e.durMs);

  // routine traffic, kept so the log always shows the pipeline working
  if (e.type === "FRAME_START") return row("debug", "camera", `frame ${e.frameId} captured (${e.data.source ?? e.message})`);
  if (e.type === "GATE" && e.status === "OK") return row("debug", "gate", `accepted: ${e.data.reason ?? e.message} (change score ${Number(e.data.score ?? 0).toFixed(2)})`);
  if (e.type === "GATE" && e.status === "SKIPPED") return row("debug", "gate", `skipped ${e.message}`, 0);
  if (e.type === "STAGE" && e.status === "OK" && e.stage && e.message !== "re-armed") {
    const found = Array.isArray(e.data.labels) && e.data.labels.length ? `: ${(e.data.labels as string[]).join(", ")}` : "";
    return row("debug", e.stage, `${e.message}${found}`, e.frameId, e.durMs);
  }
  if (e.type === "STAGE" && e.status === "SKIPPED" && e.stage) return row("debug", e.stage, `skipped (${e.message})`);
  if (e.type === "QUERY" && e.status === "OK" && e.stage) return row("debug", e.stage, e.message || "ok", 0, e.durMs);
  if (e.type === "FRAME_END") return row("debug", "frame", `complete: ${e.data.stored ? "note saved" : "no new note"}${e.data.errors ? `, ${e.data.errors} error(s)` : ""}`, e.frameId, e.durMs);
  return null;
}

/** Accumulates per-frame stage durations; closes a row on FRAME_END. */
export class FrameAccumulator {
  private open = new Map<number, { source: string; stages: Record<string, number>; t: number }>();

  handle(e: PipelineEvent): FrameRow | null {
    if (e.frameId <= 0) return null;
    if (e.type === "FRAME_START") {
      this.open.set(e.frameId, { source: String(e.data.source ?? e.message ?? ""), stages: {}, t: e.t });
      if (this.open.size > 64) this.open.delete(this.open.keys().next().value as number);
      return null;
    }
    const f = this.open.get(e.frameId);
    if (e.type === "STAGE" && e.status === "OK" && f && e.stage) f.stages[e.stage] = e.durMs;
    if (e.type === "FRAME_END") {
      this.open.delete(e.frameId);
      return {
        frameId: e.frameId, t: e.t, source: f?.source ?? String(e.data.source ?? ""), totalMs: e.durMs,
        caption: e.message, stored: !!e.data.stored, errors: Number(e.data.errors ?? 0), faces: Number(e.data.faces ?? 0),
        zone: String(e.data.zone ?? ""), stages: f?.stages ?? {}, ok: e.status !== "ERROR",
      };
    }
    return null;
  }
}

// ---- small stats helpers used by charts and tables ----------------------------------------------------------

export function median(xs: number[]): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
}

export function p95(xs: number[]): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor((s.length - 1) * 0.95)];
}

export function pushCapped<T>(arr: T[], v: T, cap: number): T[] {
  const next = arr.length >= cap ? arr.slice(arr.length - cap + 1) : arr.slice();
  next.push(v);
  return next;
}

export type { SystemSample };
