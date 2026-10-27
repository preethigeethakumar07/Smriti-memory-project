/**
 * Speedometer-style gauge. A 240 degree arc with a tri-stripe-coloured fill that turns red near the limit.
 * `value` is shown as text too, so the reading never depends on the arc alone.
 */
interface Props {
  label: string;
  value: number | null;
  /** value at the end of the arc */
  max: number;
  unit: string;
  digits?: number;
  /** fraction of max above which the arc turns red */
  warnAt?: number;
  sub?: string;
}

const R = 70;
const CX = 100;
const CY = 100;
const START = 150;   // degrees, measured clockwise from 3 o'clock
const SWEEP = 240;

function polar(deg: number) {
  const a = (deg * Math.PI) / 180;
  return { x: CX + R * Math.cos(a), y: CY + R * Math.sin(a) };
}

function arc(from: number, to: number) {
  const a = polar(from);
  const b = polar(to);
  const large = to - from > 180 ? 1 : 0;
  return `M${a.x.toFixed(2)},${a.y.toFixed(2)} A${R},${R} 0 ${large} 1 ${b.x.toFixed(2)},${b.y.toFixed(2)}`;
}

export function Gauge({ label, value, max, unit, digits = 0, warnAt = 0.85, sub }: Props) {
  const frac = value == null ? 0 : Math.min(1, Math.max(0, value / max));
  const end = START + SWEEP * frac;
  const hot = frac >= warnAt;
  const ticks = Array.from({ length: 13 }, (_, i) => START + (SWEEP * i) / 12);

  return (
    <div className="gauge" role="img" aria-label={`${label}: ${value == null ? "no data" : value.toFixed(digits)} ${unit}`}>
      <svg viewBox="0 0 200 175">
        <path d={arc(START, START + SWEEP)} fill="none" style={{ stroke: "var(--gauge-track)" }} strokeWidth="14" strokeLinecap="round" />
        {ticks.map((t, i) => {
          const o = { x: CX + (R + 13) * Math.cos((t * Math.PI) / 180), y: CY + (R + 13) * Math.sin((t * Math.PI) / 180) };
          const n = { x: CX + (R + (i % 3 === 0 ? 7 : 9)) * Math.cos((t * Math.PI) / 180), y: CY + (R + (i % 3 === 0 ? 7 : 9)) * Math.sin((t * Math.PI) / 180) };
          return <line key={i} x1={n.x} y1={n.y} x2={o.x} y2={o.y} style={{ stroke: "var(--gauge-tick)" }} strokeWidth={i % 3 === 0 ? 2 : 1} />;
        })}
        {frac > 0.004 && (
          <path d={arc(START, end)} fill="none" stroke={hot ? "#e4262c" : "url(#gaugeGrad)"} strokeWidth="14" strokeLinecap="round" style={{ filter: `drop-shadow(0 0 6px ${hot ? "rgba(228,38,44,.7)" : "rgba(74,168,255,.6)"})` }} />
        )}
        <defs>
          <linearGradient id="gaugeGrad" x1="0" x2="1" y1="0" y2="0">
            <stop offset="0" stopColor="#1b3fa0" /><stop offset="1" stopColor="#4aa8ff" />
          </linearGradient>
        </defs>
        <text className="g-val" x={CX} y={CY + 6} textAnchor="middle">{value == null ? "-" : value.toFixed(digits)}</text>
        <text className="g-unit" x={CX} y={CY + 26} textAnchor="middle">{unit.toUpperCase()}</text>
      </svg>
      <div className="g-label">{label}</div>
      {sub && <div className="dim" style={{ fontSize: 11, marginTop: 2 }}>{sub}</div>}
    </div>
  );
}
