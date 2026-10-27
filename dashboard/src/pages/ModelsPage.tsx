import { useState } from "react";
import { Cube, ShieldCheck } from "@phosphor-icons/react";
import { StageIcon } from "../components/icons";
import { command } from "../link";
import { median, p95 } from "../reducer";
import { useStore } from "../store";
import type { ModelSpec } from "../protocol";

const ROLES: { role: ModelSpec["role"]; title: string; stage: string; icon: string; blurb: string }[] = [
  { role: "DETECTOR", title: "Object detector", stage: "detect", icon: "detect", blurb: "Finds objects and draws boxes." },
  { role: "LABELER", title: "Image labeler", stage: "label", icon: "label", blurb: "Names what is in the scene." },
  { role: "OCR", title: "Text reader", stage: "ocr", icon: "ocr", blurb: "Reads signs, screens and labels." },
  { role: "CAPTIONER", title: "NPU captioner", stage: "compose", icon: "compose", blurb: "Rich sentences on the Hexagon NPU." },
];

const PRESETS = [
  { id: "fast", label: "Fast", desc: "Labels only. Lowest heat and latency." },
  { id: "balanced", label: "Balanced", desc: "Objects, labels and text." },
  { id: "rich", label: "Rich", desc: "Adds the NPU captioner once installed." },
];

export function ModelsPage() {
  const hello = useStore((s) => s.hello);
  const nodes = useStore((s) => s.nodes);
  const tripped = useStore((s) => s.tripped);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState("");

  const run = async (name: string, args: Record<string, string> = {}) => {
    setBusy(name + JSON.stringify(args));
    const r = await command(name, args);
    setNote(`${r.ok ? "Done" : "Failed"}: ${r.message}`);
    setBusy(null);
  };

  if (!hello) return <div className="cell empty">Waiting for the phone to introduce itself...</div>;
  const cfg = hello.config;

  return (
    <div className="grid">
      <section className="cell" aria-label="pipeline preset">
        <h2><span>Pipeline preset</span><span className="pill blue">{cfg.preset}</span></h2>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 10 }} role="radiogroup" aria-label="preset">
          {PRESETS.map((p) => (
            <button key={p.id} role="radio" aria-checked={cfg.preset === p.id} className="mopt" style={{ gridTemplateColumns: "22px 1fr" }} disabled={busy !== null} onClick={() => cfg.preset !== p.id && run("setPreset", { preset: p.id })}>
              <span className="radio" />
              <span><b>{p.label}</b><br /><span className="dim" style={{ fontSize: 12 }}>{p.desc}</span></span>
            </button>
          ))}
        </div>
        <div className="field" style={{ marginTop: 12 }}>
          <span className="pill dim"><ShieldCheck size={14} weight="fill" /> Face blur: always on, cannot be changed</span>
          {note && <span className="pill blue" role="status">{note}</span>}
        </div>
      </section>

      <div className="models-grid">
        {ROLES.map(({ role, title, stage, icon, blurb }) => {
          const models = hello.models.filter((m) => m.role === role);
          const n = nodes[stage];
          const stageOn = stage === "detect" ? cfg.detectOn : stage === "label" ? cfg.labelOn : stage === "ocr" ? cfg.ocrOn : true;
          const isTripped = tripped.includes(stage);
          const active = models.find((m) => m.selected);
          return (
            <section className="cell mcard" key={role} aria-label={title}>
              <div className="mhead">
                <span className="ic">{role === "CAPTIONER" ? <Cube size={22} weight="duotone" /> : <StageIcon id={icon} size={22} />}</span>
                <span style={{ flex: 1 }}>
                  <b style={{ fontSize: 17, letterSpacing: "0.08em", textTransform: "uppercase" }}>{title}</b><br />
                  <span className="dim" style={{ fontSize: 12 }}>{blurb}</span>
                </span>
                {role !== "CAPTIONER" && (
                  <span style={{ display: "flex", gap: 6 }}>
                    {isTripped && <button className="btn sm alert" disabled={busy !== null} onClick={() => run("rearm", { stage })}>Re-arm</button>}
                    <button className={`btn sm ${stageOn ? "on" : ""}`} aria-pressed={stageOn} disabled={busy !== null} onClick={() => run("toggleStage", { stage, on: String(!stageOn) })}>{stageOn ? "On" : "Off"}</button>
                  </span>
                )}
              </div>

              {role !== "CAPTIONER" && (
                <div className="mstats" aria-label="latency">
                  <div><span className="label">Last</span><b className="tab-num">{n.ms || "-"}<span className="unit">ms</span></b></div>
                  <div><span className="label">Median</span><b className="tab-num">{median(n.history) || "-"}<span className="unit">ms</span></b></div>
                  <div><span className="label">P95</span><b className="tab-num">{p95(n.history) || "-"}<span className="unit">ms</span></b></div>
                </div>
              )}

              <div role="radiogroup" aria-label={`${title} model`} style={{ display: "grid", gap: 8 }}>
                {models.map((m) => (
                  <button key={m.id} role="radio" aria-checked={m.selected} className="mopt" disabled={!m.available || busy !== null} onClick={() => !m.selected && run("setModel", { id: m.id })}>
                    <span className="radio" />
                    <span><b>{m.name}</b><br /><span className="dim" style={{ fontSize: 12 }}>{m.note}</span></span>
                    <span className={`pill ${m.available ? "dim" : "red"}`}>{m.available ? m.backend.split(" (")[0] : "Not installed"}</span>
                  </button>
                ))}
              </div>
              {isTripped && <p className="alert" style={{ margin: 0 }}>This stage tripped after repeated failures. Re-arm to try again.</p>}
              {!isTripped && active && <p className="dim" style={{ margin: 0, fontSize: 12 }}>Active: {active.name}</p>}
            </section>
          );
        })}
      </div>
    </div>
  );
}
