import { useSyncExternalStore } from "react";

/**
 * Light / dark / system, the same three choices as the phone app's Settings > Theme. The chosen mode is remembered in this
 * browser; the page always carries the RESOLVED theme in <html data-theme="light|dark"> so the stylesheet only needs two token sets.
 */
export type ThemeMode = "system" | "light" | "dark";
export type Resolved = "light" | "dark";

const KEY = "smriti.dashboard.theme";
const listeners = new Set<() => void>();
const media = typeof window !== "undefined" && window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

function read(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch { /* storage can be blocked */ }
  return "system";
}

let mode: ThemeMode = read();

export function resolve(m: ThemeMode = mode): Resolved {
  return m === "system" ? (media?.matches === false ? "light" : "dark") : m;
}

function apply() {
  document.documentElement.dataset.theme = resolve();
  listeners.forEach((l) => l());
}

export function initTheme() {
  apply();
  media?.addEventListener?.("change", () => { if (mode === "system") apply(); });
}

export function setThemeMode(m: ThemeMode) {
  mode = m;
  try { localStorage.setItem(KEY, m); } catch { /* ignore */ }
  apply();
}

export function nextMode(m: ThemeMode): ThemeMode {
  return m === "system" ? "light" : m === "light" ? "dark" : "system";
}

function subscribe(cb: () => void) { listeners.add(cb); return () => listeners.delete(cb); }

export function useTheme(): { mode: ThemeMode; resolved: Resolved } {
  const m = useSyncExternalStore(subscribe, () => mode);
  const r = useSyncExternalStore(subscribe, () => resolve());
  return { mode: m, resolved: r };
}

/** A colour from the stylesheet's current theme, for canvas / SVG code that cannot use CSS variables directly. */
export function cssVar(name: string, fallback = "#888"): string {
  if (typeof document === "undefined") return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}
