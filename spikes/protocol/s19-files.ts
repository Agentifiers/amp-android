import { Capture, internal, loadState, pick } from "./lib";
import { ensureExecutor, openObserver, RUNNER_DIR } from "./s04-live-thread";

const cap = new Capture("s19-files");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const { sock } = await openObserver(cap, threadId);
await Bun.sleep(3000);
await ensureExecutor(cap, sock);
const observers = sock.notifications.filter((n) => n.method === "observers").at(-1);
console.log("observers capability flags", JSON.stringify(observers?.params ?? null));

async function fsCall(type: string, params: Record<string, unknown>) {
  const requestId = crypto.randomUUID();
  const from = sock.notifications.length;
  const rpcResult = await sock.request(type, { requestId, ...params }, 30_000).catch((e: Error & { rpc?: unknown }) => ({ rejected: e.message, rpc: e.rpc }));
  const event = await sock.waitFor((n) => n.method.startsWith(type.replace(/_v\d$/, "")) && n.method.endsWith("_result") && n.params.requestId === requestId, 10_000, `${type} result`, from).catch(() => null);
  const out = event?.params ?? rpcResult;
  const summary = { type, params, rpcResult, resultEvent: event ? { method: event.method, line: event.line } : null, ok: pick(out, "ok"), error: pick(out, "error") ?? pick(out, "rejected") ?? null };
  cap.log("verdict", "note", summary);
  console.log(JSON.stringify({ ...summary, rpcResult: JSON.stringify(rpcResult).slice(0, 200) }));
  return out;
}

const fileUri = (p: string) => `file://${p}`;
const listings = [
  await fsCall("client_filesystem_read_directory_v2", { path: RUNNER_DIR }),
  await fsCall("client_filesystem_read_directory_v2", { path: "." }),
  await fsCall("client_filesystem_read_directory", { uri: fileUri(RUNNER_DIR) }),
];
for (const l of listings) if (pick(l, "ok") === true) console.log("entries", JSON.stringify(pick(l, "entries")).slice(0, 600));

const reads = [
  await fsCall("client_filesystem_read_file_v2", { path: `${RUNNER_DIR}/calc.py` }),
  await fsCall("client_filesystem_read_file_v2", { path: "calc.py" }),
  await fsCall("client_filesystem_read_file", { uri: fileUri(`${RUNNER_DIR}/calc.py`) }),
  await fsCall("client_filesystem_read_file_v2", { path: `${RUNNER_DIR}/calc.py`, range: { start: 0, end: 16 } }),
];
const onDisk = await Bun.file(`${RUNNER_DIR}/calc.py`).text();
for (const r of reads) if (pick(r, "ok") === true) {
  const text = Buffer.from(String(pick(r, "contentBase64")), "base64").toString("utf8");
  console.log("read ok fileSizeBytes", pick(r, "fileSizeBytes"), "equals disk", text === onDisk, "len", text.length, JSON.stringify(text.slice(0, 40)));
}

const writeTarget = `${RUNNER_DIR}/s19-write.txt`;
await fsCall("client_filesystem_write_file_v3", { path: writeTarget, contentBase64: Buffer.from("written by s19\n").toString("base64"), overwrite: false });
const written = Bun.file(writeTarget);
console.log("write_file_v3 created file", await written.exists(), (await written.exists()) ? JSON.stringify(await written.text()) : "");
await fsCall("client_filesystem_write_file_v3", { path: writeTarget, contentBase64: Buffer.from("second write\n").toString("base64"), overwrite: false });
console.log("after overwrite:false second write, content", JSON.stringify(await Bun.file(writeTarget).text()));

for (const params of [{ threadID: threadId, paths: ["calc.py"] }, { threadID: threadId, paths: [`${RUNNER_DIR}/calc.py`] }]) {
  const urls = await internal(cap, "getThreadFileURLs", params);
  console.log("getThreadFileURLs", JSON.stringify(params.paths), urls.status, JSON.stringify(urls.result ?? urls.error).slice(0, 500), "line", urls.line);
}
sock.close();
process.exit(0);
