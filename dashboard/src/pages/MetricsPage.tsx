import { useMemo } from "react";
import { Gauge } from "../components/Gauge";
import { LineChart, type LineSeries } from "../components/LineChart";
import { Cpu } from "../icons-phosphor";
import { isNpu } from "../protocol";
import { median, p95 } from "../reducer";
import { useStore } from "../store";

// validated categorical series colours (blue, orange-red, aqua, violet), one per stage; dashes are the backup channel
const C = { privacy: "#3987e5", detect: "#d95926", label: "#199e70", ocr: "#9085e9" };

function Funnel({ rows }: { rows: { label: string; n: number; alt?: boolean }[] }) {
  const max = Math.max(1, ...rows.map((r) => r.n));
  return (
    <div>
      {rows.map((r) => (
        <div className="hbar" key={r.label}>
          <span className="up">{r.label}</span>
          <div className="track"><div className={`fill ${r.alt ? "alt" : ""}`} style={{ width: `${(r.n / max) * 100}%` }} /></div>
          <span className="n">{r.n}</span>
        </div>
      ))}
    </div>
  );
}

export function MetricsPage() {
  const frames = useStore((s) => s.frames);
  const sysHist = useStore((s) => s.sysHist);
  const sys = useStore((s) => s.sys);
  const nodes = useStore((s) => s.nodes);

  const latency = useMemo(() => {
    const xs = frames.map((f) => f.t / 1000);
    const mk = (label: string, stage: keyof typeof C, dash: number[]): LineSeries => ({
      label, color: C[stage], dash, ys: frames.map((f) => (f.stages[stage] != null && f.stages[stage] >= 0 ? f.stages[stage] : null)),
    });
    return { xs, series: [mk("Privacy", "privacy", []), mk("Detect", "detect", [7, 4]), mk("Label", "label", [2, 3]), mk("Read text", "ocr", [10, 3, 2, 3])] };
  }, [frames]);

  const sysX = useMemo(() => sysHist.map((p) => p.t / 1000), [sysHist]);
  const load = useMemo<LineSeries[]>(() => [
    { label: "App CPU", color: C.privacy, dash: [], ys: sysHist.map((p) => p.s.cpu) },
    { label: "NPU busy (measured)", color: C.label, dash: [7, 4], ys: sysHist.map((p) => (p.s.ai ?? []).find(isNpu)?.busyPct ?? 0) },
  ], [sysHist]);
  const ram = useMemo<LineSeries[]>(() => [{ label: "App RAM", color: C.privacy, dash: [], ys: sysHist.map((p) => p.s.ramMb) }], [sysHist]);
  const temp = useMemo<LineSeries[]>(() => [{ label: "Battery temperature", color: C.detect, dash: [], ys: sysHist.map((p) => p.s.tempC) }], [sysHist]);

  const stageRows = ["gate", "privacy", "detect", "label", "ocr", "zone", "compose", "store", "ledger"].map((id) => ({ id, n: nodes[id] }));
  const npu = (sys?.ai ?? []).find(isNpu);
  const ramPct = sys ? (sys.sysUsedMb / Math.max(1, sys.sysTotalMb)) * 100 : null;

  return (
    <div className="grid">
      <section className="cell" aria-label="gauges">
        <h2><span>Live gauges</span><span className="dim">Updated every second</span></h2>
        <div className="gauges">
          <Gauge label="App CPU" value={sys?.cpu ?? null} max={100} unit="% of device" digits={1} sub={sys ? `${sys.threads} threads` : undefined} />
          <Gauge label="System RAM" value={ramPct} max={100} unit="% used" digits={0} sub={sys ? `Smriti uses ${sys.ramMb} MB` : undefined} />
          <Gauge label="NPU busy (measured)" value={npu ? npu.busyPct : null} max={100} unit="% of last 10 s" digits={0} sub={npu ? `${npu.name}: ${npu.lastMs} ms last call` : "No NPU model loaded"} />
          <Gauge label="Battery temp" value={sys?.tempC ?? null} max={60} unit="deg C" digits={1} warnAt={0.7} sub={sys ? `Thermal: ${sys.thermal}` : undefined} />
          <Gauge label="Battery" value={sys?.battery ?? null} max={100} unit="% charge" digits={0} warnAt={5} sub={sys ? `${sys.charging ? "Charging" : "On battery"}, ${sys.currentMa} mA` : undefined} />
        </div>
      </section>

      <section className="cell" aria-label="on-device AI engines">
        <h2><span>On-device AI engines</span><span className="dim">Measured by the phone, backend as loaded</span></h2>
        <div className="models-grid">
          {(sys?.ai ?? []).map((u) => (
            <div key={u.name} className="mcard">
              <div className="mhead">
                <span className="ic"><Cpu size={22} weight="duotone" /></span>
                <span style={{ minWidth: 0 }}>
                  <b>{u.name}</b>
                  <div className="dim" style={{ fontSize: 12 }}>{u.role}</div>
                </span>
                <span className={`pill ${u.state !== "ready" ? "red" : isNpu(u) ? "blue" : ""}`} style={{ marginLeft: "auto" }}>{u.state === "ready" ? u.backend : u.state}</span>
              </div>
              <div className="hbar" style={{ gridTemplateColumns: "90px 1fr 54px" }}>
                <span className="label">Busy 10 s</span>
                <div className="track"><div className="fill" style={{ width: `${Math.max(2, u.busyPct)}%` }} /></div>
                <span className="n">{u.busyPct.toFixed(0)}%</span>
              </div>
              <div className="mstats">
                <div><span className="label">Last</span><b>{u.lastMs}<span className="unit">ms</span></b></div>
                <div><span className="label">Average</span><b>{u.avgMs}<span className="unit">ms</span></b></div>
                <div><span className="label">Calls</span><b>{u.calls}</b></div>
              </div>
            </div>
          ))}
          {!(sys?.ai ?? []).length && <div className="empty">Waiting for the phone to report its engines.</div>}
        </div>
      </section>


      <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(400px, 1fr))" }}>
        <LineChart title="Stage latency per frame" unit="ms" xs={latency.xs} series={latency.series} />
        <LineChart title="Load" unit="%" xs={sysX} series={load} yMax={100} digits={1} />
        <LineChart title="App memory" unit="MB" xs={sysX} series={ram} baseline="auto" />
        <LineChart title="Battery temperature" unit="deg C" xs={sysX} series={temp} digits={1} baseline="auto" />

        <section className="cell" aria-label="frame funnel">
          <h2><span>Where frames go</span><span className="dim">Since start</span></h2>
          <Funnel rows={[
            { label: "Seen", n: sys?.seen ?? 0 },
            { label: "Gated out", n: sys?.gated ?? 0, alt: true },
            { label: "Dropped busy", n: sys?.dropped ?? 0, alt: true },
            { label: "Analysed", n: sys?.processed ?? 0 },
            { label: "Notes saved", n: sys?.notes ?? 0 },
          ]} />
          <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>Every skipped frame is work the models never had to do.</p>
        </section>

        <section className="cell" aria-label="network">
          <h2><span>Network: zero cloud</span><span className="pill blue">Local only</span></h2>
          <div className="kv-panel">
            <span className="label">Internet blocked</span><b className="tab-num">{sys?.blocked ?? 0} attempts refused</b>
            <span className="label">Local links</span><b className="tab-num">{sys?.lanConn ?? 0} (glasses on Wi-Fi)</b>
            <span className="label">Data sent</span><b className="tab-num">{sys ? Math.round(sys.tx / 1024) : 0} KB</b>
            <span className="label">Data received</span><b className="tab-num">{sys ? Math.round(sys.rx / 1024) : 0} KB</b>
            <span className="label">Browsers</span><b className="tab-num">{sys?.clients ?? 0} connected</b>
          </div>
          <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>Only your own glasses on your own Wi-Fi are allowed. Every other connection the app tries is refused and counted here.</p>
        </section>

        <section className="cell" aria-label="stage statistics" style={{ gridColumn: "1 / -1" }}>
          <h2><span>Stage statistics</span><span className="dim">Last 24 runs</span></h2>
          <table>
            <thead><tr><th>Stage</th><th className="num">Last</th><th className="num">Median</th><th className="num">P95</th><th className="num">Runs</th><th className="num">Errors</th></tr></thead>
            <tbody>
              {stageRows.map(({ id, n }) => (
                <tr key={id}>
                  <td className="up">{id}</td>
                  <td className="num">{n.ms || "-"}</td>
                  <td className="num">{median(n.history) || "-"}</td>
                  <td className="num">{p95(n.history) || "-"}</td>
                  <td className="num">{n.runs}</td>
                  <td className={`num ${n.errors ? "alert" : ""}`}>{n.errors}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>NPU busy is measured by the app: time spent inside Hexagon NPU inference over the last 10 s. Android exposes no NPU hardware counter to apps.</p>
        </section>
      </div>
    </div>
  );
}
