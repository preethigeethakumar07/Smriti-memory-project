// Fixed geometry of the pipeline diagram, drawn on a 1280 x 720 stage and scaled to fit.
// Connectors are curves computed from node anchors, so a moved node never leaves a dangling line.

export const STAGE_W = 1280;
export const STAGE_H = 748;
export const NODE_W = 168;
export const NODE_H = 112;
/** y of the divider between the frame lane and the question lane, and the question lane's row */
export const LANE_RULE_Y = 586;
export const QUERY_Y = 622;

export type NodeKind = "source" | "step" | "model" | "store" | "query";
export type IconKey =
  | "camera" | "gate" | "privacy" | "detect" | "label" | "ocr" | "zone" | "compose" | "store" | "ledger"
  | "mic" | "speech" | "intent" | "retrieve" | "answer" | "voice";

export interface GraphNode {
  id: string;
  title: string;
  sub: string;
  kind: NodeKind;
  x: number;
  y: number;
  icon: IconKey;
  /** model nodes can be swapped: which catalog role feeds them */
  role?: "DETECTOR" | "LABELER" | "OCR";
}

export const NODES: GraphNode[] = [
  { id: "camera", title: "Camera", sub: "Phone or glasses", kind: "source", x: 20, y: 184, icon: "camera" },
  { id: "gate", title: "Gate", sub: "Scene change", kind: "step", x: 220, y: 184, icon: "gate" },
  { id: "privacy", title: "Privacy", sub: "Face blur", kind: "step", x: 420, y: 184, icon: "privacy" },
  { id: "detect", title: "Detect", sub: "Object model", kind: "model", x: 650, y: 30, icon: "detect", role: "DETECTOR" },
  { id: "label", title: "Label", sub: "Image model", kind: "model", x: 650, y: 184, icon: "label", role: "LABELER" },
  { id: "ocr", title: "Text", sub: "Text model", kind: "model", x: 650, y: 338, icon: "ocr", role: "OCR" },
  { id: "zone", title: "Place", sub: "Zone match", kind: "step", x: 870, y: 184, icon: "zone" },
  { id: "compose", title: "Note", sub: "Compose text", kind: "step", x: 1070, y: 184, icon: "compose" },
  { id: "store", title: "Memory", sub: "Text rows", kind: "store", x: 1070, y: 330, icon: "store" },
  { id: "ledger", title: "Ledger", sub: "Last seen", kind: "store", x: 1070, y: 456, icon: "ledger" },
  { id: "mic", title: "Mic", sub: "Voice in", kind: "query", x: 20, y: QUERY_Y, icon: "mic" },
  { id: "speech", title: "Speech", sub: "On-device ASR", kind: "query", x: 220, y: QUERY_Y, icon: "speech" },
  { id: "intent", title: "Intent", sub: "Rule router", kind: "query", x: 420, y: QUERY_Y, icon: "intent" },
  { id: "retrieve", title: "Retrieve", sub: "Memory lookup", kind: "query", x: 650, y: QUERY_Y, icon: "retrieve" },
  { id: "answer", title: "Answer", sub: "From rows only", kind: "query", x: 870, y: QUERY_Y, icon: "answer" },
  { id: "voice", title: "Voice", sub: "Text to speech", kind: "query", x: 1070, y: QUERY_Y, icon: "voice" },
];

export type Side = "l" | "r" | "t" | "b";

interface Pt { x: number; y: number }

const nodeById = (id: string) => NODES.find((n) => n.id === id)!;

export function anchor(id: string, side: Side, along = 0.5): Pt {
  const n = nodeById(id);
  switch (side) {
    case "l": return { x: n.x, y: n.y + NODE_H * along };
    case "r": return { x: n.x + NODE_W, y: n.y + NODE_H * along };
    case "t": return { x: n.x + NODE_W * along, y: n.y };
    case "b": return { x: n.x + NODE_W * along, y: n.y + NODE_H };
  }
}

const NORMAL: Record<Side, Pt> = { l: { x: -1, y: 0 }, r: { x: 1, y: 0 }, t: { x: 0, y: -1 }, b: { x: 0, y: 1 } };

