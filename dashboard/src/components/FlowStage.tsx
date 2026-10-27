import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { EDGES, LANE_RULE_Y, NODES, NODE_H, NODE_W, STAGE_H, STAGE_W, edgeById, swapPaths, type GraphNode } from "../graph";
import { flowBus, type Signal } from "../signals";
import { useStore } from "../store";
import type { NodeState } from "../reducer";
import { Gear, StageIcon } from "./icons";
import { NodeDrawer } from "./NodeDrawer";

const NS = "http://www.w3.org/2000/svg";
const reduceMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function pathLength(d: string): number {
  const p = document.createElementNS(NS, "path");
  p.setAttribute("d", d);
  return p.getTotalLength();
}

/**
 * A glowing "energy" packet: one bright head and a short fading tail of dots, travelling along an SVG path.
 * Created imperatively so a busy stream never re-renders React.
 */
function travel(layer: SVGGElement, d: string, ms: number, cls: string, delay = 0) {
  const dots = [
    { r: 6, op: 1, lag: 0 },
    { r: 4.5, op: 0.6, lag: 70 },
    { r: 3.2, op: 0.35, lag: 140 },
    { r: 2.2, op: 0.18, lag: 210 },
  ];
  for (const dot of dots) {
    const c = document.createElementNS(NS, "circle");
    c.setAttribute("r", String(dot.r));
    c.setAttribute("class", `packet ${cls}`);
    c.style.opacity = "0";
    const m = document.createElementNS(NS, "animateMotion");
    m.setAttribute("dur", `${ms}ms`);
    m.setAttribute("path", d);
    m.setAttribute("fill", "freeze");
    m.setAttribute("begin", "indefinite");
    c.appendChild(m);
    layer.appendChild(c);
    const start = () => { c.style.opacity = String(dot.op); (m as unknown as { beginElement: () => void }).beginElement(); };
    setTimeout(start, delay + dot.lag);
    setTimeout(() => c.remove(), delay + dot.lag + ms + 200);
  }
}

function drop(layer: SVGGElement, index: number, busy: boolean) {
  const c = document.createElementNS(NS, "circle");
  c.setAttribute("r", "5");
  c.setAttribute("class", "packet drop");
  if (!busy) c.setAttribute("style", "stroke:#8b96a9;filter:none");
  layer.appendChild(c);
  const x = 196 + index * 6;
  c.animate(
    [
      { transform: `translate(${x}px,240px)`, opacity: 1 },
      { transform: `translate(${x + 12}px,322px)`, opacity: 0 },
    ],
    { duration: 850, delay: index * 120, easing: "ease-in", fill: "forwards" },
  );
  setTimeout(() => c.remove(), 850 + index * 120 + 150);
}

function useFit(ref: React.RefObject<HTMLDivElement | null>) {
  const [scale, setScale] = useState(1);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const calc = () => setScale(Math.min(1.3, Math.max(0.3, el.clientWidth / STAGE_W)));
    const ro = new ResizeObserver(calc);
    ro.observe(el);
    calc();
    return () => ro.disconnect();
  }, [ref]);
  return scale;
}

export function FlowStage() {
  const wrap = useRef<HTMLDivElement>(null);
  const layer = useRef<SVGGElement>(null);
  const edgeRefs = useRef<Record<string, SVGPathElement | null>>({});
  const scale = useFit(wrap);
  const selected = useStore((s) => s.selected);
  const conn = useStore((s) => s.conn);
  const camera = useStore((s) => s.camera);
  const flowing = (conn === "live" || conn === "simulated") && camera === "open";

  useEffect(() => {
    return flowBus.on((sig: Signal) => {
      const g = layer.current;
      if (!g || document.hidden) return;
      if (sig.kind === "hop") {
        const edge = edgeById(sig.edge);
        if (!edge) return;
        const el = edgeRefs.current[sig.edge];
        const ms = 380 + pathLength(edge.d) * 0.9;
        if (el) {
          el.classList.add("hot");
          window.setTimeout(() => el.classList.remove("hot"), ms + 500);
        }
        if (!reduceMotion()) travel(g, edge.d, ms, sig.tone === "read" ? "read" : "");
      } else if (sig.kind === "drop") {
        if (!reduceMotion()) for (let i = 0; i < Math.min(sig.count, 6); i++) drop(g, i, sig.reason === "busy");
      } else if (sig.kind === "swap") {
        const n = NODES.find((x) => x.id === sig.stage);
        if (!n || reduceMotion()) return;
        const { into, out } = swapPaths(n);
        travel(g, into, 420, "swap");
        travel(g, out, 420, "swap", 600);
      }
    });
  }, []);

  return (
    <div ref={wrap} className="flow-wrap" style={{ height: STAGE_H * scale }}>
      <div className="flow-stage" style={{ transform: `scale(${scale})`, width: STAGE_W, height: STAGE_H }}>
        <div className="lane-title" style={{ top: 8 }}>Frame lane: camera to memory</div>
        <div className="lane-rule" style={{ top: LANE_RULE_Y }} />
        <div className="lane-title" style={{ top: LANE_RULE_Y + 10 }}>Question lane: voice to answer</div>
        <svg className={`flow-svg${flowing ? " active" : ""}`} viewBox={`0 0 ${STAGE_W} ${STAGE_H}`} aria-hidden="true">
          <defs>
            <marker id="arrowhead" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse">
              <path d="M1,1 L8,5 L1,9" fill="none" stroke="#5c7db0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </marker>
          </defs>
          {EDGES.map((e) => (
            <g key={e.id}>
              <path ref={(el) => { edgeRefs.current[e.id] = el; }} className={`edge${e.label ? " read" : ""}`} d={e.d} markerEnd="url(#arrowhead)" />
              {e.label && e.labelAt && <text className="edge-label" x={e.labelAt.x + 8} y={e.labelAt.y - 8}>{e.label}</text>}
            </g>
          ))}
          <g ref={layer} />
        </svg>
        {NODES.map((n) => <NodeTile key={n.id} node={n} selected={selected === n.id} />)}
      </div>
      {selected && <NodeDrawer stage={selected} onClose={() => useStore.getState().setSelected(null)} />}
    </div>
  );
}

