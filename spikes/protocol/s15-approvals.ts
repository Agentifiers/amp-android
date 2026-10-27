import { Capture, internal, loadState, pick, saveState, threadCredentials, ThreadSocket } from "./lib";
import { ensureExecutor, openObserver, sendAndWaitIdle } from "./s04-live-thread";
import { rpcAppend, rpcCall, waitForTurnEnd } from "./s06-rpc";
import { connectUserActor } from "./s14-user-actor";
import { ntfyWebPush } from "./webpush";

type Answer = { via: "socket" | "rpc"; accepted: boolean; input?: Record<string, unknown> };
type Round = { n: number; observerOpenDuringTurn: boolean; answer: Answer };

const ROUNDS: Round[] = [
  { n: 1, observerOpenDuringTurn: true, answer: { via: "socket", accepted: true } },
  { n: 2, observerOpenDuringTurn: false, answer: { via: "rpc", accepted: true } },
  { n: 3, observerOpenDuringTurn: true, answer: { via: "socket", accepted: false, input: { denyFeedback: "spike: denied on purpose" } } },
];
const SCRATCH = "/tmp/amp-scratch";

const cap = new Capture("s15-approvals");
const state = await loadState();
let threadId = process.argv[2] ?? "";
if (!threadId) {
  threadId = (await threadCredentials(cap, { agentMode: "low" })).threadId;
  const label = await internal(cap, "addThreadLabels", { thread: threadId, labels: ["android-spike"] });
  console.log("created approval thread", threadId, "label ok", label.ok);
  await saveState({ approvalThread: threadId, threads: [...new Set([...(state.threads ?? []), threadId])] });
}

const push = await ntfyWebPush(cap);
const user = await connectUserActor(cap);
let { sock } = await openObserver(cap, threadId);
const upsert = await sock.request("client_upsert_notification_subscription", { subscription: push.subscription, threadURL: `https://ampcode.com/threads/${threadId}` });
console.log("push subscription", push.topic, JSON.stringify(upsert));
await ensureExecutor(cap, sock);
const answeredIds: string[] = [];

