// S23: does Amp keep every Web Push subscription on a thread, or only the latest, and does a connected viewer stop the push?
// The device under test subscribes first (the coordinator opens the thread in the app), then this script upserts two
// relay registrations A and B, runs one turn, and reports which registrations the relay saw a push for.
// NO_OBSERVER=1 closes this script's socket before the turn and sends through the stateless /request/rpc instead.
import { createECDH, randomBytes } from "node:crypto";
import { join } from "node:path";
import { Capture, internal, threadCredentials } from "./lib";
import { ensureExecutor, openObserver, sendAndWaitIdle } from "./s04-live-thread";
import { rpcAppend, waitForTurnEnd } from "./s06-rpc";

const NO_OBSERVER = process.env.NO_OBSERVER === "1";

const RELAY = process.env.RELAY_URL;
if (!RELAY) throw new Error("set RELAY_URL to the deployed relay origin");
if (!process.env.CLOUDFLARE_API_TOKEN) throw new Error("export CLOUDFLARE_API_TOKEN so wrangler tail can read the relay's logs");
const GO_FILE = "/tmp/s23-go";
const THREAD_FILE = "/tmp/s23-thread.txt";

const cap = new Capture(NO_OBSERVER ? "s23b-push-no-viewer" : "s23-push-viewer");

async function relayCall(method: string, path: string, opts: { json?: unknown; bearer?: string } = {}) {
  const r = await fetch(`${RELAY}${path}`, {
    method,
    headers: { ...(opts.json === undefined ? {} : { "Content-Type": "application/json" }), ...(opts.bearer ? { Authorization: `Bearer ${opts.bearer}` } : {}) },
    body: opts.json === undefined ? undefined : JSON.stringify(opts.json),
  });
  return { status: r.status, body: await r.text() };
}

type PushLog = Record<string, unknown> & { route?: string; ridPrefix?: string };
const pushLogs: PushLog[] = [];
const tail = Bun.spawn(["bunx", "wrangler", "tail", "amp-push-relay", "--format", "json"], {
  cwd: join(import.meta.dir, "../../relay"),
  stdout: "pipe",
  stderr: "ignore",
});
(async () => {
  let buf = "";
  const decoder = new TextDecoder();
  for await (const chunk of tail.stdout) {
    buf += decoder.decode(chunk);
    let start = buf.indexOf("{");
    while (start >= 0) {
      let depth = 0;
      let end = -1;
      for (let i = start; i < buf.length; i++) {
        if (buf[i] === "{") depth++;
        else if (buf[i] === "}" && --depth === 0) { end = i; break; }
      }
      if (end < 0) break;
      const event = JSON.parse(buf.slice(start, end + 1)) as { logs?: { message?: unknown[] }[] };
      buf = buf.slice(end + 1);
      for (const entry of event.logs ?? []) {
        for (const m of entry.message ?? []) {
          const line = typeof m === "string" ? (JSON.parse(m) as PushLog) : (m as PushLog);
          cap.log("relay.tail", "recv", line);
          if (line.route === "push") pushLogs.push(line);
        }
      }
      start = buf.indexOf("{");
    }
  }
})();
await Bun.sleep(8000);

function freshKeys() {
  const ecdh = createECDH("prime256v1");
  ecdh.generateKeys();
  return { auth: randomBytes(16).toString("base64url"), p256dh: ecdh.getPublicKey().toString("base64url") };
}

async function register(label: string) {
  const reg = await relayCall("POST", "/v1/registrations", { json: { fcmToken: `s22-no-fcm-${label}-${randomBytes(8).toString("hex")}` } });
  const r = JSON.parse(reg.body) as { rid: string; pushUrl: string; secret: string };
  cap.log("relay.register", "recv", { label, status: reg.status, ridPrefix: r.rid.slice(0, 8) });
  console.log(`registration ${label}`, reg.status, "ridPrefix", r.rid.slice(0, 8));
  return { ...r, label, keys: freshKeys() };
}

const a = await register("A");
const b = await register("B");

const created = await threadCredentials(cap, { agentMode: "low" });
const threadId = created.threadId;
await internal(cap, "addThreadLabels", { thread: threadId, labels: ["android-spike"] });
let { sock } = await openObserver(cap, threadId);

try {
  await sock.request("client_set_thread_title", { title: "Android push test (safe to archive)" });
  console.log("executor", await ensureExecutor(cap, sock));
  await Bun.write(THREAD_FILE, threadId);
  console.log("thread", threadId, `- open it in the app, leave it, then touch ${GO_FILE}`);
  const t0 = Date.now();
  while (!(await Bun.file(GO_FILE).exists())) {
    if (Date.now() - t0 > 20 * 60_000) throw new Error("no go signal within 20 minutes");
    await Bun.sleep(2000);
  }
  // The socket idles while the device subscribes, so a fresh one carries the upserts and the turn.
  sock.close();
  ({ sock } = await openObserver(cap, threadId));

  for (const r of [a, b]) {
    const upsert = await sock.request("client_upsert_notification_subscription", {
      subscription: { endpoint: r.pushUrl, keys: r.keys },
      threadURL: `https://ampcode.com/threads/${threadId}`,
    });
    console.log(`upsert ${r.label}`, JSON.stringify(upsert));
  }

  const before = pushLogs.length;
  if (NO_OBSERVER) {
    sock.close();
    await Bun.sleep(40_000);
    const sent = await rpcAppend(cap, threadId, "Reply with exactly the word: done");
    console.log("rpc send", sent.status);
    const done = await waitForTurnEnd(cap, threadId, sent.id);
    console.log("turn ended after", done.seconds, "s with no socket open");
  } else {
    const turn = await sendAndWaitIdle(sock, "Reply with exactly the word: done", { waitIdle: false });
    await sock.waitFor((n) => n.method === "agent_state" && ["idle", "error"].includes(String(n.params.state)), 180_000, "agent idle or error", turn.from);
  }
  const idleAt = Date.now();
  while (Date.now() - idleAt < 90_000) await Bun.sleep(1000);
  const seen = pushLogs.slice(before).map((p) => String(p.ridPrefix ?? "?"));
  cap.log("verdict", "note", { pushesForRidPrefixes: seen, a: a.rid.slice(0, 8), b: b.rid.slice(0, 8) });
  console.log("pushes seen for ridPrefixes:", JSON.stringify(seen));
  console.log("A got push:", seen.includes(a.rid.slice(0, 8)), "B got push:", seen.includes(b.rid.slice(0, 8)));
} finally {
  sock.close();
  for (const r of [a, b]) console.log(`relay delete ${r.label}`, (await relayCall("DELETE", `/v1/registrations/${r.rid}`, { bearer: r.secret })).status);
  await internal(cap, "archiveThread", { thread: threadId, archived: true });
  console.log("archived", threadId);
  tail.kill();
}
process.exit(0);
