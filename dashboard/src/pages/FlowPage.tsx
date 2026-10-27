import { Eye, Funnel, Lightning, NotePencil, Timer, Quotes } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { FlowStage } from "../components/FlowStage";
import { useStore } from "../store";

function Kpi({ icon, value, label }: { icon: ReactNode; value: string | number; label: string }) {
  return (
    <div className="kpi">
      <span className="ic">{icon}</span>
      <span><div className="n tab-num">{value}</div><div className="l">{label}</div></span>
    </div>
  );
}

export function FlowPage() {
  const sys = useStore((s) => s.sys);
  const frames = useStore((s) => s.frames);
  const latest = frames[frames.length - 1];
  const seen = sys?.seen ?? 0;
  const gated = sys?.gated ?? 0;

  return (
    <div>
      <FlowStage />
      <div className="kpis">
        <Kpi icon={<Eye size={22} weight="duotone" />} value={seen} label="Frames seen" />
        <Kpi icon={<Funnel size={22} weight="duotone" />} value={seen ? `${Math.round((gated / Math.max(1, seen)) * 100)}%` : "-"} label="Work skipped by gate" />
        <Kpi icon={<Lightning size={22} weight="duotone" />} value={sys?.processed ?? 0} label="Frames analysed" />
        <Kpi icon={<NotePencil size={22} weight="duotone" />} value={sys?.notes ?? 0} label="Text notes (0 images)" />
        <Kpi icon={<Timer size={22} weight="duotone" />} value={latest ? `${latest.totalMs} ms` : "-"} label="Last frame, end to end" />
      </div>
      <div className="ticker" aria-live="polite">
        <Quotes size={22} weight="fill" color="#4aa8ff" />
        <span className="txt">
          <span className="label">Latest note</span><br />
          {latest ? latest.caption : "Waiting for the first analysed frame. Move the camera."}
        </span>
      </div>
    </div>
  );
}
