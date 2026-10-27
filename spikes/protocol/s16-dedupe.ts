import { Capture, internal, loadState, messageId, pick } from "./lib";
import { ensureExecutor, openObserver } from "./s04-live-thread";
import { rpcAppend } from "./s06-rpc";

const cap = new Capture("s16-dedupe");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const { sock } = await openObserver(cap, threadId);
await Bun.sleep(3000);
await ensureExecutor(cap, sock);
const id = messageId();
const text = "Reply with exactly the word: once";
const from = sock.notifications.length;
const params = { content: [{ type: "text", text }], messageId: id, agentMode: "low", userState: { currentlyVisibleFiles: [], timeZone: "America/Los_Angeles" } };

const first = await sock.request("client_append_user_msg", params).catch((e: Error & { rpc?: unknown }) => ({ rejected: e.message, rpc: e.rpc }));
console.log("socket append ->", JSON.stringify(first));
const viaRpc = await rpcAppend(cap, threadId, text, id);
console.log("/rpc append same messageId ->", viaRpc.status, JSON.stringify(viaRpc.body), "line", viaRpc.line);
const again = await sock.request("client_append_user_msg", params).catch((e: Error & { rpc?: unknown }) => ({ rejected: e.message, rpc: e.rpc }));
console.log("socket append same messageId again ->", JSON.stringify(again));

const busyAt = await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") !== "idle", 60_000, "busy", from);
const endAt = await sock.waitFor((n) => n.method === "agent_state" && ["idle", "error"].includes(String(pick(n.params, "state"))), 120_000, "turn end", sock.notifications.indexOf(busyAt) + 1);
console.log("turn ended with agent_state", pick(endAt.params, "state"), "line", endAt.line);
await Bun.sleep(8000);
const turn = sock.notifications.slice(from);
const userAdded = turn.filter((n) => n.method === "message_added" && pick(n.params, "message.messageId") === id);
const assistantAdded = turn.filter((n) => n.method === "message_added" && pick(n.params, "message.role") === "assistant");
const queued = turn.filter((n) => n.method.startsWith("queued_message"));
console.log("message_added with our messageId", userAdded.length, "lines", userAdded.map((n) => n.line).join(","));
console.log("assistant message_added", assistantAdded.length, "queued_message* events", queued.map((n) => `${n.method}@${n.line}`).join(" "));
const busy = turn.filter((n) => n.method === "agent_state" && pick(n.params, "state") === "working").map((n) => n.line);
console.log("agent_state working transitions", busy.length, "lines", busy.join(","));

const msgs: Record<string, unknown>[] = [];
for (let fromIndex = 0; ;) {
  const page = await internal(cap, "getThreadMessagesPage", { thread: threadId, fromIndex, limit: 50 });
  msgs.push(...((pick(page.result, "messages") as Record<string, unknown>[] | undefined) ?? []));
  const next = pick(page.result, "nextMessageIndex");
  if (typeof next !== "number") break;
  fromIndex = next;
}
const transcriptHits = msgs.filter((m) => m.protocolMessageID === id);
console.log("transcript messages with protocolMessageID", transcriptHits.length, "of", msgs.length);
cap.log("verdict", "note", { messageId: id, socketFirst: first, rpcDuplicate: viaRpc.body, socketDuplicate: again, messageAddedCount: userAdded.length, messageAddedLines: userAdded.map((n) => n.line), assistantMessages: assistantAdded.length, transcriptCount: transcriptHits.length });
sock.close();
process.exit(0);
