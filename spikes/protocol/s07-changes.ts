import { $ } from "bun";
import { Capture, http, loadState, pick } from "./lib";
import { ensureExecutor, openObserver } from "./s04-live-thread";

const cap = new Capture("s07-changes");
const threadId = String((await loadState()).runnerThread);
const { sock } = await openObserver(cap, threadId);
await ensureExecutor(cap, sock);

async function git(operation: Record<string, unknown>) {
  const requestId = crypto.randomUUID();
  const from = sock.notifications.length;
  const result = await sock.request("client_git_command", { requestId, operation });
  const pushed = await sock.waitFor((n) => n.method === "client_git_command_result" && n.params.requestId === requestId, 2000, "git result event", from).catch(() => null);
  const out = pushed?.params ?? result;
  console.log(`git ${operation.type}: ok=${pick(out, "ok")} exit=${pick(out, "exitCode")} via=${pushed ? `notification line ${pushed.line}` : "rpc result"}`);
  return out;
}

const status = await git({ type: "status" });
console.log(String(pick(status, "stdout")));
const head = String(pick(await git({ type: "head" }), "stdout")).trim();
console.log("head", head);
const range = await git({ type: "diff_range_snapshot", baseHash: head });
console.log(String(pick(range, "stdout")).slice(0, 1500));
const fileDiff = await git({ type: "file_diff", path: "calc.py", changeType: "modified", full: false });
const liveDiff = String(pick(fileDiff, "stdout"));
console.log("file_diff stdout:\n" + liveDiff);

const snapReq = crypto.randomUUID();
const snapFrom = sock.notifications.length;
const snapResult = await sock.request("client_git_diff_snapshot", { requestId: snapReq, baseRevision: head, headRevision: "worktree", includeUntracked: true, content: "renderable" }).catch((e: Error) => ({ error: e.message }));
const snapEvent = await sock.waitFor((n) => n.method === "client_git_diff_snapshot_result" && n.params.requestId === snapReq, 5000, "snapshot event", snapFrom).catch(() => null);
const snapshot = pick(snapEvent?.params ?? snapResult, "snapshot");
const snapFiles = (pick(snapshot, "files") as Record<string, unknown>[] | undefined) ?? [];
console.log("client_git_diff_snapshot", snapEvent ? `event line ${snapEvent.line}` : JSON.stringify(snapResult), "files", snapFiles.map((f) => `${f.path}:${f.changeType}`).join(", "));
const rangeFiles = (pick(JSON.parse(String(pick(range, "stdout"))), "files") as Record<string, unknown>[] | undefined) ?? [];
const calcSnap = snapFiles.find((f) => f.path === "calc.py");
const calcRange = rangeFiles.find((f) => f.path === "calc.py");
console.log("calc.py snapshot entry keys", Object.keys(calcSnap ?? {}));
console.log("unified diff for calc.py from client_git_diff_snapshot:\n" + String(calcSnap?.diff));
console.log("snapshot diff === file_diff stdout:", String(calcSnap?.diff).trim() === liveDiff.trim(), "| diff_range_snapshot diff === file_diff stdout:", String(calcRange?.diff).trim() === liveDiff.trim());
await Bun.write("/tmp/amp-spike-old-calc.py", String(calcSnap?.oldContent ?? ""));
await Bun.write("/tmp/amp-spike-new-calc.py", String(calcSnap?.newContent ?? ""));
const fromContents = await $`git diff --no-index --no-color -- /tmp/amp-spike-old-calc.py /tmp/amp-spike-new-calc.py`.nothrow().text();
const hunks = (d: string) => d.slice(d.indexOf("@@"));
cap.log("reconstructed", "note", { fromSnapshotContents: fromContents });
console.log("client-side diff of snapshot oldContent/newContent hunks === file_diff hunks:", hunks(fromContents).trim() === hunks(liveDiff).trim());
const capReq = crypto.randomUUID();
const capFrom = sock.notifications.length;
const capResult = await sock.request("client_request_capture", { requestId: capReq }).catch((e: Error) => ({ error: e.message }));
const capEvent = await sock.waitFor((n) => n.method === "client_request_capture_result" && n.params.requestId === capReq, 15000, "capture event", capFrom).catch(() => null);
console.log("client_request_capture", JSON.stringify(capResult), "event", capEvent ? `line ${capEvent.line} ${JSON.stringify(capEvent.params).slice(0, 400)}` : "none");
await Bun.sleep(2000);
sock.close();

const latest = await http(cap, "diff-captures.latest", `/api/threads/${threadId}/diff-captures/latest`);
console.log("diff-captures/latest", latest.status, "etag", latest.headers.get("etag"), "line", latest.line);
const again = await http(cap, "diff-captures.latest.etag", `/api/threads/${threadId}/diff-captures/latest`, { headers: { "If-None-Match": latest.headers.get("etag") ?? "" } });
console.log("conditional re-fetch", again.status);
type CapFile = { path: string; oldBlobSHA?: string; newBlobSHA?: string; diffStat?: unknown };
const files: CapFile[] = [];
for (const r of (pick(latest.body, "ranges") as unknown[] | undefined) ?? [])
  for (const s of (pick(r, "sections") as unknown[] | undefined) ?? [])
    for (const f of (pick(s, "files") as CapFile[] | undefined) ?? []) files.push(f);
console.log("captured files", JSON.stringify(files));
const calc = files.find((f) => f.path.endsWith("calc.py"));
if (calc) {
  const blob = async (sha?: string) => {
    if (!sha) return new Uint8Array();
    const b = await http(cap, "diff-captures.blob", `/api/threads/${threadId}/diff-captures/blob/${sha}/raw`);
    console.log("blob", sha.slice(0, 12), b.status, b.raw.length, "bytes");
    return b.raw;
  };
  await Bun.write("/tmp/amp-spike-old-calc.py", await blob(calc.oldBlobSHA));
  await Bun.write("/tmp/amp-spike-new-calc.py", await blob(calc.newBlobSHA));
  const rebuilt = await $`git diff --no-index --no-color -- /tmp/amp-spike-old-calc.py /tmp/amp-spike-new-calc.py`.nothrow().text();
  cap.log("reconstructed", "note", { unifiedDiff: rebuilt });
  console.log("reconstructed unified diff from blobs:\n" + rebuilt);
  console.log("blob-rebuilt hunks === live file_diff hunks:", hunks(rebuilt).trim() === hunks(liveDiff).trim());
}
process.exit(0);
