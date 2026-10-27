import type { PipelineEvent } from "./protocol";
import { INCOMING } from "./graph";
import type { NodeState } from "./reducer";

/** What the diagram should animate for an event. Pure, so the mapping is unit tested. */
export type Signal =
  | { kind: "hop"; edge: string; tone: "frame" | "read"; frameId: number }
  | { kind: "drop"; reason: string; count: number }
  | { kind: "swap"; stage: string }
  | { kind: "fault"; stage: string };

const ROLE_STAGE: Record<string, string> = { detector: "detect", labeler: "label", ocr: "ocr" };

export function eventToSignals(e: PipelineEvent, nodes: Record<string, NodeState>): Signal[] {
  switch (e.type) {
    case "FRAME_START":
      return [{ kind: "hop", edge: "camera>gate", tone: "frame", frameId: e.frameId }];

    case "STAGE":
    case "QUERY": {
      const stage = e.stage;
      if (!stage) return [];
      if (e.status === "ERROR") return [{ kind: "fault", stage }];
      if (e.status !== "START") return [];
      const edges = INCOMING[stage] ?? [];
      const out: Signal[] = [];
      for (const edge of edges) {
        const from = edge.split(">")[0];
        const isRead = edge === "store>retrieve" || edge === "ledger>retrieve";
        // when branches rejoin, only the ones that actually produced output send a packet
        if (stage === "zone") {
          const st = nodes[from]?.status;
          if (st !== "ok") continue;
        }
        out.push({ kind: "hop", edge, tone: isRead ? "read" : "frame", frameId: e.frameId });
      }
      return out;
    }

    case "GATE": {
      if (e.status !== "SKIPPED") return [];
      const counts = (e.data.counts ?? {}) as Record<string, number>;
      return Object.entries(counts).map(([reason, count]) => ({ kind: "drop" as const, reason, count }));
    }

    case "MODEL_SWAP": {
      const stage = ROLE_STAGE[(e.stage ?? "").toLowerCase()];
      return stage ? [{ kind: "swap", stage }] : [];
    }

    default:
      return [];
  }
}

/** Listeners for visual signals. The diagram subscribes; nothing else does. */
type Listener = (s: Signal) => void;
const listeners = new Set<Listener>();
export const flowBus = {
  on(fn: Listener) { listeners.add(fn); return () => { listeners.delete(fn); }; },
  emit(s: Signal) { listeners.forEach((l) => l(s)); },
};
