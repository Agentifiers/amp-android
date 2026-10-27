import { Capture, decodeRelayFrame, encodeRelayFrame, loadState, pick } from "./lib";
import { ensureExecutor, openObserver } from "./s04-live-thread";

const cap = new Capture("s08-terminal");
const threadId = String((await loadState()).runnerThread);
const { sock } = await openObserver(cap, threadId);
await ensureExecutor(cap, sock);
const obs = sock.notifications.filter((n) => n.method === "observers").at(-1);
console.log("observers flags", JSON.stringify(obs?.params).slice(0, 400));

const channelId = `relay-${crypto.randomUUID()}`;
const enc = new TextEncoder();
const dec = new TextDecoder();
let actorSendsBinary = false;
let output = "";
const controls: unknown[] = [];
let exitInfo: unknown = null;

function onPayload(payload: Uint8Array, binary: boolean, via: string) {
  if (binary) { output += dec.decode(payload); return; }
  const msg = JSON.parse(dec.decode(payload));
  controls.push({ via, msg });
  console.log("control from terminal", via, JSON.stringify(msg));
  if (pick(msg, "type") === "exit") exitInfo = msg;
}
sock.onBinary((bytes) => {
  const f = decodeRelayFrame(bytes);
  if (!f || f.channelId !== channelId) return;
  actorSendsBinary = true;
  onPayload(f.payload, f.binary, "binary-frame");
});
sock.on((n) => {
  if (n.params.channelId !== channelId) return;
  if (n.method === "client_executor_relay_output") onPayload(Uint8Array.from(atob(String(n.params.dataBase64)), (c) => c.charCodeAt(0)), n.params.binary === true, "json-output");
  if (n.method === "client_executor_relay_exit") { exitInfo = exitInfo ?? n.params; console.log("relay exit", JSON.stringify(n.params)); }
});

function relaySend(payload: Uint8Array, binary: boolean) {
  if (actorSendsBinary) sock.sendBinary(encodeRelayFrame(1, channelId, payload, { binary }), { relay: { kind: 1, channelId, binary, text: binary ? dec.decode(payload) : dec.decode(payload) } });
  else sock.notify("client_executor_relay_data", { channelId, dataBase64: Buffer.from(payload).toString("base64"), binary });
}

sock.notify("client_executor_relay_open", { channelId, service: "amp-terminal-v1", binaryFrames: true });
relaySend(enc.encode(JSON.stringify({ type: "open", cols: 80, rows: 24 })), false);
const t0 = Date.now();
while (!controls.some((c) => pick(c, "msg.type") === "ready") && !exitInfo && Date.now() - t0 < 10_000) await Bun.sleep(50);
console.log("ready after", Date.now() - t0, "ms; actor sends binary relay frames:", actorSendsBinary);
relaySend(enc.encode(JSON.stringify({ type: "resize", cols: 80, rows: 24 })), false);
await Bun.sleep(500);
relaySend(enc.encode("pwd && git status --short\r"), true);
await Bun.sleep(3000);
console.log("---- PTY output (escape codes stripped) ----\n" + output.replace(/\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07/g, "") + "\n----");
cap.log("terminal", "note", { ptyOutput: output });
relaySend(enc.encode("exit\r"), true);
const t1 = Date.now();
while (!exitInfo && Date.now() - t1 < 8000) await Bun.sleep(50);
console.log("exit", JSON.stringify(exitInfo), "after", Date.now() - t1, "ms");
sock.notify("client_executor_relay_close", { channelId });
await Bun.sleep(500);
console.log("output bytes", output.length, "capture", cap.file);
sock.close();
process.exit(0);
