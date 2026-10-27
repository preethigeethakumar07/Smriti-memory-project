import type { CmdResult, Hello, PipelineEvent, PreviewHeader, ServerMessage } from "./protocol";
import { Simulator } from "./sim";
import { beginReplay, ingest, setCmdSettler, startFlushLoop, useStore } from "./store";

/**
 * One place that owns "where the data comes from". Tries the phone first (the page is normally served
 * by the phone, so the WebSocket is on the same host); if there is no phone it falls back to the
 * clearly-labelled simulator so the page is never an empty box.
 */

type Pending = { resolve: (r: CmdResult) => void; timer: ReturnType<typeof setTimeout> };

let ws: WebSocket | null = null;
let sim: Simulator | null = null;
let mode: "live" | "sim" | "none" = "none";
let closedByUs = false;
let attempts = 0;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let pingTimer: ReturnType<typeof setInterval> | null = null;
let fresh = true;
let lastHeader: PreviewHeader | null = null;
const pending = new Map<string, Pending>();
let cmdSeq = 0;

export const linkMode = () => mode;

function wsUrl() {
  const q = new URLSearchParams(location.search);
  const override = q.get("ws");
  if (override) return override;
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws`;
}

export function start() {
  startFlushLoop();
  setCmdSettler(settle);
  const q = new URLSearchParams(location.search);
  if (q.get("sim") === "1") return startSim();
  connectLive(true);
}

function connectLive(first: boolean) {
  closedByUs = false;
  useStore.getState().setConn(first ? "connecting" : "reconnecting");
  let opened = false;
  const socket = new WebSocket(wsUrl());
  socket.binaryType = "arraybuffer";
  ws = socket;

  // if nothing answers quickly on the first try, show the simulator instead of an empty screen
  const giveUp = first ? setTimeout(() => { if (!opened) { socket.close(); startSim(); } }, 2500) : null;

  socket.onopen = () => {
    opened = true;
    if (giveUp) clearTimeout(giveUp);
    mode = "live";
    attempts = 0;
    fresh = true;
    useStore.getState().setConn("live");
    pingTimer = setInterval(() => socket.readyState === 1 && socket.send(JSON.stringify({ type: "ping", t: performance.now() })), 2000);
    const pin = sessionStorage.getItem("smriti-pin");
    if (pin) socket.send(JSON.stringify({ type: "auth", pin }));
  };

  socket.onmessage = (ev) => {
    if (typeof ev.data !== "string") return onBinary(ev.data as ArrayBuffer);
    let m: ServerMessage;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === "pong") { useStore.getState().setLatency(Math.round(performance.now() - (m as { t: number }).t)); return; }
    if (m.type === "authRequired") { useStore.getState().setConn("authRequired"); return; }
    if (m.type === "authFailed") { useStore.getState().setConn("authFailed", "Wrong PIN"); sessionStorage.removeItem("smriti-pin"); return; }
    if (m.type === "hello" && fresh) {
      fresh = false;
      useStore.getState().reset();
      beginReplay();
      useStore.getState().setConn("live");
    }
    if (m.type === "preview") lastHeader = m;
    ingest(m);
  };

  socket.onclose = () => {
    if (pingTimer) clearInterval(pingTimer);
    if (giveUp) clearTimeout(giveUp);
    if (mode === "sim" || closedByUs) return;
    if (!opened && first) return;          // the give-up timer starts the simulator
    scheduleReconnect();
  };
  socket.onerror = () => { /* onclose follows */ };
}

function scheduleReconnect() {
  attempts++;
  const wait = Math.min(1000 * attempts, 5000);
  useStore.getState().setConn("reconnecting", `retry in ${Math.round(wait / 1000)} s`);
  reconnectTimer = setTimeout(() => connectLive(false), wait);
}

/** First 8 bytes: frame id (big endian). The rest is a JPEG of the already face-blurred frame. */
function onBinary(buf: ArrayBuffer) {
  if (buf.byteLength < 9) return;
  const frameId = Number(new DataView(buf).getBigInt64(0));
  const blob = new Blob([buf.slice(8)], { type: "image/jpeg" });
  const header = lastHeader;
  if (!header) return;
  useStore.getState().setPreview({ frameId, url: URL.createObjectURL(blob), header, at: Date.now() });
}

export function startSim() {
  if (ws) { closedByUs = true; try { ws.close(); } catch { /* ignore */ } }
  if (reconnectTimer) clearTimeout(reconnectTimer);
  mode = "sim";
  useStore.getState().reset();
  useStore.getState().setConn("simulated", "no phone found, showing generated data");
  beginReplay(0);
  sim?.stop();
  sim = new Simulator(ingest);
  sim.start();
}

export function retryLive() {
  sim?.stop(); sim = null;
  mode = "none";
  useStore.getState().reset();
  connectLive(true);
}

export function submitPin(pin: string) {
  sessionStorage.setItem("smriti-pin", pin);
  ws?.readyState === 1 && ws.send(JSON.stringify({ type: "auth", pin }));
}

/** Sends a command to the phone (or the simulator) and resolves with its answer. */
export function command(name: string, args: Record<string, string> = {}): Promise<CmdResult> {
  const id = `c${++cmdSeq}`;
  useStore.getState().logCmd({ id, name, args, ok: null, message: "sent", at: Date.now() });
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      const r: CmdResult = { type: "cmdResult", id, name, ok: false, message: "no answer (timed out)" };
      useStore.getState().resolveCmd(r);
      resolve(r);
    }, 20000);
    pending.set(id, { resolve, timer });
    if (mode === "sim") sim?.command(id, name, args);
    else if (ws?.readyState === 1) ws.send(JSON.stringify({ type: "cmd", id, name, args }));
    else {
      clearTimeout(timer);
      pending.delete(id);
      const r: CmdResult = { type: "cmdResult", id, name, ok: false, message: "not connected" };
      useStore.getState().resolveCmd(r);
      resolve(r);
    }
  });
}

/** Called for every cmdResult coming back, to settle the promise returned by command(). */
export function settle(r: CmdResult) {
  const p = pending.get(r.id);
  if (!p) return;
  clearTimeout(p.timer);
  pending.delete(r.id);
  p.resolve(r);
}

export type { Hello, PipelineEvent };
