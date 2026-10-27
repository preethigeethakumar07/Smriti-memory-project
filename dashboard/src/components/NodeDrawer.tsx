import { useState } from "react";
import { NODES, STAGE_ROLE } from "../graph";
import { command } from "../link";
import { median, p95 } from "../reducer";
import { useStore } from "../store";
import { StageIcon } from "./icons";

export function NodeDrawer({ stage, onClose }: { stage: string; onClose: () => void }) {
  const node = NODES.find((n) => n.id === stage);
  const st = useStore((s) => s.nodes[stage]);
  const hello = useStore((s) => s.hello);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  if (!node || !st) return null;

  const role = STAGE_ROLE[stage];
  const candidates = role ? (hello?.models ?? []).filter((m) => m.role === role) : [];
  const active = candidates.find((m) => m.selected);
  const max = Math.max(1, ...st.history);
  const cfgKey = stage === "detect" ? "detectOn" : stage === "label" ? "labelOn" : "ocrOn";
  const on = hello ? Boolean((hello.config as unknown as Record<string, unknown>)[cfgKey]) : true;
  const tripped = st.status === "disabled" || st.status === "error";

  const run = async (name: string, args: Record<string, string>) => {
    setBusy(true);
    const r = await command(name, args);
    setNote(`${r.ok ? "Done" : "Failed"}: ${r.message}`);
    setBusy(false);
  };

  return (
    <aside className="drawer" aria-label={`${node.title} details`}>
      <div className="cell-title"><span style={{ display: "flex", gap: 8, alignItems: "center" }}><StageIcon id={node.icon} size={18} /> Stage detail</span><button className="btn sm" onClick={onClose}>Close</button></div>
      <h3>{node.title}</h3>
      <div className="dim">{active ? active.name : node.sub}</div>

      <div className="kv">
        <span className="k">Status</span><span className="up">{st.status}</span>
        <span className="k">Last</span><span className="tab-num">{st.ms || "-"} ms</span>
        <span className="k">Median</span><span className="tab-num">{median(st.history) || "-"} ms</span>
        <span className="k">P95</span><span className="tab-num">{p95(st.history) || "-"} ms</span>
        <span className="k">Runs</span><span className="tab-num">{st.runs}</span>
        <span className="k">Errors</span><span className={`tab-num ${st.errors ? "alert" : ""}`}>{st.errors}</span>
        <span className="k">Frame</span><span className="tab-num">{st.frameId || "-"}</span>
        <span className="k">Output</span><span style={{ overflowWrap: "anywhere" }}>{st.message || "-"}</span>
      </div>

      <div className="label" style={{ marginTop: 10 }}>Last {st.history.length} runs (ms)</div>
      <div className="bars" aria-label="recent durations">
        {st.history.map((v, i) => <i key={i} style={{ height: `${Math.max(4, (v / max) * 100)}%` }} title={`${v} ms`} />)}
      </div>

      {role && (
        <>
          <div className="label" style={{ marginTop: 16 }}>Model</div>
          <div role="radiogroup" aria-label="model choice" style={{ display: "grid", gap: 8, marginTop: 8 }}>
            {candidates.map((m) => (
              <button key={m.id} type="button" role="radio" aria-checked={m.selected} className="mopt" disabled={!m.available || busy}
                onClick={() => !m.selected && run("setModel", { id: m.id })}>
                <span className="radio" />
                <span><b>{m.name}</b><br /><span className="dim" style={{ fontSize: 12 }}>{m.note}</span></span>
                <span className="pill dim">{m.available ? m.backend.split(" (")[0] : "Not installed"}</span>
              </button>
            ))}
          </div>
          <div className="field">
            <button className="btn" disabled={busy} onClick={() => run("toggleStage", { stage, on: String(!on) })}>{on ? "Turn off" : "Turn on"}</button>
            {tripped && <button className="btn alert" disabled={busy} onClick={() => run("rearm", { stage })}>Re-arm</button>}
          </div>
        </>
      )}

      {!role && tripped && (
        <div className="field"><button className="btn alert" disabled={busy} onClick={() => run("rearm", { stage })}>Re-arm</button></div>
      )}

      {stage === "privacy" && (
        <p className="dim" style={{ marginTop: 16 }}>
          This step cannot be turned off or swapped. If it fails, the frame is discarded: no model sees it and nothing is stored.
        </p>
      )}
      {note && <p style={{ marginTop: 12 }} role="status" className="pill blue">{note}</p>}
    </aside>
  );
}
