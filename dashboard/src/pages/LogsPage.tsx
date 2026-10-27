import { useMemo, useRef, useState } from "react";
import { StageIcon } from "../components/icons";
import { STAGE_ROLE } from "../graph";
import { useStore } from "../store";

type Level = "debug" | "info" | "warn" | "error";
const LEVELS: Level[] = ["debug", "info", "warn", "error"];
const ICON_FOR: Record<string, string> = {
  camera: "camera", gate: "gate", privacy: "privacy", detect: "detect", label: "label", ocr: "ocr", zone: "zone", compose: "compose",
  store: "store", ledger: "ledger", mic: "mic", speech: "speech", intent: "intent", retrieve: "retrieve", answer: "answer", voice: "voice",
};

export function LogsPage() {
  const logs = useStore((s) => s.logs);
  const [levels, setLevels] = useState<Set<Level>>(new Set(LEVELS));
  const [tag, setTag] = useState("all");
  const [q, setQ] = useState("");
  const [paused, setPaused] = useState(false);
  const frozen = useRef(logs);
  if (!paused) frozen.current = logs;
  const source = frozen.current;

  const counts = useMemo(() => {
    const c: Record<Level, number> = { debug: 0, info: 0, warn: 0, error: 0 };
    for (const l of source) c[l.level]++;
    return c;
  }, [source]);
  const tags = useMemo(() => ["all", ...Array.from(new Set(source.map((l) => l.tag))).sort()], [source]);
  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return source
      .filter((l) => levels.has(l.level) && (tag === "all" || l.tag === tag) && (!needle || (l.message + l.tag + l.model).toLowerCase().includes(needle)))
      .slice(-400)
      .reverse();
  }, [source, levels, tag, q]);

  const toggle = (l: Level) => setLevels((cur) => { const n = new Set(cur); if (n.has(l)) n.delete(l); else n.add(l); return n; });
  const exportJson = () => {
    const blob = new Blob([JSON.stringify(shown, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `smriti-log-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div className="cell">
      <h2>
        <span>Event log</span>
        <span className="dim">{shown.length} of {source.length} rows {paused ? "(paused)" : "(live)"}</span>
      </h2>
      <div className="field">
        {LEVELS.map((l) => (
          <button key={l} className={`btn sm ${levels.has(l) ? "on" : ""}`} aria-pressed={levels.has(l)} onClick={() => toggle(l)}>
            {l} <span className="tab-num">{counts[l]}</span>
          </button>
        ))}
        <label className="dim up" style={{ display: "inline-flex", gap: 8, alignItems: "center", fontSize: 12 }}>
          Stage
          <select value={tag} onChange={(e) => setTag(e.target.value)}>{tags.map((t) => <option key={t} value={t}>{t}</option>)}</select>
        </label>
        <input type="text" aria-label="search the log" placeholder="Search stage, model or message" value={q} onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 300 }} />
        <button className={`btn sm ${paused ? "on" : ""}`} aria-pressed={paused} onClick={() => setPaused((p) => !p)}>{paused ? "Resume" : "Pause"}</button>
        <button className="btn sm" onClick={exportJson}>Export JSON</button>
      </div>
      <div className="log-list" role="log" aria-live="off">
        <div className="log-head"><span>Time</span><span>Level</span><span>Stage</span><span>Model</span><span>Message</span><span style={{ textAlign: "right" }}>Time ms</span><span style={{ textAlign: "right" }}>Frame</span></div>
        {shown.map((l) => (
          <div key={l.id} className={`log-row ${l.level}`}>
            <span className="tab-num dim">{new Date(l.t).toLocaleTimeString([], { hour12: false })}</span>
            <span className="lv">{l.level}</span>
            <span className="stg">{ICON_FOR[l.tag] ? <StageIcon id={ICON_FOR[l.tag]} size={15} weight="bold" /> : null}{l.tag}</span>
            <span className="mdl" title={l.model}>{STAGE_ROLE[l.tag] ? l.model || "-" : ""}</span>
            <span className="msg">{l.message}</span>
            <span className="ms tab-num">{l.ms ? l.ms : ""}</span>
            <span className="fid tab-num">{l.frameId ? `#${l.frameId}` : ""}</span>
          </div>
        ))}
        {!shown.length && <div className="empty">No rows match these filters.</div>}
      </div>
    </div>
  );
}
