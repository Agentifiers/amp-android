import { Capture, internal, pick, type Notification } from "./lib";
import { openObserver } from "./s04-live-thread";

const TITLE = process.argv.slice(2).find((arg) => !arg.startsWith("--")) ?? "";
const CAPTURE = `${import.meta.dir}/captures/s20-changes-params.jsonl`;
const KEPT_EVENTS: Record<string, true> = {
  observers: true,
  executor_connected: true,
  executor_status: true,
  client_git_command_result: true,
  client_git_diff_snapshot_result: true,
  client_changes_file_order_result: true,
};

// The thread is the user's real work: drop its replayed transcript, other thread titles, and file contents, keep line numbers.
async function trimCapture() {
  const dropped: Record<string, number> = {};
  const kept: string[] = [];
  let last = 0;
  for (const line of (await Bun.file(CAPTURE).text()).split("\n").filter(Boolean)) {
    const row = JSON.parse(line);
    const data = row.data;
    if (row.ch === "trim") {
      for (const [key, count] of Object.entries(data.droppedReplayFrames as Record<string, number>)) dropped[key] = (dropped[key] ?? 0) + count;
      continue;
    }
    last = row.n;
    if (row.ch === "internal.listThreads" && row.dir === "recv" && data.body !== undefined) {
      const threads = (pick(data, "body.result.threads") as { id: string; title?: string }[] | undefined) ?? [];
      row.data = { status: data.status, listed: threads.length, matched: threads.filter((t) => t.title === TITLE).map(({ id, title }) => ({ id, title })) };
    } else if (row.ch === "thread" && row.dir === "recv" && data.id === undefined && !KEPT_EVENTS[data.method]) {
      const key = data.method ?? data.type ?? "frame";
      dropped[key] = (dropped[key] ?? 0) + 1;
      continue;
    }
    for (const file of (pick(row.data, "params.snapshot.files") as Record<string, unknown>[] | undefined) ?? []) {
      for (const key of ["oldContent", "newContent", "fullFileDiff"]) {
        const value = file[key];
        if (typeof value === "string" && !/^<\d+ chars>$/.test(value)) file[key] = `<${value.length} chars>`;
      }
    }
    kept.push(JSON.stringify(row));
  }
  kept.push(JSON.stringify({ n: last + 1, t: new Date().toISOString(), ch: "trim", dir: "note", data: { droppedReplayFrames: dropped } }));
  await Bun.write(CAPTURE, kept.join("\n") + "\n");
}

if (process.argv[2] === "--trim") {
  await trimCapture();
  process.exit(0);
}

if (!TITLE) throw new Error("usage: bun s20-changes-params.ts <thread title>, or --trim");
const cap = new Capture("s20-changes-params");

const list = await internal(cap, "listThreads", { limit: 100 });
const threads = (pick(list.result, "threads") as { id: string; title?: string }[] | undefined) ?? [];
const thread = threads.find((t) => t.title === TITLE);
if (!thread) throw new Error(`no thread titled ${TITLE} in the latest ${threads.length}`);
console.log("thread", thread.id);

const { sock } = await openObserver(cap, thread.id);
await Bun.sleep(3000);
const hasExecutor = sock.notifications.some((n) => n.method === "executor_connected" || (n.method === "observers" && pick(n.params, "hasExecutor") === true));
console.log("executor connected", hasExecutor);
if (!hasExecutor) {
  sock.close();
  throw new Error("no executor on the thread; this probe never wakes one");
}

async function call(method: string, params: Record<string, unknown>, waitMs: number) {
  const requestId = crypto.randomUUID();
  const from = sock.notifications.length;
  const ack = await sock.request(method, { requestId, ...params }).then(
    (result) => ({ result }),
    (e: Error & { rpc?: unknown; line?: number }) => ({ error: e.rpc ?? e.message, line: e.line }),
  );
  if ("error" in ack) return { ack, event: null };
  const event = await sock.waitFor((n: Notification) => n.method === `${method}_result` && n.params.requestId === requestId, waitMs, `${method}_result`, from).catch(() => null);
  return { ack, event };
}

const status = await call("client_git_command", { operation: { type: "status" } }, 10_000);
console.log("status event line", status.event?.line, "ok", pick(status.event?.params, "ok"));
const head = await call("client_git_command", { operation: { type: "head" } }, 10_000);
console.log("head event line", head.event?.line, "ok", pick(head.event?.params, "ok"));

const unstaged = { baseRevision: "index", headRevision: "worktree", includeUntracked: true };
const snap = await call("client_git_diff_snapshot", { ...unstaged, content: "renderable" }, 15_000);
const snapshot = pick(snap.event?.params, "snapshot");
const files = (pick(snapshot, "files") as { path: string; changeType: string; diff?: string }[] | undefined) ?? [];
const diffHash = String(pick(snapshot, "diffHash"));
console.log("snapshot event line", snap.event?.line, "files", files.map((f) => `${f.path}:${f.changeType}`).join(", "));
if (files.length === 0) throw new Error("no unstaged files to order");

const orderFiles = files.map((f) => ({ path: f.path, changeType: f.changeType, diff: (f.diff ?? "").slice(0, 20_000) }));
const appSection = { sectionID: "unstaged", diffHash, baseRevision: unstaged.baseRevision, headRevision: unstaged.headRevision, files: orderFiles };

const before = await call("client_changes_file_order", { sections: [appSection] }, 5_000);
console.log("order without includeUntracked:", JSON.stringify(before.ack), "line", "line" in before.ack ? before.ack.line : "-");

const after = await call("client_changes_file_order", { sections: [{ ...appSection, includeUntracked: unstaged.includeUntracked }] }, 120_000);
console.log("order with includeUntracked: ack", JSON.stringify(after.ack), "result line", after.event?.line, JSON.stringify(pick(after.event?.params, "result")).slice(0, 600));

sock.close();
await Bun.sleep(500);
await trimCapture();
process.exit(0);