/** Smooth cubic curve leaving `a` along its side normal and arriving at `b` along its side normal. */
export function curve(a: Pt, sa: Side, b: Pt, sb: Side): string {
  const dist = Math.hypot(b.x - a.x, b.y - a.y);
  const k = Math.max(36, dist * 0.45);
  const c1 = { x: a.x + NORMAL[sa].x * k, y: a.y + NORMAL[sa].y * k };
  const c2 = { x: b.x + NORMAL[sb].x * k, y: b.y + NORMAL[sb].y * k };
  const f = (n: number) => Math.round(n * 10) / 10;
  return `M${f(a.x)},${f(a.y)} C${f(c1.x)},${f(c1.y)} ${f(c2.x)},${f(c2.y)} ${f(b.x)},${f(b.y)}`;
}

export interface GraphEdge {
  id: string;
  from: string;
  to: string;
  d: string;
  /** reads from memory (drawn on the way back) */
  label?: string;
  labelAt?: Pt;
}

function edge(from: string, sf: Side, to: string, st: Side, opt: { fromAlong?: number; toAlong?: number; label?: string } = {}): GraphEdge {
  const a = anchor(from, sf, opt.fromAlong ?? 0.5);
  const b = anchor(to, st, opt.toAlong ?? 0.5);
  return {
    id: `${from}>${to}`, from, to, d: curve(a, sf, b, st), label: opt.label,
    labelAt: opt.label ? { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } : undefined,
  };
}

export const EDGES: GraphEdge[] = [
  edge("camera", "r", "gate", "l"),
  edge("gate", "r", "privacy", "l"),
  edge("privacy", "r", "detect", "l"),
  edge("privacy", "r", "label", "l"),
  edge("privacy", "r", "ocr", "l"),
  edge("detect", "r", "zone", "l"),
  edge("label", "r", "zone", "l"),
  edge("ocr", "r", "zone", "l"),
  edge("zone", "r", "compose", "l"),
  edge("compose", "b", "store", "t"),
  edge("store", "b", "ledger", "t"),
  edge("mic", "r", "speech", "l"),
  edge("speech", "r", "intent", "l"),
  edge("intent", "r", "retrieve", "l"),
  edge("retrieve", "r", "answer", "l"),
  edge("answer", "r", "voice", "l"),
  // the way back: the question path READS memory
  edge("store", "l", "retrieve", "t", { toAlong: 0.8, label: "READS" }),
  edge("ledger", "l", "retrieve", "t", { toAlong: 0.25, label: "READS" }),
];

export const edgeById = (id: string) => EDGES.find((x) => x.id === id);

/** Which edges light up when a stage starts. Parallel branches rejoin at "zone". */
export const INCOMING: Record<string, string[]> = {
  gate: ["camera>gate"],
  privacy: ["gate>privacy"],
  detect: ["privacy>detect"],
  label: ["privacy>label"],
  ocr: ["privacy>ocr"],
  zone: ["detect>zone", "label>zone", "ocr>zone"],
  compose: ["zone>compose"],
  store: ["compose>store"],
  ledger: ["store>ledger"],
  speech: ["mic>speech"],
  intent: ["speech>intent"],
  retrieve: ["intent>retrieve", "store>retrieve", "ledger>retrieve"],
  answer: ["retrieve>answer"],
  voice: ["answer>voice"],
};

/** Tiny local detour used to animate a model swap: library tab -> node and back. */
export function swapPaths(node: GraphNode): { into: string; out: string } {
  const lx = node.x + NODE_W - 22;
  const ly = node.y - 16;
  const cx = node.x + NODE_W - 22;
  const cy = node.y + 22;
  return { into: `M${lx},${ly} L${cx},${cy}`, out: `M${cx},${cy} L${lx},${ly}` };
}

/** Stage -> catalog role, for putting the active model's name on the stage's tile and in the log. */
export const STAGE_ROLE: Record<string, "DETECTOR" | "LABELER" | "OCR"> = { detect: "DETECTOR", label: "LABELER", ocr: "OCR" };
