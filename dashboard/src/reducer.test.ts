import { describe, expect, it } from "vitest";
import type { PipelineEvent } from "./protocol";
import { FrameAccumulator, HISTORY, emptyGate, initialNodes, median, p95, reduceGate, reduceNodes, toLog } from "./reducer";

const ev = (p: Partial<PipelineEvent>): PipelineEvent => ({ type: "STAGE", t: 0, frameId: 1, durMs: 0, message: "", data: {}, ...p });

describe("reduceNodes", () => {
  it("moves a node from running to ok and records the duration", () => {
    let n = initialNodes();
    n = reduceNodes(n, ev({ stage: "label", status: "START" }), 1000);
    expect(n.label.status).toBe("running");
    expect(n.label.startedAt).toBe(1000);
    n = reduceNodes(n, ev({ stage: "label", status: "OK", durMs: 120, message: "3 label(s)" }), 1120);
    expect(n.label.status).toBe("ok");
    expect(n.label.ms).toBe(120);
    expect(n.label.history).toEqual([120]);
    expect(n.label.runs).toBe(1);
  });

  it("counts errors and keeps the message", () => {
    const n = reduceNodes(initialNodes(), ev({ stage: "ocr", status: "ERROR", message: "boom", durMs: 4 }), 0);
    expect(n.ocr.status).toBe("error");
    expect(n.ocr.errors).toBe(1);
    expect(n.ocr.message).toBe("boom");
  });

  it("shows tripped and skipped states", () => {
    let n = reduceNodes(initialNodes(), ev({ stage: "detect", status: "DISABLED", message: "tripped after 3 failures" }), 0);
    expect(n.detect.status).toBe("disabled");
    n = reduceNodes(n, ev({ stage: "detect", status: "SKIPPED", message: "turned off" }), 0);
    expect(n.detect.status).toBe("skipped");
  });

  it("caps history and ignores unknown stages and other event types", () => {
    let n = initialNodes();
    for (let i = 1; i <= HISTORY + 10; i++) n = reduceNodes(n, ev({ stage: "gate", status: "OK", durMs: i }), 0);
    expect(n.gate.history.length).toBe(HISTORY);
    expect(n.gate.history[HISTORY - 1]).toBe(HISTORY + 10);
    const same = reduceNodes(n, ev({ stage: "nonsense", status: "OK" }), 0);
    expect(same).toBe(n);
    const sys = reduceNodes(n, ev({ type: "SYSTEM", stage: "gate", status: "OK" }), 0);
    expect(sys).toBe(n);
  });
});

describe("gate node", () => {
  it("is never shown as skipped or off just because it rejected frames", () => {
    let n = reduceNodes(initialNodes(), ev({ type: "GATE", stage: "gate", status: "OK", message: "changed" }), 0);
    n = reduceNodes(n, ev({ type: "GATE", stage: "gate", status: "SKIPPED", message: "static x4", data: { total: 4, counts: { static: 4 } } }), 0);
    expect(n.gate.status).toBe("ok");
    expect(n.gate.message).toContain("4 skipped");
  });
});

describe("reduceGate", () => {
  it("counts accepted frames and aggregates rejection reasons", () => {
    let g = emptyGate();
    g = reduceGate(g, ev({ type: "GATE", status: "OK", data: { score: 0.3, threshold: 0.08, reason: "changed" } }));
    g = reduceGate(g, ev({ type: "GATE", status: "SKIPPED", data: { counts: { cooldown: 2, busy: 1 }, score: 0.01, threshold: 0.08 } }));
    g = reduceGate(g, ev({ type: "GATE", status: "SKIPPED", data: { counts: { cooldown: 3 } } }));
    expect(g.accepted).toBe(1);
    expect(g.rejected).toBe(6);
    expect(g.reasons).toEqual({ cooldown: 5, busy: 1 });
  });
});

describe("FrameAccumulator", () => {
  it("builds one row per frame with stage durations", () => {
    const a = new FrameAccumulator();
    a.handle(ev({ type: "FRAME_START", frameId: 7, data: { source: "phone" } }));
    a.handle(ev({ frameId: 7, stage: "privacy", status: "OK", durMs: 300 }));
    a.handle(ev({ frameId: 7, stage: "label", status: "OK", durMs: 110 }));
    const row = a.handle(ev({ type: "FRAME_END", frameId: 7, durMs: 700, status: "OK", message: "I see a cup.", data: { stored: true, errors: 0, faces: 1, zone: "desk" } }));
    expect(row).not.toBeNull();
    expect(row!.stages).toEqual({ privacy: 300, label: 110 });
    expect(row!.totalMs).toBe(700);
    expect(row!.stored).toBe(true);
    expect(row!.faces).toBe(1);
    expect(row!.source).toBe("phone");
  });

  it("ignores events without a frame id", () => {
    const a = new FrameAccumulator();
    expect(a.handle(ev({ type: "FRAME_END", frameId: 0 }))).toBeNull();
  });
});

describe("toLog", () => {
  it("keeps routine stage traffic as debug rows so the log always shows work", () => {
    const row = toLog(ev({ stage: "ocr", status: "OK", durMs: 390, message: "12 chars" }), 1, (s) => (s === "ocr" ? "ML Kit text recognizer" : ""));
    expect(row?.level).toBe("debug");
    expect(row?.ms).toBe(390);
    expect(row?.model).toBe("ML Kit text recognizer");
    const det = toLog(ev({ stage: "detect", status: "OK", durMs: 90, message: "3 object(s)", data: { labels: ["cup", "book"] } }), 2);
    expect(det?.message).toContain("cup, book");
    expect(toLog(ev({ type: "FRAME_END", frameId: 3, durMs: 700, data: { stored: true, errors: 0 } }), 2)?.message).toContain("note saved");
    expect(toLog(ev({ stage: "ocr", status: "OK", message: "re-armed" }), 3)).toBeNull();
  });

  it("turns errors and trips into error rows and ignores 'still tripped' repeats", () => {
    expect(toLog(ev({ stage: "ocr", status: "ERROR", message: "boom" }), 1)?.level).toBe("error");
    expect(toLog(ev({ stage: "ocr", status: "DISABLED", message: "tripped after 3 failures" }), 2)?.level).toBe("error");
    expect(toLog(ev({ stage: "ocr", status: "DISABLED", message: "tripped, waiting for re-arm" }), 3)).toBeNull();
  });

  it("maps LOG levels", () => {
    expect(toLog(ev({ type: "LOG", stage: "engine", message: "ready", data: { level: "warn" } }), 1)?.level).toBe("warn");
    expect(toLog(ev({ type: "LOG", message: "x", data: {} }), 1)?.level).toBe("info");
  });
});

describe("stats", () => {
  it("median and p95", () => {
    expect(median([5, 1, 3])).toBe(3);
    expect(median([])).toBe(0);
    expect(p95([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])).toBe(9);
  });
});
