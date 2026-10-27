import type { ReactNode } from "react";
import {
  Car, CreditCard, EnvelopeSimple, EyeSlash, IdentificationCard, Key, Monitor, Phone, ShieldCheck, UserFocus,
} from "../icons-phosphor";
import { useStore } from "../store";

/** kind id (from the phone) -> icon and short label */
const KINDS: Record<string, { icon: ReactNode; label: string }> = {
  FACE: { icon: <EyeSlash size={22} weight="duotone" />, label: "Faces blurred" },
  CARD: { icon: <CreditCard size={22} weight="duotone" />, label: "Card numbers" },
  ID_NUMBER: { icon: <IdentificationCard size={22} weight="duotone" />, label: "ID numbers" },
  PHONE: { icon: <Phone size={22} weight="duotone" />, label: "Phone numbers" },
  EMAIL: { icon: <EnvelopeSimple size={22} weight="duotone" />, label: "Email addresses" },
  PLATE: { icon: <Car size={22} weight="duotone" />, label: "Number plates" },
  PERSON: { icon: <UserFocus size={22} weight="duotone" />, label: "Names" },
  SECRET: { icon: <Key size={22} weight="duotone" />, label: "Passwords / codes" },
  DOCUMENT: { icon: <IdentificationCard size={22} weight="duotone" />, label: "Documents" },
  SCREEN: { icon: <Monitor size={22} weight="duotone" />, label: "Screens with text" },
};

function Kpi({ icon, value, label }: { icon: ReactNode; value: number; label: string }) {
  return (
    <div className="kpi">
      <span className="ic">{icon}</span>
      <span><div className="n">{value}</div><div className="l">{label}</div></span>
    </div>
  );
}

const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour12: false });

/**
 * What the privacy shield caught. The phone sends the KIND and a masked preview only, and the preview is shown
 * blurred as well, so even a screenshot of this page leaks nothing. The real values never leave the phone.
 */
export function PrivacyPage() {
  const rows = useStore((s) => s.sensitive);
  const counts = rows.reduce<Record<string, number>>((m, r) => { m[r.kind] = (m[r.kind] ?? 0) + 1; return m; }, {});
  const kinds = Object.keys(KINDS);

  return (
    <div>
      <div className="kpis" style={{ marginTop: 0, marginBottom: 14 }}>
        {kinds.map((k) => <Kpi key={k} icon={KINDS[k].icon} value={counts[k] ?? 0} label={KINDS[k].label} />)}
      </div>

      <section className="cell" aria-label="sensitive findings">
        <h2>
          <span><ShieldCheck size={16} weight="bold" style={{ verticalAlign: "-3px", marginRight: 6 }} />Sensitive content caught</span>
          <span className="dim">Values are masked on the phone and blurred here.</span>
        </h2>
        <table>
          <thead>
            <tr><th>Time</th><th>Kind</th><th>Value (masked, blurred)</th><th>What Smriti did</th><th>Source</th><th className="num">Frame</th></tr>
          </thead>
          <tbody>
            {[...rows].reverse().slice(0, 80).map((r) => (
              <tr key={r.id}>
                <td className="mono">{clock(r.t)}</td>
                <td><span className="pill red">{KINDS[r.kind]?.label ?? r.label}</span></td>
                <td className="mono"><span className="blurred" aria-label="masked value">{r.masked}</span></td>
                <td>{r.action}</td>
                <td>{r.source}</td>
                <td className="num mono">{r.frameId || "-"}</td>
              </tr>
            ))}
            {!rows.length && <tr><td colSpan={6} className="dim">Nothing sensitive seen yet. Point the camera at a card, a letter or a screen to see it caught here.</td></tr>}
          </tbody>
        </table>
        <p className="dim" style={{ margin: "12px 0 0", fontSize: 12 }}>
          Faces are blurred in the picture before any model runs. Numbers, emails, plates, names and codes are removed from the
          text before it is saved. Smriti never stores an image, and this page never receives the real value.
        </p>
      </section>
    </div>
  );
}
