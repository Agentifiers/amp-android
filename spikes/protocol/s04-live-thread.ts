import { Capture, counts, internal, loadState, messageId, pick, saveState, threadCredentials, ThreadSocket, type Notification } from "./lib";

export const RUNNER_ID = "android-spike";
export const RUNNER_DIR = "/private/tmp/amp-scratch";
export const CLIENT_APP = { type: "cli", name: "amp-android-spike", version: "0.0.1" };

export async function openObserver(cap: Capture, threadId: string, ch = "thread") {
  const creds = await threadCredentials(cap, { threadId });
  const sock = new ThreadSocket(cap, threadId, { wsToken: creds.wsToken, clientApplication: CLIENT_APP, interactiveClient: true, transport: "json-rpc" }, ch);
  await sock.open();
  const resume = await sock.request("client_resume", { version: 0 });
  cap.log(ch, "note", { resumeResult: resume });
  return { sock, creds, resume };
}

export async function ensureExecutor(cap: Capture, sock: ThreadSocket) {
  const connected = (n: Notification) => n.method === "executor_connected" || (n.method === "observers" && pick(n.params, "hasExecutor") === true);
  if (sock.notifications.some(connected)) return "already-connected";
  const spawn = await sock.request("client_spawn_executor", { requestId: crypto.randomUUID(), executor: { type: "runner", runnerId: RUNNER_ID, workingDirectory: RUNNER_DIR } });
  cap.log("thread", "note", { spawnResult: spawn });
  await sock.waitFor(connected, 90_000, "executor connected");
  return "spawned";
}

export function agentStates(sock: ThreadSocket, from = 0) {
  return sock.notifications.slice(from).filter((n) => n.method === "agent_state").map((n) => String(pick(n.params, "state")));
}

export async function sendAndWaitIdle(sock: ThreadSocket, text: string, opts: { steer?: boolean; timeoutMs?: number; waitIdle?: boolean; content?: unknown[] } = {}) {
  const from = sock.notifications.length;
  const id = messageId();
  const result = await sock.request("client_append_user_msg", {
    content: opts.content ?? [{ type: "text", text }],
    messageId: id,
    agentMode: "low",
    userState: { currentlyVisibleFiles: [], timeZone: "America/Los_Angeles" },
    ...(opts.steer ? { steer: true } : {}),
  });
  if (opts.waitIdle === false) return { id, result, from };
  await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") !== "idle", 60_000, "agent busy", from);
  await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") === "idle", opts.timeoutMs ?? 300_000, "agent idle", from);
  return { id, result, from };
}

if (import.meta.main) {
  const cap = new Capture("s04-live-thread");
  const state = await loadState();
  let threadId = process.argv[2] ?? "";
  if (!threadId) {
    const created = await threadCredentials(cap, { agentMode: "low" });
    threadId = created.threadId;
    console.log("created thread", threadId, "response keys", Object.keys(created), "capability", created.capability, "poolName", created.poolName);
    const label = await internal(cap, "addThreadLabels", { thread: threadId, labels: ["android-spike"] });
    console.log("addThreadLabels", label.status, label.ok, JSON.stringify(label.result ?? label.error));
    await saveState({ runnerThread: threadId, threads: [...new Set([...(state.threads ?? []), threadId])] });
  }

  const { sock, resume } = await openObserver(cap, threadId);
  console.log("client_resume result", resume);
  const how = await ensureExecutor(cap, sock);
  console.log("executor", how);

  const t0 = Date.now();
  const { id, result, from } = await sendAndWaitIdle(sock, "Rename add to add_numbers in calc.py and run it");
  console.log("append result", result, "messageId", id, "turn seconds", ((Date.now() - t0) / 1000).toFixed(1));
  await Bun.sleep(1500);

  const turn = sock.notifications.slice(from);
  console.log("event type counts (turn)", counts(turn.map((n) => n.method)));
  console.log("event type counts (whole session)", counts(sock.notifications.map((n) => n.method)));
  console.log("agent_state transitions", agentStates(sock, from).join(" -> "));
  const added = turn.filter((n) => n.method === "message_added").map((n) => `${pick(n.params, "message.role")}#${pick(n.params, "seq")}`);
  console.log("message_added", added.join(", "));
  const seqs = sock.notifications.map((n) => pick(n.params, "seq")).filter((s): s is number => typeof s === "number");
  console.log("max seq", Math.max(...seqs), "capture", cap.file);
  await saveState({ runnerThreadLastSeq: String(Math.max(...seqs)), s04MessageCountLive: String(added.length) });
  sock.close();
  process.exit(0);
}
