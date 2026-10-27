import { createECDH, randomBytes } from "node:crypto";
import { join } from "node:path";
import { Capture, internal, threadCredentials } from "./lib";
import { ensureExecutor, openObserver, sendAndWaitIdle } from "./s04-live-thread";

const RELAY = process.env.RELAY_URL;
if (!RELAY) throw new Error("set RELAY_URL to the deployed relay origin");
if (!process.env.CLOUDFLARE_API_TOKEN) throw new Error("export CLOUDFLARE_API_TOKEN so wrangler tail can read the relay's logs");

const cap = new Capture("s11b-relay");

async function relayCall(method: string, path: string, opts: { json?: unknown; bearer?: string } = {}): Promise<{ status: number; body: string }> {
  const pin = process.env.RELAY_RESOLVE;
  if (!pin) {
    const r = await fetch(`${RELAY}${path}`, {
      method,
      headers: { ...(opts.json === undefined ? {} : { "Content-Type": "application/json" }), ...(opts.bearer ? { Authorization: `Bearer ${opts.bearer}` } : {}) },
      body: opts.json === undefined ? undefined : JSON.stringify(opts.json),
    });
    return { status: r.status, body: await r.text() };
  }
  const args = ["curl", "-s", "-w", "\n%{http_code}", "--resolve", pin, "-X", method, `${RELAY}${path}`];
  if (opts.json !== undefined) args.push("-H", "Content-Type: application/json", "-d", JSON.stringify(opts.json));
  if (opts.bearer) args.push("-H", `Authorization: Bearer ${opts.bearer}`);
  const out = await new Response(Bun.spawn(args, { stdout: "pipe" }).stdout).text();
  const nl = out.lastIndexOf("\n");
  return { status: Number(out.slice(nl + 1)), body: out.slice(0, nl) };
}

type PushLog = Record<string, unknown> & { route?: string };
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

const ecdh = createECDH("prime256v1");
ecdh.generateKeys();
const keys = { auth: randomBytes(16).toString("base64url"), p256dh: ecdh.getPublicKey().toString("base64url") };

const reg = await relayCall("POST", "/v1/registrations", { json: { fcmToken: `s11b-no-fcm-${randomBytes(8).toString("hex")}` } });
const registration = JSON.parse(reg.body) as { rid: string; pushUrl: string; secret: string };
cap.log("relay.register", "recv", { status: reg.status, rid: registration.rid, pushHost: new URL(registration.pushUrl).host });
console.log("relay registration", reg.status, "rid", registration.rid.slice(0, 8));

const created = await threadCredentials(cap, { agentMode: "low" });
const threadId = created.threadId;
await internal(cap, "addThreadLabels", { thread: threadId, labels: ["android-spike"] });
console.log("thread", threadId);
const { sock } = await openObserver(cap, threadId);

async function waitForPush(label: string, timeoutMs: number) {
  const before = pushLogs.length;
  const t0 = Date.now();
  while (pushLogs.length === before && Date.now() - t0 < timeoutMs) await Bun.sleep(1000);
  const seen = pushLogs.length - before;
  console.log(seen ? `${label}: relay saw ${seen} push request(s)` : `${label}: NO push reached the relay within ${timeoutMs / 1000} s`);
  for (const p of pushLogs.slice(before)) console.log("push log", JSON.stringify(p));
}

try {
  console.log("executor", await ensureExecutor(cap, sock));
  const upsert = await sock.request("client_upsert_notification_subscription", {
    subscription: { endpoint: registration.pushUrl, keys },
    threadURL: `https://ampcode.com/threads/${threadId}`,
  });
  console.log("upsert", JSON.stringify(upsert));

  const turn = await sendAndWaitIdle(sock, "Reply with exactly the word: done", { waitIdle: false });
  await sock.waitFor((n) => n.method === "agent_state" && ["idle", "error"].includes(String(n.params.state)), 180_000, "agent idle or error", turn.from);
  const states = sock.notifications.slice(turn.from).filter((n) => n.method === "agent_state").map((n) => String(n.params.state));
  console.log("agent states", states.join(" -> "));
  await waitForPush("agent turn", 90_000);
} finally {
  sock.close();
  const del = await relayCall("DELETE", `/v1/registrations/${registration.rid}`, { bearer: registration.secret });
  console.log("relay delete", del.status);
  await internal(cap, "archiveThread", { thread: threadId, archived: true });
  tail.kill();
}
process.exit(0);
