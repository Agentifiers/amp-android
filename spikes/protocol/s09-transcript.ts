import { Capture, counts, http, internal, loadState, pick } from "./lib";
import { openObserver } from "./s04-live-thread";

const cap = new Capture("s09-transcript");
const threadId = String((await loadState()).runnerThread);
type Msg = { role?: string; messageId?: unknown; protocolMessageID?: string };
const ids = (v: unknown) => ((v as Msg[] | undefined) ?? []).map((m) => m.protocolMessageID ?? m.messageId);

const initial = await internal(cap, "getThreadInitialTranscriptPage", { thread: threadId });
console.log("getThreadInitialTranscriptPage", initial.status, "messages", ids(pick(initial.result, "messages")).length, "firstMessageIndex", pick(initial.result, "firstMessageIndex"), "hasMoreBefore", pick(initial.result, "hasMoreBefore"), "line", initial.line);

const all: unknown[] = [];
let fromIndex = 0;
for (;;) {
  const page = await internal(cap, "getThreadMessagesPage", { thread: threadId, fromIndex, limit: 10 });
  const msgs = (pick(page.result, "messages") as unknown[] | undefined) ?? [];
  all.push(...msgs);
  const next = pick(page.result, "nextMessageIndex");
  console.log(`getThreadMessagesPage from ${fromIndex}: ${msgs.length} msgs, next ${next}, line ${page.line}`);
  if (typeof next !== "number") break;
  fromIndex = next;
}
const pagedIds = ids(all);

const anchor = (all as Msg[])[5];
const before = await internal(cap, "getThreadTranscriptPage", { thread: threadId, before: 5, anchorID: String(anchor?.protocolMessageID) });
console.log("getThreadTranscriptPage before=5 anchorID=<protocolMessageID at index 5>", before.status, "status", pick(before.result, "status"), "messages", ids(pick(before.result, "messages")).length, "line", before.line);
const stale = await internal(cap, "getThreadTranscriptPage", { thread: threadId, before: 5, anchorID: String((all as Msg[])[4]?.protocolMessageID) });
console.log("getThreadTranscriptPage with mismatched anchorID ->", JSON.stringify(stale.result), "line", stale.line);

const outline = await http(cap, "transcript-outline", `/api/threads/${threadId}/transcript-outline`);
console.log("GET transcript-outline (Bearer)", outline.status, Array.isArray(outline.body) ? `${outline.body.length} entries` : JSON.stringify(outline.body).slice(0, 200), "line", outline.line);
const pageHttp = await http(cap, "transcript-page", `/api/threads/${threadId}/transcript-page?around=0&limit=50`);
console.log("GET transcript-page (Bearer)", pageHttp.status, JSON.stringify(pick(pageHttp.body, "bounds") ?? pageHttp.body).slice(0, 300), "line", pageHttp.line);

const { sock, resume } = await openObserver(cap, threadId);
await Bun.sleep(3000);
const live = sock.notifications.filter((n) => n.method === "message_added");
const liveIds = live.map((n) => String(pick(n.params, "message.messageId")));
console.log("live replay via client_resume{version:0}", JSON.stringify(resume), "message_added", live.length, "event counts", counts(sock.notifications.map((n) => n.method)));
sock.close();

console.log("paged (getThreadMessagesPage) messages", pagedIds.length, "roles", counts(((all as Msg[]).map((m) => String(m.role)))));
console.log("same message id sequence (paged vs live replay):", JSON.stringify(pagedIds) === JSON.stringify(liveIds));
if (JSON.stringify(pagedIds) !== JSON.stringify(liveIds)) {
  console.log("only in paged", pagedIds.filter((i) => !liveIds.includes(String(i))));
  console.log("only in live", liveIds.filter((i) => !pagedIds.includes(i)));
}
process.exit(0);
