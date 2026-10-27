import { useMemo, useState } from "react";
import { Compass, Crosshair, MapTrifold, Pulse, Timer } from "../icons-phosphor";
import { command } from "../link";
import type { XY } from "../protocol";
import { useStore } from "../store";

const W = 760;
const H = 540;
const PAD = 56;
const WALKED = "#3B8EEA";
const BACK = "#2FBF71";
const ROUTE = "#F2A93B";
const STEPS = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000];

const CUE_TEXT: Record<string, string> = {
  STRAIGHT: "Keep going straight", LEFT: "Turn left", RIGHT: "Turn right", SLIGHT_LEFT: "Bear left", SLIGHT_RIGHT: "Bear right",
  U_TURN: "Turn around", ARRIVED: "You have arrived", OFF_ROUTE: "Off the path", NO_HEADING: "Finding direction",
};
const MODE_TEXT: Record<string, string> = { IDLE: "Ready", RECORDING: "Remembering the walk", GUIDING: "Guiding" };

const metres = (m: number) => (m >= 1000 ? `${(m / 1000).toFixed(m % 1000 === 0 ? 0 : 1)} km` : `${Math.round(m)} m`);

/** arrow marks along a line: a point and the direction (screen radians) every ~spacing px */
function arrowMarks(line: XY[], toScreen: (p: XY) => XY, spacingPx: number): { x: number; y: number; a: number }[] {
  const out: { x: number; y: number; a: number }[] = [];
  let carried = spacingPx / 2;
  for (let i = 1; i < line.length; i++) {
    const [x0, y0] = toScreen(line[i - 1]);
    const [x1, y1] = toScreen(line[i]);
    const len = Math.hypot(x1 - x0, y1 - y0);
    if (len < 0.5) continue;
    const a = Math.atan2(y1 - y0, x1 - x0);
    let d = spacingPx - carried;
    while (d <= len) { out.push({ x: x0 + Math.cos(a) * d, y: y0 + Math.sin(a) * d, a }); d += spacingPx; }
    carried = len - (d - spacingPx);
  }
  return out;
}

function Kpi({ icon, value, label, alert }: { icon: React.ReactNode; value: string; label: string; alert?: boolean }) {
  return (
    <div className="kpi">
      <span className="ic" style={alert ? { color: "var(--red)" } : undefined}>{icon}</span>
      <span><div className="n" style={{ fontSize: 22 }}>{value}</div><div className="l">{label}</div></span>
    </div>
  );
}

/**
 * The walk guide, live from the phone: the path as a plan (metres from where the walk began; no map tiles, the phone never goes online).
 * Blue is where you walked, green where you have walked since the guide began leading back, amber the way you are being led.
 */
