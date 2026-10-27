import { command } from "../link";
import { useStore } from "../store";

const STAGE_COLORS: Record<string, string> = { privacy: "#3987e5", detect: "#d95926", label: "#199e70", ocr: "#9085e9" };
const ORDER = ["privacy", "detect", "label", "ocr"];

export function FramesPage() {
  const frames = useStore((s) => s.frames);
  const gate = useStore((s) => s.gate);
  const preview = useStore((s) => s.preview);
  const previewOn = useStore((s) => s.hello?.config.preview ?? false);
  const conn = useStore((s) => s.conn);
  const latest = frames[frames.length - 1];
  const maxMs = Math.max(1, ...frames.map((f) => f.totalMs));

  return (
    <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(380px, 1fr))" }}>
      <section className="cell" aria-label="recent frames" style={{ gridColumn: "1 / -1" }}>
        <h2><span>Recent frames</span><span className="dim">Newest first. Bar = where the time went</span></h2>
        <div className="legend" aria-label="legend">
          {ORDER.map((s) => (
            <span key={s}><svg viewBox="0 0 30 8" aria-hidden="true"><rect x="0" y="0" width="30" height="8" rx="2" fill={STAGE_COLORS[s]} /></svg>{s}</span>
          ))}
        </div>
        <table>
          <thead><tr><th>#</th><th>Source</th><th className="num">Total</th><th style={{ width: "28%" }}>Time split</th><th>Note</th><th>Saved</th></tr></thead>
          <tbody>
            {[...frames].reverse().slice(0, 10).map((f) => (
              <tr key={f.frameId}>
                <td className="mono">{f.frameId}</td>
                <td className="up">{f.source}</td>
                <td className="num">{f.totalMs} ms</td>
                <td>
                  <div className="stackbar" style={{ width: `${Math.max(12, (f.totalMs / maxMs) * 100)}%` }} aria-label={ORDER.map((s) => `${s} ${f.stages[s] ?? 0} ms`).join(", ")}>
                    {ORDER.map((s) => (f.stages[s] ?? 0) > 0 ? <i key={s} style={{ flex: f.stages[s], background: STAGE_COLORS[s], marginRight: 2 }} title={`${s} ${f.stages[s]} ms`} /> : null)}
                  </div>
                </td>
                <td className={f.ok ? "" : "alert"} style={{ overflowWrap: "anywhere" }}>{f.caption}{f.faces ? <span className="pill blue" style={{ marginLeft: 8 }}>{f.faces} face blurred</span> : null}</td>
                <td>{f.stored ? <span className="pill blue">Saved</span> : <span className="pill dim">Skipped</span>}</td>
              </tr>
            ))}
            {!frames.length && <tr><td colSpan={6} className="dim">No frames yet. Move the camera to a new scene.</td></tr>}
          </tbody>
        </table>
      </section>

      <section className="cell" aria-label="frame inspector">
        <h2>
          <span>Frame inspector</span>
          <button className={`btn sm ${previewOn ? "on" : ""}`} aria-pressed={previewOn} disabled={conn === "simulated"} onClick={() => command("setPreview", { on: String(!previewOn) })}>
            Preview {previewOn ? "on" : "off"}
          </button>
        </h2>
        {previewOn && preview ? (
          <div className="preview-wrap">
            <div style={{ position: "relative" }}>
              <img className="preview-img" src={preview.url} alt={`Face-blurred frame ${preview.frameId}`} />
              {preview.header.boxes.map((b, i) => (
                <div key={i} className="box" style={{ left: `${b.x * 100}%`, top: `${b.y * 100}%`, width: `${b.w * 100}%`, height: `${b.h * 100}%` }}>
                  <span>{b.label} {Math.round(b.c * 100)}</span>
                </div>
              ))}
            </div>
            <div className="dim" style={{ marginTop: 8, fontSize: 12 }}>Frame {preview.frameId}. Faces were blurred before any model saw this. Held in memory only.</div>
          </div>
        ) : (
          <div className="empty">
            <b>{conn === "simulated" ? "Preview is not available in simulated mode." : previewOn ? "Waiting for the next analysed frame." : "Preview is off."}</b>
            <span>Off by default: only metadata leaves the phone. Turn it on to see the face-blurred frame with detection boxes.</span>
          </div>
        )}
        {latest && <p style={{ margin: "12px 0 0", overflowWrap: "anywhere" }}><span className="label">Latest note</span><br />{latest.caption}</p>}
      </section>

      <section className="cell" aria-label="gate reasons">
        <h2><span>Why frames were skipped</span><span className="dim">Change threshold {gate.threshold.toFixed(2)}</span></h2>
        {Object.keys(gate.reasons).length === 0 && <div className="empty">Nothing skipped yet.</div>}
        {Object.entries(gate.reasons).sort((a, b) => b[1] - a[1]).map(([k, n]) => {
          const max = Math.max(...Object.values(gate.reasons));
          return (
            <div className="hbar" key={k}>
              <span className="up">{k}</span>
              <div className="track"><div className={`fill ${k === "busy" ? "alt" : ""}`} style={{ width: `${(n / max) * 100}%` }} /></div>
              <span className="n">{n}</span>
            </div>
          );
        })}
        <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>
          Cooldown: too soon after the last frame. Static: the scene did not change. Busy: the models were still working, so the frame was dropped, never queued.
        </p>
        <p style={{ margin: "8px 0 0", fontSize: 12 }}>Accepted {gate.accepted} / rejected {gate.rejected}</p>
      </section>
    </div>
  );
}
