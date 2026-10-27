import { useState } from "react";
import { Bug, ChatCircleText, Eye, Plugs, Trash, Warning } from "@phosphor-icons/react";
import { command, linkMode } from "../link";
import { useStore } from "../store";

export function AdminPage() {
  const hello = useStore((s) => s.hello);
  const cmdLog = useStore((s) => s.cmdLog);
  const conn = useStore((s) => s.conn);
  const latency = useStore((s) => s.latencyMs);
  const [busy, setBusy] = useState<string | null>(null);
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState("");
  const [minutes, setMinutes] = useState("5");

  const run = async (name: string, args: Record<string, string> = {}) => {
    setBusy(name);
    const r = await command(name, args);
    setBusy(null);
    return r;
  };

  if (!hello) return <div className="cell empty">Waiting for the phone to introduce itself...</div>;
  const cfg = hello.config;
  const isDebug = hello.app.debug;

  return (
    <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(360px, 1fr))" }}>
      <section className="cell" aria-label="capture controls">
        <h2><span>Capture</span></h2>
        <div className="field">
          <button className={`btn ${cfg.memoryOn ? "on" : ""}`} aria-pressed={cfg.memoryOn} disabled={busy !== null} onClick={() => run("setMemory", { on: String(!cfg.memoryOn) })}>
            Memory {cfg.memoryOn ? "on" : "off"}
          </button>
          <button className={`btn ${cfg.preview ? "on" : ""}`} aria-pressed={cfg.preview} disabled={conn === "simulated" || busy !== null} onClick={() => run("setPreview", { on: String(!cfg.preview) })}>
            <Eye size={16} weight="bold" /> Frame preview {cfg.preview ? "on" : "off"}
          </button>
        </div>
        <p className="dim" style={{ margin: 0, fontSize: 12 }}>
          Preview streams only the face-blurred frame, from memory, never stored. The phone shows a badge while it is on.
        </p>
      </section>

      <section className="cell" aria-label="actions">
        <h2><span>Actions</span></h2>
        <div className="field">
          <button className="btn primary" disabled={busy !== null} onClick={async () => setAnswer((await run("describe")).message)}><Eye size={16} weight="bold" /> What do you see</button>
        </div>
        <div className="field">
          <input type="text" aria-label="question" placeholder="Ask: where are my keys?" value={question} onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && question.trim()) run("ask", { text: question }).then((r) => setAnswer(r.message)); }} />
          <button className="btn" disabled={busy !== null || !question.trim()} onClick={async () => setAnswer((await run("ask", { text: question })).message)}><ChatCircleText size={16} weight="bold" /> Ask</button>
        </div>
        <div className="field">
          <span className="label">Forget last</span>
          <input type="number" min={1} max={1440} value={minutes} onChange={(e) => setMinutes(e.target.value)} style={{ width: 80 }} aria-label="minutes" />
          <span className="dim">min</span>
          <button className="btn alert" disabled={busy !== null} onClick={async () => setAnswer((await run("forget", { minutes })).message)}><Trash size={16} weight="bold" /> Forget</button>
        </div>
        {answer && <p style={{ margin: "10px 0 0", overflowWrap: "anywhere" }} role="status"><span className="label">Result</span><br />{answer}</p>}
      </section>

      <section className="cell" aria-label="connection">
        <h2><span>Connection</span><span className={`pill ${conn === "live" ? "blue" : conn === "simulated" ? "red" : "dim"}`}>{conn}</span></h2>
        <div className="kv-panel">
          <span className="label">Transport</span><b>{hello.transport.toUpperCase()}</b>
          <span className="label">Round trip</span><b className="tab-num">{conn === "live" ? `${latency} ms` : "-"}</b>
          <span className="label">Device</span><b>{hello.device.summary}</b>
          <span className="label">Chip</span><b>{hello.device.soc}</b>
          <span className="label">App</span><b>{hello.app.name} {hello.app.version}{hello.app.debug ? " (debug)" : ""}</b>
          <span className="label">Session</span><b className="tab-num">#{hello.session.id}</b>
        </div>
        <p className="dim" style={{ margin: "10px 0 0", fontSize: 12 }}><Plugs size={14} style={{ verticalAlign: "-2px" }} /> This page is served by the phone. Nothing here goes through the internet.</p>
      </section>

      {isDebug && (
        <section className="cell" aria-label="fault injection">
          <h2><span>Fault injection</span><span className="pill red"><Bug size={13} weight="fill" /> Debug build only</span></h2>
          <p className="dim" style={{ marginTop: 0, fontSize: 12 }}>
            Prove the pipeline survives a broken stage: after 3 failures in a row the stage trips and the rest keep running. Re-arm it on the Models page.
          </p>
          <div className="field">
            {["detect", "label", "ocr", "privacy"].map((st) => (
              <button key={st} className="btn sm alert" disabled={busy !== null} onClick={() => run("fault", { stage: st, on: "true" })}><Warning size={13} weight="fill" /> Break {st}</button>
            ))}
            <button className="btn sm" disabled={busy !== null} onClick={() => run("clearFaults")}>Clear all</button>
          </div>
          <p className="dim" style={{ margin: 0, fontSize: 12 }}>Privacy fails closed: the frame is discarded and no model sees it.</p>
        </section>
      )}

      <section className="cell" aria-label="command history" style={{ gridColumn: "1 / -1" }}>
        <h2><span>Command history</span><span className="dim">{linkMode() === "sim" ? "Simulated" : "Live"}</span></h2>
        <table>
          <thead><tr><th>Time</th><th>Command</th><th>Result</th></tr></thead>
          <tbody>
            {[...cmdLog].reverse().slice(0, 10).map((c) => (
              <tr key={c.id}>
                <td className="mono">{new Date(c.at).toLocaleTimeString([], { hour12: false })}</td>
                <td className="up">{c.name} <span className="mono dim">{Object.entries(c.args).map(([k, v]) => `${k}=${v}`).join(" ")}</span></td>
                <td className={c.ok === false ? "alert" : ""}>{c.ok === null ? "Waiting..." : `${c.ok ? "OK" : "Failed"}: ${c.message}`}</td>
              </tr>
            ))}
            {!cmdLog.length && <tr><td colSpan={3} className="dim">No commands sent yet.</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
