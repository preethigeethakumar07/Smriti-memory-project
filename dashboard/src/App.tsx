import { useEffect, useState } from "react";
import {
  Aperture, Camera, ChartLineUp, Cpu, Cube, DeviceMobile, Images, ListBullets, Compass, MapTrifold, Moon, Pulse, ShieldCheck, SlidersHorizontal, Sun, Timer, User, UsersThree, UsbIcon, WifiHigh,
} from "./icons-phosphor";
import { retryLive, start, submitPin } from "./link";
import { useStore } from "./store";
import { nextMode, setThemeMode, useTheme } from "./theme";
import { AdminPage } from "./pages/AdminPage";
import { FlowPage } from "./pages/FlowPage";
import { FramesPage } from "./pages/FramesPage";
import { LogsPage } from "./pages/LogsPage";
import { MetricsPage } from "./pages/MetricsPage";
import { ModelsPage } from "./pages/ModelsPage";
import { PrivacyPage } from "./pages/PrivacyPage";
import { WalkPage } from "./pages/WalkPage";
import { SpacePage } from "./pages/SpacePage";
import { SessionsPage } from "./pages/SessionsPage";

const TABS = [
  { id: "flow", label: "Live flow", icon: Pulse, page: FlowPage },
  { id: "frames", label: "Frames", icon: Images, page: FramesPage },
  { id: "models", label: "Models", icon: Cube, page: ModelsPage },
  { id: "metrics", label: "Metrics", icon: ChartLineUp, page: MetricsPage },
  { id: "walk", label: "Walk", icon: MapTrifold, page: WalkPage },
  { id: "space", label: "Space", icon: Compass, page: SpacePage },
  { id: "privacy", label: "Privacy", icon: ShieldCheck, page: PrivacyPage },
  { id: "logs", label: "Logs", icon: ListBullets, page: LogsPage },
  { id: "admin", label: "Admin", icon: SlidersHorizontal, page: AdminPage },
  { id: "users", label: "Users", icon: UsersThree, page: SessionsPage },
] as const;

type TabId = (typeof TABS)[number]["id"];

function initialTab(): TabId {
  const h = location.hash.replace("#", "");
  return TABS.find((t) => t.id === h)?.id ?? "flow";
}

function Chip({ icon, label, value, tone, title }: { icon: React.ReactNode; label: string; value: string; tone?: "live" | "bad"; title?: string }) {
  return (
    <div className={`chip${tone ? ` ${tone}` : ""}`} title={title}>
      {icon}
      <span><div className="k">{label}</div><div className="v">{value}</div></span>
    </div>
  );
}

export function App() {
  const [tab, setTab] = useState<TabId>(initialTab);
  const conn = useStore((s) => s.conn);
  const note = useStore((s) => s.connNote);
  const hello = useStore((s) => s.hello);
  const latency = useStore((s) => s.latencyMs);
  const camera = useStore((s) => s.camera);
  const { mode, resolved } = useTheme();

  useEffect(() => { start(); }, []);
  useEffect(() => { if (location.hash.replace("#", "") !== tab) location.hash = tab; }, [tab]);
  useEffect(() => {
    const onHash = () => setTab(initialTab());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const Page = TABS.find((t) => t.id === tab)!.page;
  const simulated = conn === "simulated";
  const live = conn === "live";

  return (
    <div className="shell">
      <div className="stripe" />
      {simulated && (
        <div className="banner" role="alert">
          <span>Simulated data. No phone is connected. Nothing below is real.</span>
          <button onClick={retryLive}>Try the phone again</button>
        </div>
      )}

      <header className="top">
        <div className="brand">
          <span className="mark tile3d"><Aperture size={30} weight="duotone" /></span>
          <span>
            <h1>Smriti</h1>
            <p>Pipeline console / offline / served by the phone</p>
          </span>
        </div>
        <div className="status-row" role="status" aria-label="connection status">
          <Chip tone={live ? "live" : simulated ? "bad" : undefined} icon={live ? <span className="pulse-dot" /> : <WifiHigh size={20} />} label="Link" value={live ? "Live" : conn} />
          <Chip icon={<UsbIcon size={20} />} label="Transport" value={(hello?.transport ?? "-").toUpperCase()} />
          <Chip icon={<DeviceMobile size={20} />} label="Device" value={hello?.device.summary.split("|")[0].trim() ?? "-"} title={hello?.device.summary} />
          <Chip icon={<Cpu size={20} />} label="Chip" value={hello?.device.soc ?? "-"} />
          <Chip icon={<User size={20} />} label="User" value={hello?.user.name || "-"} />
          <Chip tone={camera === "error" ? "bad" : camera === "open" ? "live" : undefined} icon={<Camera size={20} />} label="Camera" value={camera === "open" ? "Active" : camera} />
          <Chip icon={<Timer size={20} />} label="Round trip" value={live ? `${latency} ms` : "-"} />
          <button className="theme-toggle" onClick={() => setThemeMode(nextMode(mode))} title="Theme: System, Light or Dark (same choice as the phone app)" aria-label={`Theme: ${mode}. Click to change.`}>
            {resolved === "dark" ? <Moon size={18} weight="duotone" /> : <Sun size={18} weight="duotone" />}
            {mode === "system" ? "System" : mode === "light" ? "Light" : "Dark"}
          </button>
        </div>
      </header>

      <nav className="tabs" role="tablist" aria-label="pages">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} className="tab" onClick={() => setTab(t.id)}>
            <t.icon size={18} weight={tab === t.id ? "fill" : "bold"} />
            {t.label}
          </button>
        ))}
      </nav>

      <main className="page" role="tabpanel">
        {/* keyed by theme so charts and gauges rebuild with the new colours */}
        <Page key={resolved} />
      </main>

      <footer className="footer">
        <span>Zero cloud: this page is served by the phone</span>
        <span>No images are stored</span>
        <span>{note || "OK"}</span>
        <span>App {hello?.app.version ?? "-"}</span>
      </footer>

      {(conn === "authRequired" || conn === "authFailed") && <PinPrompt failed={conn === "authFailed"} />}
    </div>
  );
}

function PinPrompt({ failed }: { failed: boolean }) {
  const [pin, setPin] = useState("");
  return (
    <div className="modal" role="dialog" aria-modal="true" aria-label="enter PIN">
      <form className="modal-box" onSubmit={(e) => { e.preventDefault(); submitPin(pin.trim()); }}>
        <h2 style={{ margin: "0 0 8px", font: "700 28px/1 var(--ui)", letterSpacing: "0.1em", textTransform: "uppercase" }}>PIN required</h2>
        <p className="dim" style={{ marginTop: 0 }}>The phone shows a 6 digit PIN in Settings, Developer dashboard.</p>
        <input type="password" inputMode="numeric" autoFocus aria-label="PIN" value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))} />
        {failed && <p className="alert" role="alert">Wrong PIN</p>}
        <div className="field"><button className="btn primary" type="submit" disabled={pin.length !== 6}>Unlock</button></div>
      </form>
    </div>
  );
}
