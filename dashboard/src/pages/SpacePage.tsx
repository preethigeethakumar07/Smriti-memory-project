import { Compass, Crosshair, Cube, Pulse, ShieldCheck } from "../icons-phosphor";
import { useStore } from "../store";

const ago = (t: number) => {
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  return s < 60 ? `${s}s ago` : `${Math.round(s / 60)} min ago`;
};

const SENSOR_TEXT: Record<string, string> = {
  FULL: "Rotation vector (gyroscope + accelerometer + compass)",
  COMPASS: "Accelerometer + compass",
  GRAVITY_ONLY: "Accelerometer only: pitch is real, heading is not available",
  NONE: "No orientation sensor",
};

function Kpi({ icon, value, label, alert }: { icon: React.ReactNode; value: string; label: string; alert?: boolean }) {
  return (
    <div className="kpi">
      <span className="ic" style={alert ? { color: "var(--red)" } : undefined}>{icon}</span>
      <span><div className="n" style={{ fontSize: 22 }}>{value}</div><div className="l">{label}</div></span>
    </div>
  );
}

const SIZE = 520;
const C = SIZE / 2;

/**
 * Top-down room view, North up. The phone is the centre, the blue wedge is where the camera is pointing right now
 * (live from the phone's orientation sensors), and each dot is an object placed in the room: label plus x, y, z.
 */
