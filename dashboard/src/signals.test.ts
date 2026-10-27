import { describe, expect, it } from "vitest";
import type { PipelineEvent } from "./protocol";
import { initialNodes } from "./reducer";
import { eventToSignals } from "./signals";
import { EDGES, INCOMING, NODES, NODE_H, NODE_W, STAGE_H, STAGE_W, anchor, curve } from "./graph";

const ev = (p: Partial<PipelineEvent>): PipelineEvent => ({ type: "STAGE", t: 0, frameId: 1, durMs: 0, message: "", data: {}, ...p });

describe("eventToSignals", () => {
  it("starts a frame at the camera", () => {
    const s = eventToSignals(ev({ type: "FRAME_START" }), initialNodes());
    expect(s).toEqual([{ kind: "hop", edge: "camera>gate", tone: "frame", frameId: 1 }]);
  });

  it("sends a packet along the incoming edge when a stage starts", () => {
    const s = eventToSignals(ev({ stage: "privacy", status: "START" }), initialNodes());
    expect(s).toEqual([{ kind: "hop", edge: "gate>privacy", tone: "frame", frameId: 1 }]);
  });

  it("only rejoins at zone from branches that produced output", () => {
    const nodes = initialNodes();
    nodes.detect = { ...nodes.detect, status: "ok" };
    nodes.label = { ...nodes.label, status: "ok" };
    nodes.ocr = { ...nodes.ocr, status: "skipped" };
    const s = eventToSignals(ev({ stage: "zone", status: "START" }), nodes);
    expect(s.map((x) => (x as any).edge).sort()).toEqual(["detect>zone", "label>zone"]);
  });

  it("marks memory reads on the way back", () => {
    const s = eventToSignals(ev({ type: "QUERY", stage: "retrieve", status: "START" }), initialNodes());
    const reads = s.filter((x) => x.kind === "hop" && x.tone === "read").map((x) => (x as any).edge).sort();
    expect(reads).toEqual(["ledger>retrieve", "store>retrieve"]);
  });

  it("drops frames at the gate according to the aggregated counts", () => {
    const s = eventToSignals(ev({ type: "GATE", status: "SKIPPED", data: { counts: { busy: 2, static: 3 } } }), initialNodes());
    expect(s).toEqual([{ kind: "drop", reason: "busy", count: 2 }, { kind: "drop", reason: "static", count: 3 }]);
  });

  it("maps a model swap to the node it feeds, and errors to faults", () => {
    expect(eventToSignals(ev({ type: "MODEL_SWAP", stage: "labeler" }), initialNodes())).toEqual([{ kind: "swap", stage: "label" }]);
    expect(eventToSignals(ev({ stage: "ocr", status: "ERROR" }), initialNodes())).toEqual([{ kind: "fault", stage: "ocr" }]);
  });
});

describe("graph geometry", () => {
  it("every edge connects existing nodes and every INCOMING edge exists", () => {
    const ids = new Set(NODES.map((n) => n.id));
    for (const e of EDGES) {
      expect(ids.has(e.from), e.id).toBe(true);
      expect(ids.has(e.to), e.id).toBe(true);
    }
    const edgeIds = new Set(EDGES.map((e) => e.id));
    for (const list of Object.values(INCOMING)) for (const id of list) expect(edgeIds.has(id), id).toBe(true);
  });

  it("curves start and end exactly on the node anchors", () => {
    const a = anchor("gate", "r");
    const b = anchor("privacy", "l");
    const d = curve(a, "r", b, "l");
    expect(d.startsWith(`M${a.x},${a.y} C`)).toBe(true);
    expect(d.endsWith(` ${b.x},${b.y}`)).toBe(true);
    const e = EDGES.find((x) => x.id === "gate>privacy")!;
    expect(e.d).toBe(d);
  });

  it("no two nodes overlap and all sit inside the stage", () => {
    for (const a of NODES) {
      expect(a.x + NODE_W).toBeLessThanOrEqual(STAGE_W);
      expect(a.y + NODE_H).toBeLessThanOrEqual(STAGE_H);
      for (const b of NODES) {
        if (a === b) continue;
        const overlap = a.x < b.x + NODE_W && b.x < a.x + NODE_W && a.y < b.y + NODE_H && b.y < a.y + NODE_H;
        expect(overlap, `${a.id} overlaps ${b.id}`).toBe(false);
      }
    }
  });
});
