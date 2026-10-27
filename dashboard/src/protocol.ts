// Wire protocol v1: one JSON object per WebSocket text message. Mirrors app/.../dashboard/EventJson.kt.

export type EventType =
  | "FRAME_START" | "GATE" | "STAGE" | "FRAME_END" | "MODEL_SWAP"
  | "QUERY" | "CAMERA" | "CONFIG" | "SYSTEM" | "LOG" | "SENSITIVE" | "SPATIAL";

export type Status = "START" | "OK" | "SKIPPED" | "ERROR" | "DISABLED";

export interface PipelineEvent {
  v?: number;
  type: EventType;
  t: number;
  frameId: number;
  stage?: string;
  status?: Status;
  durMs: number;
  message: string;
  data: Record<string, any>;
}

export interface ModelSpec {
  id: string;
  role: "DETECTOR" | "LABELER" | "OCR" | "CAPTIONER";
  name: string;
  backend: string;
  note: string;
  available: boolean;
  selected: boolean;
}

export interface PipelineConfig {
  preset: string;
  detector: string;
  labeler: string;
  ocr: string;
  detectOn: boolean;
  labelOn: boolean;
  ocrOn: boolean;
  preview: boolean;
  intervalSec: number;
  memoryOn: boolean;
}

export interface Hello {
  type: "hello";
  app: { name: string; version: string; debug: boolean };
  device: { summary: string; soc: string };
  user: { name: string; language: string; source: string };
  session: { id: number; startedAt: number };
  config: PipelineConfig;
  tripped: string[];
  stages: { frame: string[]; query: string[] };
  models: ModelSpec[];
  transport: "usb" | "lan" | "sim";
  uptimeMs: number;
}

/** One on-device AI engine, measured by the phone: its real backend and how busy it was in the last 10 seconds. */
export interface AiUnit {
  name: string; role: string; backend: string; state: string;
  loadMs: number; calls: number; lastMs: number; avgMs: number; busyPct: number;
}

export const isNpu = (u: AiUnit) => u.backend.startsWith("NPU");

export interface SystemSample {
  ramMb: number; sysUsedMb: number; sysTotalMb: number;
  cpu: number; threads: number;
  battery: number; charging: boolean; tempC: number; currentMa: number;
  thermal: string; headroom: number;
  tx: number; rx: number; blocked: number; lanConn: number;
  seen: number; gated: number; processed: number; dropped: number;
  notes: number; busyPct: number; clients: number;
  camera: string; ready: boolean;
  poseAz?: number; posePitch?: number; poseValid?: boolean; poseSensor?: string;
  ai?: AiUnit[];
}

export interface SessionRow {
  id: number; startedAt: number; endedAt: number;
  user: string; language: string; device: string; appVersion: string; consent: number;
  frames: number; notes: number; errors: number; live: boolean;
}

export interface CmdResult { type: "cmdResult"; id: string; name: string; ok: boolean; message: string }

export interface PreviewHeader {
  type: "preview"; frameId: number; caption: string; w: number; h: number; bytes: number;
  boxes: { label: string; c: number; x: number; y: number; w: number; h: number }[];
}

export type ServerMessage =
  | Hello
  | PipelineEvent
  | CmdResult
  | PreviewHeader
  | { type: "sessions"; rows: SessionRow[] }
  | MoveMsg
  | MoveSavedMsg
  | { type: "pong"; t: number }
  | { type: "authRequired" }
  | { type: "authFailed" };

/** Stage ids in draw order; must match Stage.kt on the phone. */
export const FRAME_STAGES = ["camera", "gate", "privacy", "detect", "label", "ocr", "zone", "compose", "store", "ledger"] as const;
export const QUERY_STAGES = ["mic", "speech", "intent", "retrieve", "answer", "voice"] as const;

/** The walk guide, live. Points are [x, y] in metres east and north of where the walk began. */
export type XY = [number, number];
export interface MoveMsg {
  type: "move";
  demo: boolean;
  active: boolean;
  state: {
    mode: string; follow: string; title: string; cue: string | null; errDeg: number; phrase: string; remainingM: number;
    steps: number; walkedM: number; gpsM: number | null; headingDeg: number | null; arrived: boolean; note: string; compassPoor: boolean;
  };
  trail: { out: XY[]; back: XY[]; route: XY[]; here: XY | null; start: XY | null; dest: XY | null; destLabel: string; heading: number | null; sigma: number };
}
export interface SavedWalk { id: number; name: string; lengthM: number; hasGps: boolean; createdAt: number; points: XY[] }
export interface MoveSavedMsg { type: "moveSaved"; places: { name: string; kind: string; radiusM: number }[]; walks: SavedWalk[] }
