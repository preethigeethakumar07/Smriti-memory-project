import { useEffect } from "react";
import { Clock, Lightning, NotePencil, User, Warning } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { command } from "../link";
import { useStore } from "../store";

const dur = (ms: number) => {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : m ? `${m}m ${s % 60}s` : `${s}s`;
};

function Kpi({ icon, value, label, alert }: { icon: ReactNode; value: string | number; label: string; alert?: boolean }) {
  return (
    <div className="kpi">
      <span className="ic" style={alert ? { color: "var(--red)" } : undefined}>{icon}</span>
      <span><div className={`n ${alert ? "alert" : ""}`} style={{ textTransform: "capitalize" }}>{value}</div><div className="l">{label}</div></span>
    </div>
  );
}

export function SessionsPage() {
  const rows = useStore((s) => s.sessions);
  const hello = useStore((s) => s.hello);
  const sys = useStore((s) => s.sys);

  useEffect(() => {
    command("listSessions");
    const t = setInterval(() => command("listSessions"), 10000);
    return () => clearInterval(t);
  }, []);

  const live = rows.find((r) => r.live);

  return (
    <div>
      <div className="kpis" style={{ marginTop: 0, marginBottom: 14 }}>
        <Kpi icon={<User size={22} weight="duotone" />} value={hello?.user.name || "-"} label={`Signed in, ${hello?.user.language || "-"}`} />
        <Kpi icon={<Clock size={22} weight="duotone" />} value={hello ? dur(Date.now() - hello.session.startedAt) : "-"} label={`Session #${hello?.session.id ?? "-"} uptime`} />
        <Kpi icon={<Lightning size={22} weight="duotone" />} value={live?.frames ?? sys?.processed ?? 0} label="Frames analysed" />
        <Kpi icon={<NotePencil size={22} weight="duotone" />} value={live?.notes ?? sys?.notes ?? 0} label="Notes stored" />
        <Kpi icon={<Warning size={22} weight="duotone" />} value={live?.errors ?? 0} label="Stage errors" alert={!!live?.errors} />
      </div>

      <section className="cell" aria-label="sessions">
        <h2><span>Users and sessions on this phone</span><span className="dim">Text records only. No frames, no faces.</span></h2>
        <table>
          <thead>
            <tr><th>#</th><th>User</th><th>Language</th><th>Device</th><th>App</th><th>Started</th><th className="num">Length</th><th className="num">Frames</th><th className="num">Notes</th><th className="num">Errors</th><th>State</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className={r.live ? "sel" : ""}>
                <td className="mono">{r.id}</td>
                <td><b>{r.user || "-"}</b></td>
                <td>{r.language}</td>
                <td>{r.device}</td>
                <td className="mono">{r.appVersion}</td>
                <td className="mono">{new Date(r.startedAt).toLocaleString([], { hour12: false })}</td>
                <td className="num">{dur(r.endedAt - r.startedAt)}</td>
                <td className="num">{r.frames}</td>
                <td className="num">{r.notes}</td>
                <td className={`num ${r.errors && !r.live ? "alert" : ""}`}>{r.errors}</td>
                <td>{r.live ? <span className="pill blue"><span className="pulse-dot" style={{ width: 7, height: 7 }} /> Live</span> : <span className="pill dim">Ended</span>}</td>
              </tr>
            ))}
            {!rows.length && <tr><td colSpan={11} className="dim">No sessions recorded yet.</td></tr>}
          </tbody>
        </table>
        <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>
          The name is what the user typed during setup. The consent version is stored with each session.
        </p>
      </section>
    </div>
  );
}
