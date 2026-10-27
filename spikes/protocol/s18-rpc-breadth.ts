import { Capture, internal, loadState, pick } from "./lib";
import { rpcAppend, rpcCall } from "./s06-rpc";
import { connectUserActor } from "./s14-user-actor";
import { ntfyWebPush } from "./webpush";

const cap = new Capture("s18-rpc-breadth");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const user = await connectUserActor(cap);
const states = () => user.events.filter((e) => e.name === "threadStatusUpdated" && JSON.stringify(e.args).includes(threadId)).map((e) => ({ state: String(pick((e.args as unknown[])[0], "state")), at: e.at, line: e.line }));
const push = await ntfyWebPush(cap);

const upsert = await rpcCall(cap, threadId, "client_upsert_notification_subscription", { subscription: push.subscription, threadURL: `https://ampcode.com/threads/${threadId}` });
console.log("/rpc client_upsert_notification_subscription", upsert.status, JSON.stringify(upsert.body), "line", upsert.line);

const title = `android-spike2 rpc title ${Date.now()}`;
const setTitle = await rpcCall(cap, threadId, "client_set_thread_title", { title });
const afterTitle = await internal(cap, "getThreadInitialTranscriptPage", { thread: threadId });
console.log("/rpc client_set_thread_title", setTitle.status, JSON.stringify(setTitle.body), "-> thread.title now", JSON.stringify(pick(afterTitle.result, "thread.title")), "matches", pick(afterTitle.result, "thread.title") === title, "line", afterTitle.line);

const pushFrom = push.streamed.length;
const long = await rpcAppend(cap, threadId, "Write a 600-word essay about ocean tides. Do not use any tools.");
console.log("/rpc long turn", long.status, JSON.stringify(long.body));
const t0 = Date.now();
while (Date.now() - t0 < 60_000 && !states().some((s) => s.at >= t0 && s.state !== "idle")) await Bun.sleep(50);
const cancelAt = Date.now();
const cancel = await rpcCall(cap, threadId, "client_cancel", {});
console.log("/rpc client_cancel", cancel.status, JSON.stringify(cancel.body), "line", cancel.line);
while (Date.now() - cancelAt < 30_000 && !states().some((s) => s.at >= cancelAt && ["idle", "error"].includes(s.state))) await Bun.sleep(100);
const idle = states().find((s) => s.at >= cancelAt && ["idle", "error"].includes(s.state));
console.log("userActor states during long turn", states().filter((s) => s.at >= t0).map((s) => `${s.state}@${s.line}+${s.at - cancelAt}ms`).join(" "), "| turn end after cancel ms", idle ? `${idle.at - cancelAt} (${idle.state})` : "none in 30s");
const tail = await internal(cap, "getThreadTail", { thread: threadId, limit: 3 });
const last = ((pick(tail.result, "messages") as Record<string, unknown>[] | undefined) ?? []).at(-1);
const words = String(JSON.stringify(last?.content ?? "")).split(/\s+/).length;
console.log("last message role", last?.role, "state", JSON.stringify(last?.state), "approx words", words, "tail line", tail.line);

const cancelledPush = await push.nextMessage(pushFrom, 20_000);
const payload = cancelledPush ? await push.decryptMessage(cancelledPush) : null;
console.log("push after cancelled turn", payload ? `line ${payload.line} ${JSON.stringify(payload.payload)}` : "none in 20s");
cap.log("verdict", "note", { upsert: upsert.body, setTitle: setTitle.body, titleApplied: pick(afterTitle.result, "thread.title") === title, cancel: cancel.body, endAfterCancel: idle ? { state: idle.state, ms: idle.at - cancelAt } : null, lastMessageState: last?.state ?? null, pushDelivered: Boolean(payload), pushLine: payload?.line ?? null });
user.close();
process.exit(0);
