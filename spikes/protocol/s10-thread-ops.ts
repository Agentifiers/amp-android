import { Capture, internal, loadState, pick, userAction, userCredentials } from "./lib";
import { openObserver } from "./s04-live-thread";

const cap = new Capture("s10-thread-ops");
const state = await loadState();
const threadId = String(state.runnerThread);
const results: { op: string; ok: boolean; evidence: string }[] = [];
const record = (op: string, ok: boolean, evidence: string) => { results.push({ op, ok, evidence }); console.log(ok ? "PASS" : "FAIL", op, evidence); };

async function readThread() {
  const r = await internal(cap, "listThreads", { threadIDs: [threadId], includeArchived: true, includeEmpty: true });
  return { t: (pick(r.result, "threads") as Record<string, unknown>[] | undefined)?.[0] ?? {}, line: r.line };
}
async function readSummary() {
  const creds = await userCredentials(cap, "read");
  const r = await userAction(cap, creds, "getRecentThreads", [{ limit: 20 }]);
  return { s: ((pick(r.body, "output") as Record<string, unknown>[] | undefined) ?? []).find((x) => x.threadId === threadId) ?? {}, line: r.line };
}

await internal(cap, "archiveThread", { thread: threadId, archived: false });
await internal(cap, "addThreadLabels", { thread: threadId, labels: ["android-spike"] });
const labels = await internal(cap, "getThreadLabels", { thread: threadId });
record("label android-spike (addThreadLabels -> getThreadLabels)", JSON.stringify(labels.result).includes("android-spike"), `s10 line ${labels.line}`);

const pin = await internal(cap, "updateThread", { thread: threadId, pinned: true });
let rt = await readThread();
record("pin (updateThread pinned:true -> listThreads.pinned)", pin.ok && rt.t.pinned === true, `s10 lines ${pin.line},${rt.line}`);
const w = await userCredentials(cap, "write");
const unpinUA = await userAction(cap, w, "setThreadPinned", [{ threadId, pinned: false }]);
let rs = await readSummary();
rt = await readThread();
record("unpin via userActor setThreadPinned (stateless action, write cap)", unpinUA.status === 200 && rs.s.pinned === false && rt.t.pinned === false, `s10 lines ${unpinUA.line},${rs.line},${rt.line} http ${unpinUA.status}`);

const title = `android-spike runner ${new Date().toISOString().slice(0, 19)}`;
const rename = await internal(cap, "updateThread", { thread: threadId, title });
for (let i = 0; i < 10; i++) { rt = await readThread(); if (rt.t.title === title) break; await Bun.sleep(300); }
record("rename (updateThread title -> listThreads.title)", rename.ok && rt.t.title === title, `s10 lines ${rename.line},${rt.line}`);

const { sock } = await openObserver(cap, threadId);
const wsTitle = `${title} (ws)`;
await sock.request("client_set_thread_title", { title: wsTitle });
const titleEvt = await sock.waitFor((n) => n.method === "thread_title" && n.params.title === wsTitle, 5000, "thread_title").catch(() => null);
for (let i = 0; i < 10; i++) { rt = await readThread(); if (rt.t.title === wsTitle) break; await Bun.sleep(300); }
record("rename via WS client_set_thread_title", titleEvt !== null && rt.t.title === wsTitle, `thread_title event line ${titleEvt?.line}, listThreads line ${rt.line} title=${JSON.stringify(rt.t.title)}`);

await sock.request("client_mark_thread_unread", {});
await Bun.sleep(1500);
rs = await readSummary();
record("mark unread (WS client_mark_thread_unread -> getRecentThreads.hasUnreadMessages)", rs.s.hasUnreadMessages === true, `s10 line ${rs.line}`);
const lastAssistant = [...sock.notifications].reverse().find((n) => n.method === "message_added" && pick(n.params, "message.role") === "assistant");
if (lastAssistant) {
  await sock.request("client_mark_message_read", { messageId: pick(lastAssistant.params, "message.messageId") });
  await Bun.sleep(1500);
  rs = await readSummary();
  record("mark read (WS client_mark_message_read on last assistant msg)", rs.s.hasUnreadMessages === false, `s10 line ${rs.line} hasUnread=${rs.s.hasUnreadMessages}`);
}
sock.close();

const snooze = await internal(cap, "updateThread", { thread: threadId, snooze: "one_hour" });
rt = await readThread();
const snoozeEcho = pick(snooze.result, "snooze");
record("snooze one_hour (updateThread snooze)", snooze.ok && snoozeEcho !== undefined && snoozeEcho !== null, `s10 line ${snooze.line} echo=${JSON.stringify(snoozeEcho)} listThreads keys has snooze=${"snooze" in rt.t}`);
const unsnooze = await internal(cap, "updateThread", { thread: threadId, snooze: "off" });
record("unsnooze (updateThread snooze off)", unsnooze.ok, `s10 line ${unsnooze.line} echo=${JSON.stringify(pick(unsnooze.result, "snooze"))}`);

const archive = await internal(cap, "archiveThread", { thread: threadId, archived: true });
rt = await readThread();
const active = await internal(cap, "listThreads", { limit: 50 });
const inDefault = ((pick(active.result, "threads") as Record<string, unknown>[] | undefined) ?? []).find((t) => t.id === threadId);
record("archive (archiveThread -> listThreads.archived)", archive.ok && rt.t.archived === true, `s10 lines ${archive.line},${rt.line}; default listThreads (no includeArchived) still returns it: ${Boolean(inDefault)} archived=${inDefault?.archived} (line ${active.line})`);
const creds = await import("./lib").then((m) => m.threadCredentials(cap, { threadId }).then(() => "issued", (e: Error) => e.message));
record("archived thread refuses new thread-actor credentials", creds.includes("THREAD_ARCHIVED") || creds.includes("archived"), `POST /api/thread-actors -> ${creds.slice(0, 160)}`);

await Bun.write(`${import.meta.dir}/captures/s10-results.json`, JSON.stringify(results, null, 2));
process.exit(0);
