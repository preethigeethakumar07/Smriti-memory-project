import { useEffect, useMemo, useRef, useState } from "react";
import uPlot from "uplot";
import { cssVar } from "../theme";

export interface LineSeries {
  label: string;
  /** validated categorical colour (see palette check); never the iQOO stripe colours */
  color: string;
  /** dash pattern is the secondary encoding, so identity never depends on colour alone */
  dash: number[];
  ys: (number | null)[];
}

interface Props {
  title: string;
  unit: string;
  xs: number[];            // epoch seconds
  series: LineSeries[];
  yMax?: number;           // fixed scale (e.g. 100 for percentages)
  height?: number;
  digits?: number;
  /** "zero" anchors the axis at 0 (counts, latency, percentages); "auto" zooms to the data (temperature, memory) */
  baseline?: "zero" | "auto";
}

const fmtTime = (s: number) => new Date(s * 1000).toLocaleTimeString([], { hour12: false });
const rgba = (hex: string, a: number) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

export function LineChart({ title, unit, xs, series, yMax, height = 190, digits = 0, baseline = "zero" }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const tip = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot | null>(null);
  const [table, setTable] = useState(false);
  const multi = series.length >= 2;

  // keep the latest data reachable from uPlot's hooks without recreating the chart
  const latest = useRef({ xs, series, digits, unit });
  latest.current = { xs, series, digits, unit };

  const data = useMemo(() => [xs, ...series.map((s) => s.ys)] as uPlot.AlignedData, [xs, series]);

  useEffect(() => {
    const el = box.current;
    if (!el || plot.current) return;
    // colours come from the stylesheet's current theme (the page is re-keyed when the theme changes, so this runs again)
    const axis = { stroke: cssVar("--dim", "#8b96a9"), font: "11px JetBrains Mono", grid: { stroke: cssVar("--grid", "#1a2230"), width: 1 }, ticks: { stroke: cssVar("--edge", "#263042"), width: 1 } };
    const surface = cssVar("--panel", "#0e131b");
    const ink = cssVar("--text", "#fff");
    const opts: uPlot.Options = {
      width: el.clientWidth || 400,
      height,
      legend: { show: false },
      padding: [12, 14, 0, 0],
      cursor: { x: true, y: false, points: { size: 10, width: 2, fill: surface, stroke: ink }, drag: { x: false, y: false } },
      scales: {
        x: { time: true },
        y: {
          range: (_u, min, max) => {
            if (baseline === "auto") {
              const pad = Math.max(1, (max - min) * 0.3);
              return [Math.floor(min - pad), Math.ceil(max + pad)];
            }
            return [0, yMax ?? Math.max(1, max * 1.15)];
          },
        },
      },
      axes: [{ ...axis, space: 70 }, { ...axis, size: 48 }],
      series: [
        {},
        ...series.map((s) => ({
          label: s.label,
          stroke: s.color,
          width: 2.5,
          dash: s.dash,
          fill: (u: uPlot) => {
            const g = u.ctx.createLinearGradient(0, u.bbox.top, 0, u.bbox.top + u.bbox.height);
            g.addColorStop(0, rgba(s.color, series.length > 1 ? 0.14 : 0.3));
            g.addColorStop(1, rgba(s.color, 0));
            return g;
          },
          spanGaps: false,
          // end marker (>= 8px) on the newest point only, ringed in the surface colour
          points: { show: true, size: 10, width: 2, stroke: surface, fill: s.color, filter: (u: uPlot) => [Math.max(0, u.data[0].length - 1)] },
        })),
      ],
      hooks: {
        setCursor: [
          (u) => {
            const t = tip.current;
            if (!t) return;
            const i = u.cursor.idx;
            if (i == null || i < 0) { t.style.display = "none"; return; }
            const { xs: X, series: S, digits: d, unit: un } = latest.current;
            const rows = S.map((s) => `<span style="color:${s.color}">&#9632;</span> ${s.label}: <b>${s.ys[i] == null ? "-" : (s.ys[i] as number).toFixed(d)}</b> ${un}`);
            t.innerHTML = `<b>${fmtTime(X[i])}</b><br>${rows.join("<br>")}`;
            t.style.display = "block";
            const left = Math.min((u.cursor.left ?? 0) + 14, (box.current?.clientWidth ?? 300) - 170);
            t.style.left = `${left}px`;
            t.style.top = `${Math.max(0, (u.cursor.top ?? 0) - 10)}px`;
          },
        ],
      },
    };
    plot.current = new uPlot(opts, data, el);
    const ro = new ResizeObserver(() => plot.current?.setSize({ width: el.clientWidth, height }));
    ro.observe(el);
    return () => { ro.disconnect(); plot.current?.destroy(); plot.current = null; };
    // chart is created once; data flows through setData below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { plot.current?.setData(data); }, [data]);

  const last = (s: LineSeries) => {
    for (let i = s.ys.length - 1; i >= 0; i--) if (s.ys[i] != null) return (s.ys[i] as number).toFixed(digits);
    return "-";
  };
  const rows = Math.min(12, xs.length);

  return (
    <section className="cell" aria-label={title}>
      <h2>
        <span>{title} <span className="dim">({unit})</span></span>
        <button className="btn sm" aria-pressed={table} onClick={() => setTable((t) => !t)}>{table ? "Chart" : "Table"}</button>
      </h2>
      {multi && (
        <div className="legend" aria-label="legend">
          {series.map((s) => (
            <span key={s.label}>
              <svg viewBox="0 0 30 8" aria-hidden="true"><line x1="0" y1="4" x2="30" y2="4" stroke={s.color} strokeWidth="3" strokeDasharray={s.dash.join(" ")} /></svg>
              {s.label} <b className="tab-num">{last(s)}</b>
            </span>
          ))}
        </div>
      )}
      <div className="chart-box" style={{ display: table ? "none" : "block" }}>
        <div ref={box} />
        <div ref={tip} className="tip" />
        {xs.length < 2 && <div className="dim up" style={{ padding: "8px 0" }}>Waiting for data</div>}
      </div>
      {table && (
        <table aria-label={`${title} values`}>
          <thead><tr><th>Time</th>{series.map((s) => <th key={s.label} className="num">{s.label}</th>)}</tr></thead>
          <tbody>
            {Array.from({ length: rows }, (_, k) => xs.length - 1 - k).map((i) => (
              <tr key={i}><td className="mono">{fmtTime(xs[i])}</td>{series.map((s) => <td key={s.label} className="num">{s.ys[i] == null ? "-" : (s.ys[i] as number).toFixed(digits)}</td>)}</tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