for (const round of ROUNDS) {
  console.log(`\n== round ${round.n}: observer open during turn=${round.observerOpenDuringTurn}, answer via ${round.answer.via}, accepted=${round.answer.accepted}`);
  const t0 = Date.now();
  const pushFrom = push.streamed.length;
  const prompt = `Use the shell_command tool to run exactly this command and nothing else: echo s15-${round.n} > s15-${round.n}.txt\nIf the command is rejected, reply with the single word: denied`;
  let approval: unknown;
  let approvalLine: number | undefined;
  let messageId: string;
  if (round.observerOpenDuringTurn) {
    if (sock.closed) {
      ({ sock } = await openObserver(cap, threadId));
      await Bun.sleep(3000);
    }
    const from = sock.notifications.length;
    ({ id: messageId } = await sendAndWaitIdle(sock, prompt, { waitIdle: false }));
    const queued = await sock.waitFor((n) => n.method === "tool_approval_queue" && JSON.stringify(n.params).includes("\"approvals\":[{") && !answeredIds.some((id) => JSON.stringify(n.params).includes(id)), 120_000, "tool approval queued", from);
    approval = (pick(queued.params, "approvals") as unknown[])[0];
    approvalLine = queued.line;
    const awaiting = sock.notifications.slice(from).filter((n) => n.method === "agent_state").map((n) => `${pick(n.params, "state")}@${n.line}`);
    console.log("tool_approval_queue line", queued.line, JSON.stringify(queued.params));
    console.log("agent_state so far", awaiting.join(" "));
  } else {
    sock.close();
    await Bun.sleep(1000);
    const sent = await rpcAppend(cap, threadId, prompt);
    messageId = sent.id;
    console.log("sent via /rpc with no observer", sent.status, JSON.stringify(sent.body));
  }

  const pushed = await push.nextMessage(pushFrom, 60_000);
  const pushPayload = pushed ? (await push.decryptMessage(pushed)) : null;
  console.log("push", pushPayload ? `line ${pushPayload.line} ${JSON.stringify(pushPayload.payload)}` : "none within 60s");
  for (let i = 0; !approval && i < 15; i++) {
    const pending = await internal(cap, "getThreadTail", { thread: threadId, limit: 4 });
    const toolUse = ((pick(pending.result, "messages") as Record<string, unknown>[] | undefined) ?? []).flatMap((m) => (m.content as Record<string, unknown>[] | undefined) ?? []).findLast((b) => b.type === "tool_use" && !answeredIds.includes(String(b.id)));
    const pushId = pick(pushPayload?.payload, "toolCallId");
    if (pushId || toolUse) approval = { toolCallId: pushId ?? toolUse?.id, source: pushId ? "push" : "getThreadTail", tailLine: pending.line };
    else await Bun.sleep(3000);
  }
  console.log("approval target", JSON.stringify(approval));
  await Bun.sleep(2000);
  const statuses = user.events.filter((e) => e.name === "threadStatusUpdated" && e.at >= t0 && JSON.stringify(e.args).includes(threadId));
  console.log("userActor threadStatusUpdated while waiting", statuses.map((e) => `line ${e.line} ${JSON.stringify(e.args)}`).join("\n  "));

  const [idKey, toolCallId] = ["toolCallId", "toolUseId", "toolUseID", "id"].map((k) => [k, pick(approval, k)] as const).find(([, v]) => typeof v === "string") ?? ["none", undefined];
  if (!toolCallId) throw new Error(`no tool call id in approval ${JSON.stringify(approval)}`);
  answeredIds.push(toolCallId);
  const response = { toolCallId, accepted: round.answer.accepted, ...(round.answer.input ? { input: round.answer.input } : {}) };
  console.log("answering with key", idKey, JSON.stringify(response));
  let answered: unknown = round.answer.via === "rpc" ? await rpcCall(cap, threadId, "client_tool_approval_response", response) : null;
  if (answered) console.log("/rpc client_tool_approval_response ->", JSON.stringify(answered));
  if (!answered || pick(answered, "body.error")) {
    if (sock.closed) ({ sock } = await openObserver(cap, threadId));
    answered = await sock.request("client_tool_approval_response", response).catch((e: Error & { rpc?: unknown }) => ({ rejected: e.message, rpc: e.rpc }));
    console.log("socket client_tool_approval_response ->", JSON.stringify(answered));
  }

  if (round.observerOpenDuringTurn) {
    const from = sock.notifications.findIndex((n) => n.line === approvalLine);
    await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") === "idle", 180_000, "idle after approval", from + 1);
    const result = sock.notifications.slice(from).find((n) => n.method === "message_added" && JSON.stringify(pick(n.params, "message.content")).includes(toolCallId) && pick(n.params, "message.role") === "user");
    console.log("tool_result", result ? `line ${result.line} ${JSON.stringify(pick(result.params, "message.content")).slice(0, 600)}` : "not seen");
    const queues = sock.notifications.slice(from).filter((n) => n.method === "tool_approval_queue").map((n) => `line ${n.line} ${JSON.stringify(n.params).slice(0, 200)}`);
    console.log("later tool_approval_queue events", queues.join(" | "));
  } else {
    const done = await waitForTurnEnd(cap, threadId, messageId);
    console.log("turn ended (tail line", done.line, ")", done.messages.map((m) => `${m.role}:${JSON.stringify(m.content).slice(0, 160)}`).join("\n  "));
  }
  const file = Bun.file(`${SCRATCH}/s15-${round.n}.txt`);
  const exists = await file.exists();
  cap.log("verify", "note", { round: round.n, file: `s15-${round.n}.txt`, exists, content: exists ? await file.text() : null, statusEventLines: statuses.map((e) => e.line), pushLine: pushPayload?.line ?? null, approvalLine: approvalLine ?? null });
  console.log(`s15-${round.n}.txt exists=${exists}`, exists ? JSON.stringify(await file.text()) : "");
}

await Bun.sleep(2000);
sock.close();
user.close();
process.exit(0);
