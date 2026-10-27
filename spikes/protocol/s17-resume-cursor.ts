import { Capture, counts, internal, loadState, pick, threadCredentials, ThreadSocket } from "./lib";
import { CLIENT_APP } from "./s04-live-thread";

const cap = new Capture("s17-resume-cursor");
const state = await loadState();
const threadIds = process.argv.length > 2 ? process.argv.slice(2) : [String(state.runnerThread), String(state.approvalThread)];

async function resumeAt(threadId: string, version: number, label: string) {
  const creds = await threadCredentials(cap, { threadId });
  const sock = new ThreadSocket(cap, threadId, { wsToken: creds.wsToken, clientApplication: CLIENT_APP, interactiveClient: true, transport: "json-rpc" }, `thread.${label}`);
  await sock.open();
  const result = await sock.request("client_resume", { version });
  await Bun.sleep(3000);
  const seqs = sock.notifications.map((n) => pick(n.params, "seq")).filter((s): s is number => typeof s === "number");
  const added = sock.notifications.filter((n) => n.method === "message_added").map((n) => String(pick(n.params, "message.messageId")));
  sock.close();
  const out = { label, version, result, messageAdded: added.length, firstAdded: added[0] ?? null, minSeq: seqs.length ? Math.min(...seqs) : null, maxSeq: seqs.length ? Math.max(...seqs) : null, eventCounts: counts(sock.notifications.map((n) => n.method)) };
  cap.log("verdict", "note", { threadId, ...out });
  console.log(JSON.stringify(out));
  return out;
}

for (const threadId of threadIds) {
  console.log(`\n== ${threadId}`);
  const page = await internal(cap, "getThreadInitialTranscriptPage", { thread: threadId });
  const msgs = (pick(page.result, "messages") as Record<string, unknown>[] | undefined) ?? [];
  const v = Number(pick(page.result, "thread.data.v"));
  const candidates = { "thread.data.v": v, firstMessageIndex: pick(page.result, "firstMessageIndex"), lastMessageIndex: msgs.at(-1)?.messageId, lastProtocolMessageVersion: msgs.at(-1)?.protocolMessageVersion, updatedAt: pick(page.result, "thread.updatedAt"), hasMoreBefore: pick(page.result, "hasMoreBefore") };
  console.log("initial page", msgs.length, "messages, line", page.line, JSON.stringify(candidates));
  const zero = await resumeAt(threadId, 0, "v0");
  const atV = await resumeAt(threadId, v, "vThreadV");
  const throughSeq = Number(pick(zero.result, "replayThroughSeq"));
  if (throughSeq !== v) await resumeAt(threadId, throughSeq, "vReplayThroughSeq");
  console.log(`replayMessageCount version 0 = ${pick(zero.result, "replayMessageCount")}, version thread.data.v (${v}) = ${pick(atV.result, "replayMessageCount")}; replayThroughSeq from v0 = ${throughSeq}`);
}
process.exit(0);
