// Protocol check against a real phone (or the simulator server).
//   adb forward tcp:8765 tcp:8765
//   node scripts/ws-check.mjs [ws://localhost:8765/ws] [seconds]
// Prints a summary of what arrives and exits non-zero if the basics are missing.
const url = process.argv[2] ?? "ws://localhost:8765/ws";
const seconds = Number(process.argv[3] ?? 12);

const counts = {};
const stages = new Set();
let hello = null;
let sessions = null;
let cmdResults = [];
let binary = 0;

const ws = new WebSocket(url);
ws.binaryType = "arraybuffer";

ws.onopen = () => {
  console.log("connected", url);
  // exercise the command channel while we listen
  setTimeout(() => ws.send(JSON.stringify({ type: "cmd", id: "1", name: "listSessions", args: {} })), 1500);
  setTimeout(() => ws.send(JSON.stringify({ type: "cmd", id: "2", name: "setPreset", args: { preset: "fast" } })), 2500);
  setTimeout(() => ws.send(JSON.stringify({ type: "cmd", id: "3", name: "setPreset", args: { preset: "balanced" } })), 3500);
  setTimeout(() => ws.send(JSON.stringify({ type: "cmd", id: "4", name: "describe", args: {} })), 4500);
  setTimeout(() => ws.send(JSON.stringify({ type: "cmd", id: "5", name: "nope", args: {} })), 5500);
};

ws.onmessage = (m) => {
  if (typeof m.data !== "string") { binary++; return; }
  const o = JSON.parse(m.data);
  counts[o.type] = (counts[o.type] ?? 0) + 1;
  if (o.stage) stages.add(o.stage);
  if (o.type === "hello" && !hello) hello = o;
  if (o.type === "sessions") sessions = o.rows;
  if (o.type === "cmdResult") cmdResults.push(`${o.name}:${o.ok ? "ok" : "FAIL"}(${o.message})`);
};

ws.onerror = (e) => { console.error("socket error", e.message ?? e); process.exit(2); };

setTimeout(() => {
  ws.close();
  console.log("\nmessage counts:", counts);
  console.log("stages seen   :", [...stages].join(", "));
  console.log("binary frames :", binary);
  console.log("hello         :", hello ? `${hello.app.name} ${hello.app.version} on ${hello.device.summary}, transport=${hello.transport}, models=${hello.models.length}, preset=${hello.config.preset}` : "MISSING");
  console.log("sessions      :", sessions ? `${sessions.length} row(s), live=${sessions.filter((s) => s.live).length}` : "none");
  console.log("command results:", cmdResults.join(" | "));
  const problems = [];
  if (!hello) problems.push("no hello");
  if (!counts.SYSTEM) problems.push("no SYSTEM samples");
  if (!sessions?.length) problems.push("no sessions");
  if (!cmdResults.some((r) => r.startsWith("setPreset:ok"))) problems.push("setPreset did not succeed");
  if (!cmdResults.some((r) => r.startsWith("nope:FAIL"))) problems.push("unknown command was not rejected");
  if (problems.length) { console.error("\nPROBLEMS:", problems.join("; ")); process.exit(1); }
  console.log("\nOK");
  process.exit(0);
}, seconds * 1000);
