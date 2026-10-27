import { Capture, gatewayURL, http, internal, loadState, messageId, pick, threadCredentials } from "./lib";

export async function rpcCall(cap: Capture, threadId: string, method: string, params: Record<string, unknown>) {
  const creds = await threadCredentials(cap, { threadId });
  const frame = { jsonrpc: "2.0", id: `thread-one-shot-client-${Date.now()}-0`, method, params };
  const r = await http(cap, `threadActor.rpc.${method}`, gatewayURL("threadActor", "/request/rpc", threadId), {
    method: "POST",
    headers: { "x-rivet-conn-params": JSON.stringify({ wsToken: creds.wsToken, transport: "json-rpc" }), Authorization: "", "Content-Type": "application/json" },
    body: JSON.stringify(frame),
  });
  return { status: r.status, body: r.body, line: r.line };
}

export async function rpcAppend(cap: Capture, threadId: string, text: string, id = messageId()) {
  const r = await rpcCall(cap, threadId, "client_append_user_msg", {
    content: [{ type: "text", text }],
    messageId: id,
    agentMode: "low",
    userState: { currentlyVisibleFiles: [], timeZone: "America/Los_Angeles" },
  });
  return { id, ...r };
}

export async function waitForTurnEnd(cap: Capture, threadId: string, afterMessageId: string, timeoutMs = 180_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    await Bun.sleep(4000);
    const tail = await internal(cap, "getThreadTail", { thread: threadId, limit: 6 });
    const messages = (pick(tail.result, "messages") as Record<string, unknown>[] | undefined) ?? [];
    const idx = messages.findIndex((m) => m.protocolMessageID === afterMessageId);
    const last = messages.at(-1);
    if (idx >= 0 && last && last.role === "assistant" && idx < messages.length - 1 && pick(last, "state.stopReason") === "end_turn") return { messages, line: tail.line, seconds: (Date.now() - t0) / 1000 };
  }
  throw new Error("turn did not finish");
}

if (import.meta.main) {
  const cap = new Capture("s06-rpc");
  const threadId = String((await loadState()).runnerThread);
  const sent = await rpcAppend(cap, threadId, "Reply with exactly the word: pong");
  console.log("rpc POST", sent.status, JSON.stringify(sent.body), "line", sent.line, "messageId", sent.id);
  const done = await waitForTurnEnd(cap, threadId, sent.id);
  console.log("tail after turn", done.messages.map((m) => `${m.role}:${m.messageId}:${m.protocolMessageID}`).join(" "), "line", done.line);
}