function NodeTile({ node, selected }: { node: GraphNode; selected: boolean }) {
  const st = useStore((s) => s.nodes[node.id]);
  const camera = useStore((s) => s.camera);
  const gateScore = useStore((s) => s.gate.lastScore);
  const model = useStore((s) => (node.role ? s.hello?.models.find((m) => m.role === node.role && m.selected) : undefined));
  const [shake, setShake] = useState(0);
  const [swap, setSwap] = useState(0);

  useEffect(() => flowBus.on((sig) => {
    if (sig.kind === "fault" && sig.stage === node.id) setShake((x) => x + 1);
    if (sig.kind === "swap" && sig.stage === node.id) setSwap((x) => x + 1);
  }), [node.id]);

  const status = node.id === "camera" ? cameraStatus(camera, st.status) : st.status;
  const sub = model ? model.name.replace(/^ML Kit /, "").replace(/^image /, "") : node.sub;
  const camOpen = node.id === "camera" && camera === "open";

  let val: React.ReactNode = <>--</>;
  if (node.id === "camera") val = <>{camera === "open" ? "ACTIVE" : camera.toUpperCase()}</>;
  else if (node.id === "gate" && status !== "idle") val = <>{gateScore.toFixed(2)}<small>SCORE</small></>;
  else if (status === "running") val = <>WORKING</>;
  else if (status === "ok") val = st.ms > 0 ? <>{st.ms}<small>MS</small></> : <>DONE</>;
  else if (status === "skipped") val = <>OFF</>;
  else if (status === "error") val = <>ERROR</>;
  else if (status === "disabled") val = <>TRIPPED</>;

  const out = status === "ok" || status === "error" ? shortOut(node.id, st.message) : "";

  return (
    <button
      type="button"
      key={`${node.id}-${shake}`}
      className={`node${selected ? " selected" : ""}${shake ? " fault" : ""}${camOpen ? " cam-open" : ""}`}
      data-status={status}
      style={{ left: node.x, top: node.y, width: NODE_W, height: NODE_H }}
      onClick={() => useStore.getState().setSelected(selected ? null : node.id)}
      aria-label={`${node.title}: ${status}${st.ms ? `, ${st.ms} ms` : ""}`}
      aria-pressed={selected}
    >
      <span className="ring">
        <Gear className="gear" size={68} weight="bold" />
        <StageIcon id={node.icon} size={26} />
      </span>
      <span className="head">
        <span className="t"><span>{node.title}</span><i className="led" /></span>
        <span className="sub"><span key={swap} className={`swapname${swap ? " swap" : ""}`}>{sub}</span></span>
      </span>
      <span className="read"><span className="val">{val}</span></span>
      <span className="outrow" title={st.message}>{out}</span>
      {node.role && <span className="lib">LIBRARY</span>}
      <span className="bar" />
    </button>
  );
}

/** A few characters of the stage's latest output, so the tile says what was found. */
function shortOut(id: string, msg: string): string {
  if (!msg) return "";
  if (id === "privacy") return msg.replace(/ face\(s\) blurred/, " faces blurred");
  if (id === "detect") return msg.replace(/ object\(s\)/, " objects found");
  if (id === "label") return msg.replace(/ label\(s\)/, " labels found");
  if (id === "ocr") return `${msg} read`;
  if (id === "zone") return `place: ${msg}`;
  if (id === "store") return msg.startsWith("same") ? "same as last" : msg;
  if (id === "ledger") return msg.replace(/ item\(s\) updated/, " items updated");
  if (id === "compose") return msg.length > 28 ? `${msg.slice(0, 27)}...` : msg;
  return "";
}

function cameraStatus(cam: string, fallback: NodeState["status"]): NodeState["status"] {
  if (cam === "error") return "error";
  if (cam === "opening") return "running";
  if (cam === "open") return fallback === "idle" ? "ok" : fallback;
  if (cam === "closed" || cam === "off") return "idle";
  return fallback;
}