export function WalkPage() {
  const move = useStore((s) => s.move);
  const saved = useStore((s) => s.moveSaved);
  const [pick, setPick] = useState<number | null>(null);
  const [zoom, setZoom] = useState(1);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");

  const tr = move?.trail;
  const liveHasPath = !!tr && (tr.out.length > 1 || tr.back.length > 1 || tr.route.length > 1 || !!tr.here);
  const walk = pick != null ? saved.walks.find((w) => w.id === pick) : undefined;
  const showSaved = !!walk && !(move?.active && liveHasPath);

  const out: XY[] = showSaved ? walk!.points : tr?.out ?? [];
  const back: XY[] = showSaved ? [] : tr?.back ?? [];
  const route: XY[] = showSaved ? [] : tr?.route ?? [];
  const here = showSaved ? null : tr?.here ?? null;
  const start = showSaved ? walk!.points[0] ?? null : tr?.start ?? null;
  const dest = showSaved ? null : tr?.dest ?? null;
  const heading = showSaved ? null : tr?.heading ?? null;
  const sigma = showSaved ? 0 : tr?.sigma ?? 0;

  const view = useMemo(() => {
    const all: XY[] = [...out, ...back, ...route];
    if (here) all.push(here);
    if (start) all.push(start);
    if (dest) all.push(dest);
    if (!all.length) return { s: 1, cx: 0, cy: 0 };
    const xs = all.map((p) => p[0]), ys = all.map((p) => p[1]);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const spanX = Math.max(maxX - minX, 40), spanY = Math.max(maxY - minY, 40);
    const s = Math.max(Math.min((W - 2 * PAD) / spanX, (H - 2 * PAD) / spanY), 1e-6);
    return { s, cx: (minX + maxX) / 2, cy: (minY + maxY) / 2 };
  }, [out, back, route, here, start, dest]);

  const scale = view.s * zoom;
  const toScreen = (p: XY): XY => [W / 2 + (p[0] - view.cx) * scale, H / 2 - (p[1] - view.cy) * scale];
  const poly = (l: XY[]) => l.map((p) => toScreen(p).map((v) => v.toFixed(1)).join(",")).join(" ");

  const gridM = STEPS.find((s) => s * scale >= 70) ?? STEPS[STEPS.length - 1];
  const barM = [...STEPS].reverse().find((s) => s * scale <= 160) ?? STEPS[0];
  const x0 = view.cx - W / 2 / scale, x1 = view.cx + W / 2 / scale, y0 = view.cy - H / 2 / scale, y1 = view.cy + H / 2 / scale;
  const vlines: number[] = [], hlines: number[] = [];
  for (let v = Math.ceil(x0 / gridM) * gridM; v <= x1 && vlines.length < 60; v += gridM) vlines.push(v);
  for (let v = Math.ceil(y0 / gridM) * gridM; v <= y1 && hlines.length < 60; v += gridM) hlines.push(v);

  const arrow = (m: { x: number; y: number; a: number }, color: string, k: string) => (
    <polygon key={k} points="-7,-5 7,0 -7,5 -3,0" fill={color} transform={`translate(${m.x.toFixed(1)} ${m.y.toFixed(1)}) rotate(${((m.a * 180) / Math.PI).toFixed(1)})`} />
  );

  const run = async (name: string, args: Record<string, string> = {}) => {
    setBusy(true);
    try { const r = await command(name, args); setMsg(r.message); } catch (e) { setMsg(String(e)); } finally { setBusy(false); }
  };

  const st = move?.state;
  const cue = st?.cue ? CUE_TEXT[st.cue] ?? st.cue : "";
  const empty = !out.length && !back.length && !route.length && !here;

  return (
    <div>
      <div className="kpis" style={{ marginTop: 0, marginBottom: 14 }}>
        <Kpi icon={<Pulse size={22} weight="duotone" />} value={move?.demo ? "Demo" : MODE_TEXT[st?.mode ?? "IDLE"] ?? st?.mode ?? "-"} label={st?.title ? `Following: ${st.title}` : "Walk guide"} />
        <Kpi icon={<MapTrifold size={22} weight="duotone" />} value={st?.arrived ? "Arrived" : st?.remainingM ? metres(st.remainingM) : "-"} label="Distance left" />
        <Kpi icon={<Timer size={22} weight="duotone" />} value={st ? `${st.steps} steps, ${metres(st.walkedM)}` : "-"} label="Walked this trip" />
        <Kpi icon={<Crosshair size={22} weight="duotone" />} value={st?.gpsM != null ? `GPS ${st.gpsM} m` : "no GPS"} label="Position source" alert={st != null && st.gpsM == null && !!move?.active} />
        <Kpi icon={<Compass size={22} weight="duotone" />} value={st?.headingDeg != null ? `${st.headingDeg}°` : "-"} label={st?.compassPoor ? "Heading (compass poor)" : "Heading (0 = North)"} alert={!!st?.compassPoor} />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "minmax(320px, 800px) 1fr", gap: 14 }}>
        <section className="cell" aria-label="walk path map">
          <h2>
            <span>{showSaved ? `Saved walk: ${walk!.name}` : "Path map"}</span>
            <span className="dim">Metres from where the walk began. No map tiles: the phone never goes online.</span>
          </h2>
          <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="the walked path with arrows" style={{ background: "transparent" }}>
            {vlines.map((v) => <line key={`v${v}`} x1={toScreen([v, 0])[0]} x2={toScreen([v, 0])[0]} y1={0} y2={H} style={{ stroke: "var(--edge-2)" }} strokeOpacity={v === 0 ? 0.9 : 0.4} strokeDasharray={v === 0 ? undefined : "3 6"} />)}
            {hlines.map((v) => <line key={`h${v}`} y1={toScreen([0, v])[1]} y2={toScreen([0, v])[1]} x1={0} x2={W} style={{ stroke: "var(--edge-2)" }} strokeOpacity={v === 0 ? 0.9 : 0.4} strokeDasharray={v === 0 ? undefined : "3 6"} />)}

            {out.length > 1 && <polyline points={poly(out)} fill="none" stroke={WALKED} strokeWidth={4} strokeLinejoin="round" strokeLinecap="round" />}
            {back.length > 1 && <polyline points={poly(back)} fill="none" stroke={BACK} strokeWidth={4} strokeLinejoin="round" strokeLinecap="round" />}
            {route.length > 1 && <polyline points={poly(route)} fill="none" stroke={ROUTE} strokeWidth={3} strokeDasharray="10 7" strokeLinejoin="round" />}
            {arrowMarks(out, toScreen, 70).map((m, i) => arrow(m, WALKED, `ao${i}`))}
            {arrowMarks(back, toScreen, 70).map((m, i) => arrow(m, BACK, `ab${i}`))}
            {arrowMarks(route, toScreen, 70).map((m, i) => arrow(m, ROUTE, `ar${i}`))}

            {start && (() => { const [x, y] = toScreen(start); return <g><circle cx={x} cy={y} r={7} fill="#2FBF71" stroke="var(--bg, #0b0f17)" strokeWidth={2} /><text x={x + 11} y={y - 9} fontSize={12} style={{ fill: "var(--dim-2)" }}>Start</text></g>; })()}
            {dest && (() => { const [x, y] = toScreen(dest); return <g><circle cx={x} cy={y} r={8} fill="#e4262c" stroke="var(--bg, #0b0f17)" strokeWidth={2} /><text x={x + 11} y={y - 9} fontSize={12} style={{ fill: "var(--dim-2)" }}>{tr?.destLabel || "Destination"}</text></g>; })()}
            {here && (() => {
              const [x, y] = toScreen(here);
              const r = Math.min(Math.max(sigma * scale, 0), 160);
              return (
                <g>
                  {r > 8 && <circle cx={x} cy={y} r={r} fill={WALKED} fillOpacity={0.12} stroke={WALKED} strokeOpacity={0.4} />}
                  <circle cx={x} cy={y} r={9} fill={WALKED} stroke="#fff" strokeWidth={2.5} />
                  {heading != null && <polygon points="0,-20 7,-8 -7,-8" fill="#fff" stroke={WALKED} strokeWidth={1.5} transform={`translate(${x} ${y}) rotate(${heading})`} />}
                </g>
              );
            })()}

            {empty && <text x={W / 2} y={H / 2} textAnchor="middle" fontSize={16} style={{ fill: "var(--dim)" }}>No path yet. Start a walk on the phone, or press Demo walk.</text>}

            {/* north arrow, scale bar */}
            <g transform={`translate(${W - 38} 44)`}>
              <polygon points="0,-24 8,8 0,2 -8,8" style={{ fill: "var(--red)" }} />
              <text y={24} textAnchor="middle" fontSize={13} fontWeight={700} style={{ fill: "var(--dim-2)" }}>N</text>
            </g>
            <g transform={`translate(24 ${H - 26})`}>
              <line x1={0} x2={barM * scale} y1={0} y2={0} style={{ stroke: "var(--dim-2)" }} strokeWidth={3} />
              <line x1={0} x2={0} y1={-6} y2={6} style={{ stroke: "var(--dim-2)" }} strokeWidth={2} />
              <line x1={barM * scale} x2={barM * scale} y1={-6} y2={6} style={{ stroke: "var(--dim-2)" }} strokeWidth={2} />
              <text x={barM * scale + 10} y={4} fontSize={12} style={{ fill: "var(--dim-2)" }}>{metres(barM)}</text>
            </g>
            <text x={W - 14} y={H - 14} textAnchor="end" fontSize={11} style={{ fill: "var(--dim)" }}>grid {metres(gridM)}</text>
          </svg>

          <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center", marginTop: 8 }}>
            <span><i style={{ display: "inline-block", width: 18, height: 4, background: WALKED, verticalAlign: "middle", marginRight: 6 }} />Walked</span>
            <span><i style={{ display: "inline-block", width: 18, height: 4, background: BACK, verticalAlign: "middle", marginRight: 6 }} />Walking back</span>
            <span><i style={{ display: "inline-block", width: 18, height: 0, borderTop: `3px dashed ${ROUTE}`, verticalAlign: "middle", marginRight: 6 }} />The way to go</span>
            <span style={{ flex: 1 }} />
            <button className="btn" onClick={() => setZoom((z) => Math.max(z / 1.5, 0.25))}>Zoom out</button>
            <button className="btn" onClick={() => setZoom((z) => Math.min(z * 1.5, 12))}>Zoom in</button>
            <button className="btn" onClick={() => setZoom(1)}>Fit</button>
          </div>
        </section>

        <div style={{ display: "grid", gap: 14, alignContent: "start" }}>
          <section className="cell" aria-label="guidance">
            <h2><span>Guidance now</span></h2>
            <div style={{ font: "700 26px/1.2 var(--ui)", marginBottom: 6 }}>{st?.phrase || cue || (st?.arrived ? "You have arrived" : "Waiting")}</div>
            {cue && st?.phrase && <div className="dim">{cue}{st && st.errDeg ? `, ${Math.abs(st.errDeg)}° to the ${st.errDeg > 0 ? "right" : "left"}` : ""}</div>}
            {st?.note && <div className="dim" style={{ marginTop: 6 }}>{st.note}</div>}
            <div style={{ display: "flex", gap: 8, marginTop: 12, flexWrap: "wrap" }}>
              <button className="btn primary" disabled={busy || !!move?.demo || !!move?.active} onClick={() => run("moveDemo")}>Demo walk</button>
              <button className="btn" disabled={busy || !(move?.demo || move?.active)} onClick={() => run("moveStop")}>{move?.demo ? "Stop demo" : "Stop walk"}</button>
            </div>
            {msg && <div className="dim" style={{ marginTop: 8 }}>{msg}</div>}
            {move?.demo && <p className="dim" style={{ marginBottom: 0 }}>A simulated person: the phone does not move, nothing is saved.</p>}
          </section>

          <section className="cell" aria-label="saved places">
            <h2><span>Places</span><span className="dim">{saved.places.length}</span></h2>
            {saved.places.length === 0 && <div className="dim">No saved places yet.</div>}
            {saved.places.map((p) => <div key={p.name} style={{ display: "flex", justifyContent: "space-between", padding: "4px 0" }}><span>{p.kind === "home" ? "Home: " : ""}{p.name}</span><span className="dim">within {p.radiusM} m</span></div>)}
          </section>

          <section className="cell" aria-label="saved walks">
            <h2><span>Saved walks</span><span className="dim">click to draw</span></h2>
            {saved.walks.length === 0 && <div className="dim">No saved walks yet.</div>}
            {saved.walks.map((w) => (
              <button key={w.id} className="btn" style={{ display: "flex", width: "100%", justifyContent: "space-between", marginBottom: 6, borderColor: pick === w.id ? "var(--blue)" : undefined }} onClick={() => { setPick(pick === w.id ? null : w.id); setZoom(1); }}>
                <span>{w.name}</span><span className="dim">{metres(w.lengthM)}{w.hasGps ? ", GPS" : ", steps only"}</span>
              </button>
            ))}
          </section>
        </div>
      </div>
    </div>
  );
}
