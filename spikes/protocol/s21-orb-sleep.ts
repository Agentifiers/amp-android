import { Capture, internal, pick, threadCredentials, ThreadSocket, userAction, userCredentials, type Notification } from "./lib";
import { CLIENT_APP } from "./s04-live-thread";

const THREAD_ID = process.argv.find((a) => a.startsWith("T-"));
const WAKE = process.argv.includes("--wake");
const NAME = WAKE ? "s21-orb-wake" : "s21-orb-sleep";
const CAPTURE = `${import.meta.dir}/captures/${NAME}.jsonl`;
const WATCH_MS = WAKE ? 2 * 60_000 : 20_000;
const KEPT = /^(observers|executor_|agent_state|thread_title|error_set|error_cleared|background_work)/;

// The thread is the user's real work: keep only executor frames, this thread's summary rows, and line numbers.
async function trimCapture(threadId: string) {
  const dropped: Record<string, number> = {};
  const kept: string[] = [];
  let last = 0;
  for (const line of (await Bun.file(CAPTURE).text()).split("\n").filter(Boolean)) {
    const row = JSON.parse(line);
    const data = row.data;
    last = row.n;
    if (row.ch === "internal.listThreads" && row.dir === "recv") {
      const threads = (pick(data, "body.result.threads") as { id: string; title?: string; executorType?: string }[] | undefined) ?? [];
      const matched = threads.filter((t) => t.id === threadId).map(({ id, title, executorType }) => ({ id, title, executorType }));
      row.data = { status: data.status, listed: threads.length, matched };
    } else if (row.ch === "userActor.action.getRecentThreads" && row.dir === "recv") {
      const rows = (pick(data, "body.output") as Record<string, unknown>[] | undefined) ?? [];
      const matched = rows.filter((t) => t.threadId === threadId).map(({ threadId, title, state, executorConnected, runnerID, updatedAt }) => ({ threadId, title, state, executorConnected, runnerID, updatedAt }));
      row.data = { status: data.status, listed: rows.length, matched };
    } else if (row.ch === "thread" && row.dir === "recv" && data.id === undefined && !KEPT.test(data.method ?? data.type ?? "")) {
      const key = data.method ?? data.type ?? "frame";
      dropped[key] = (dropped[key] ?? 0) + 1;
      continue;
    }
    kept.push(JSON.stringify(row));
  }
  kept.push(JSON.stringify({ n: last + 1, t: new Date().toISOString(), ch: "trim", dir: "note", data: { droppedFrames: dropped } }));
  await Bun.write(CAPTURE, kept.join("\n") + "\n");
}

const cap = new Capture(NAME);

const list = await internal(cap, "listThreads", { limit: 100 });
const threads = (pick(list.result, "threads") as { id: string; title?: string }[] | undefined) ?? [];
const thread = threads.find((t) => t.id === THREAD_ID);
if (!thread) throw new Error(`no thread ${THREAD_ID} in the latest ${threads.length}`);
console.log("thread", thread.id, "listThreads line", list.line);

const user = await userCredentials(cap, "read");
const sinceMs = Date.now() - 7 * 864e5 + 0.5;
const recent = await userAction(cap, user, "getRecentThreads", [{ limit: 100, sinceMs }]);
const summary = ((pick(recent.body, "output") as Record<string, unknown>[] | undefined) ?? []).find((t) => (t.threadId ?? t.id) === thread.id);
console.log("getRecentThreads line", recent.line, "executorConnected", summary?.executorConnected, "executorType", summary?.executorType);

const grant = await threadCredentials(cap, { threadId: thread.id });
console.log("thread-actors capability", grant.capability, "threadVersion", grant.threadVersion);
const sock = new ThreadSocket(cap, thread.id, { wsToken: grant.wsToken, clientApplication: CLIENT_APP, interactiveClient: true, transport: "json-rpc" });
await sock.open();
const resume = await sock.request("client_resume", { version: grant.threadVersion ?? 0 });
cap.log("thread", "note", { resumeResult: resume });

const connected = (n: Notification) => n.method === "executor_connected" || (n.method === "observers" && pick(n.params, "hasExecutor") === true) || (n.method === "executor_status" && pick(n.params, "status") === "running");
const executorFrames = () => sock.notifications.filter((n) => n.method === "observers" || n.method.startsWith("executor_")).map((n) => `${n.line}:${n.method}:${String(pick(n.params, "hasExecutor") ?? pick(n.params, "status"))}`);

await Bun.sleep(5_000);
console.log("connect burst executor frames", executorFrames().join(" "));
if (sock.notifications.some(connected)) console.log("executor already connected; the orb is awake");

if (WAKE && !sock.notifications.some(connected)) {
  const requestId = `spawn_${crypto.randomUUID()}`;
  const from = sock.notifications.length;
  const answer = await sock.request("client_spawn_executor", { requestId, executorDemand: "optional" }).then(
    (result) => ({ result }),
    (e: Error & { rpc?: unknown }) => ({ error: e.rpc ?? e.message }),
  );
  console.log("client_spawn_executor answer", JSON.stringify(answer));
  const hit = await sock.waitFor(connected, WATCH_MS, "executor connected", from).catch(() => null);
  console.log(hit ? `executor connected at line ${hit.line} after ${hit.at - sock.notifications[from]!.at} ms` : "no executor within 5 minutes");
  await Bun.sleep(3_000);
} else {
  await Bun.sleep(WATCH_MS);
}
console.log("executor frames", executorFrames().join(" "));

sock.close();
await Bun.sleep(500);
await trimCapture(thread.id);
process.exit(0);