export function SpacePage() {
  const space = useStore((s) => s.space);
  const sys = useStore((s) => s.sys) as any;
  const objs = Object.values(space.objects).sort((a, b) => b.lastSeen - a.lastSeen);

  const az: number = space.pose?.az ?? (sys?.poseAz >= 0 ? sys?.poseAz : -1);
  const pitch: number = space.pose?.pitch ?? sys?.posePitch ?? 0;
  const valid: boolean = space.pose?.valid ?? !!sys?.poseValid;
  const sensor: string = sys?.poseSensor ?? "-";

  const maxD = Math.max(3, ...objs.map((o) => Math.hypot(o.x, o.y)), 1);
  const scale = (C - 34) / (Math.ceil(maxD / 1) * 1);
  const rings = [1, 2, 3, 5, 8].filter((r) => r <= Math.ceil(maxD)).concat(Math.ceil(maxD)).filter((v, i, a) => a.indexOf(v) === i);

  const wedge = (centre: number, half: number, r: number) => {
    const a1 = ((centre - half) * Math.PI) / 180, a2 = ((centre + half) * Math.PI) / 180;
    const p = (a: number) => `${C + r * Math.sin(a)},${C - r * Math.cos(a)}`;
    return `M${C},${C} L${p(a1)} A${r},${r} 0 0 1 ${p(a2)} Z`;
  };

  return (
    <div>
      <div className="kpis" style={{ marginTop: 0, marginBottom: 14 }}>
        <Kpi icon={<Compass size={22} weight="duotone" />} value={valid && az >= 0 ? `${Math.round(az)}°` : "no compass"} label="Heading (0 = North)" alert={!valid} />
        <Kpi icon={<Crosshair size={22} weight="duotone" />} value={`${pitch >= 0 ? "+" : ""}${pitch.toFixed(0)}°`} label="Pitch (camera up / down)" />
        <Kpi icon={<Cube size={22} weight="duotone" />} value={String(objs.length)} label="Objects in the room graph" />
        <Kpi icon={<Pulse size={22} weight="duotone" />} value={SENSOR_TEXT[sensor] ? sensor.replace("_", " ").toLowerCase() : "-"} label="Orientation sensor" />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "minmax(320px, 540px) 1fr", gap: 14 }}>
        <section className="cell" aria-label="room radar">
          <h2><span>Room view, top down</span><span className="dim">North up. Positions are estimates in metres.</span></h2>
          <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width="100%" role="img" aria-label="objects around the phone">
            <defs>
              <radialGradient id="rg" cx="50%" cy="50%" r="50%"><stop offset="0%" stopColor="#4aa8ff" stopOpacity="0.2" /><stop offset="100%" stopColor="#4aa8ff" stopOpacity="0" /></radialGradient>
              <linearGradient id="cone" x1="0" y1="1" x2="0" y2="0"><stop offset="0%" stopColor="#4aa8ff" stopOpacity="0.55" /><stop offset="100%" stopColor="#4aa8ff" stopOpacity="0.04" /></linearGradient>
            </defs>
            <circle cx={C} cy={C} r={C - 8} fill="url(#rg)" />
            {rings.map((r) => (
              <g key={r}>
                <circle cx={C} cy={C} r={r * scale} fill="none" style={{ stroke: "var(--edge-2)" }} strokeDasharray="4 6" />
                <text x={C + 4} y={C - r * scale - 3} style={{ fill: "var(--dim)" }} fontSize="11">{r} m</text>
              </g>
            ))}
            {["N", "E", "S", "W"].map((l, i) => (
              <text key={l} x={C + (C - 14) * Math.sin((i * Math.PI) / 2)} y={C - (C - 14) * Math.cos((i * Math.PI) / 2) + 4} style={{ fill: l === "N" ? "var(--red)" : "var(--dim)" }} fontSize="13" fontWeight="700" textAnchor="middle">{l}</text>
            ))}

            {az >= 0 && valid && <path d={wedge(az, 27, C - 30)} fill="url(#cone)" stroke="#4aa8ff" strokeOpacity="0.6" />}

            {objs.map((o) => {
              const age = Date.now() - o.lastSeen;
              const op = Math.max(0.25, 1 - age / 120000);
              const px = C + o.x * scale, py = C - o.y * scale;
              return (
                <g key={o.name} opacity={op}>
                  <circle cx={px} cy={py} r={9} fill="#4aa8ff" fillOpacity="0.22" />
                  <circle cx={px} cy={py} r={4.5} fill={o.conf >= 0.5 ? "#4aa8ff" : "#e4262c"} style={{ stroke: "var(--panel)" }} strokeWidth="1.5" />
                  <text x={px + 9} y={py + 4} style={{ fill: "var(--text)" }} fontSize="12" fontWeight="600">{o.name}</text>
                  <text x={px + 9} y={py + 17} style={{ fill: "var(--dim)" }} fontSize="10">{o.z >= 0 ? "+" : ""}{o.z.toFixed(1)} m up</text>
                </g>
              );
            })}

            <circle cx={C} cy={C} r={8} style={{ fill: "var(--panel)", stroke: "var(--text)" }} strokeWidth="2" />
            <circle cx={C} cy={C} r={3} fill="#4aa8ff" />
            {!valid && <text x={C} y={SIZE - 14} fill="#e4262c" fontSize="12" textAnchor="middle">This phone has no compass: positions below are camera-relative.</text>}
          </svg>
        </section>

        <section className="cell" aria-label="scene object graph">
          <h2><span><ShieldCheck size={16} weight="bold" style={{ verticalAlign: "-3px", marginRight: 6 }} />3D scene object graph</span><span className="dim">label + xyz, from this phone only</span></h2>
          <table>
            <thead><tr><th>Object</th><th className="num">x (E)</th><th className="num">y (N)</th><th className="num">z (up)</th><th className="num">Dist</th><th className="num">Bearing</th><th>Basis</th><th>Seen</th></tr></thead>
            <tbody>
              {objs.slice(0, 30).map((o) => (
                <tr key={o.name}>
                  <td><b>{o.name}</b></td>
                  <td className="num mono">{o.x.toFixed(2)}</td><td className="num mono">{o.y.toFixed(2)}</td><td className="num mono">{o.z.toFixed(2)}</td>
                  <td className="num mono">{o.dist.toFixed(1)} m</td>
                  <td className="num mono">{valid ? `${Math.round(o.az)}°` : "-"}</td>
                  <td><span className={`pill ${o.conf >= 0.5 ? "blue" : "red"}`}>{o.conf >= 0.5 ? "size" : "guess"}</span></td>
                  <td className="dim">{ago(o.lastSeen)}</td>
                </tr>
              ))}
              {!objs.length && <tr><td colSpan={8} className="dim">Nothing placed yet. Point the camera at a few objects and move the phone slowly.</td></tr>}
            </tbody>
          </table>
          <div className="pipeline-strip" aria-label="how positions are made">
            {["Camera frame", "Phone orientation (measured)", "Ray through the box", "Distance from object size (estimated)", "x, y, z in the room", "Tracker keeps identity", "Navigation arrow"].map((s, i, a) => (
              <span key={s} className="step">{s}{i < a.length - 1 && <i>→</i>}</span>
            ))}
          </div>
          <p className="dim" style={{ margin: "10px 0 0", fontSize: 12 }}>
            Orientation is measured by the phone's sensors. Distance is an estimate from the size of the object's box against the size such an
            object usually has, so a "guess" row (red) has no known size. This is not a full 3D scan; it is a sensor-based map of where things were.
          </p>
        </section>
      </div>
    </div>
  );
}
