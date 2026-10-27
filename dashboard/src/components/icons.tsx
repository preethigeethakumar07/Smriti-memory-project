import {
  Camera, ChatText, Crosshair, Database, Funnel, Gear, ListMagnifyingGlass, MagnifyingGlass, MapPin, Microphone, NotePencil,
  ShieldCheck, SpeakerHigh, Tag, TextAa, TreeStructure, Waveform,
} from "@phosphor-icons/react";
import type { Icon, IconWeight } from "@phosphor-icons/react";
import type { IconKey } from "../graph";

export const STAGE_ICONS: Record<IconKey, Icon> = {
  camera: Camera,
  gate: Funnel,
  privacy: ShieldCheck,
  detect: Crosshair,
  label: Tag,
  ocr: TextAa,
  zone: MapPin,
  compose: NotePencil,
  store: Database,
  ledger: ListMagnifyingGlass,
  mic: Microphone,
  speech: Waveform,
  intent: TreeStructure,
  retrieve: MagnifyingGlass,
  answer: ChatText,
  voice: SpeakerHigh,
};

export function StageIcon({ id, size = 26, weight = "duotone" }: { id: string; size?: number; weight?: IconWeight }) {
  const Icon = STAGE_ICONS[id as IconKey] ?? Gear;
  return <Icon size={size} weight={weight} />;
}

export { Gear };
