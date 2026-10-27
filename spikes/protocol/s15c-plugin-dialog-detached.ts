import { Capture, loadState, pick, type Notification } from "./lib";
import { ensureExecutor, openObserver } from "./s04-live-thread";
import { connectUserActor } from "./s14-user-actor";
import { ntfyWebPush } from "./webpush";

const cap = new Capture("s15c-plugin-dialog-detached");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const push = await ntfyWebPush(cap);
const user = await connectUserActor(cap);
let { sock } = await openObserver(cap, threadId);
await Bun.sleep(3000);
await sock.request("client_upsert_notification_subscription", { subscription: push.subscription, threadURL: `https://ampcode.com/threads/${threadId}` });
await ensureExecutor(cap, sock);
const isEvent = (event: string) => (n: Notification) => n.method === "plugin_message" && pick(n.params, "message.event") === event;
await sock.waitFor((n) => isEvent("command.list")(n) && JSON.stringify(n.params).includes("spike-dialog"), 30_000, "command.list");

const commandRequestId = `spike-command-${crypto.randomUUID()}`;
sock.notify("executor_plugin_message", { message: { type: "request", id: commandRequestId, method: "command.execute", params: { pluginName: "spike-dialog", commandId: "spike-dialog", threadID: threadId } } });
const added = await sock.waitFor(isEvent("plugin.ui.request_added"), 30_000, "request_added");
const requestId = String(pick(added.params, "message.data.request.requestId"));
console.log("pending", requestId, "line", added.line, "; closing the only observer");
const closedAt = Date.now();
sock.close();

const pushed = await push.nextMessage(0, 45_000);
const pushPayload = pushed ? await push.decryptMessage(pushed) : null;
console.log("push with no observer", pushPayload ? `line ${pushPayload.line} ${JSON.stringify(pushPayload.payload)}` : "none in 45s");
const statuses = user.events.filter((e) => e.name === "threadStatusUpdated" && e.at >= closedAt - 2000 && JSON.stringify(e.args).includes(threadId));
console.log("userActor threadStatusUpdated after observer left", statuses.map((e) => `line ${e.line} indicator=${JSON.stringify(pick((e.args as unknown[])[0], "indicator"))} state=${pick((e.args as unknown[])[0], "state")}`).join("\n  ") || "none");

({ sock } = await openObserver(cap, threadId));
await Bun.sleep(3000);
const snapshot = sock.notifications.filter(isEvent("plugin.ui.snapshot")).at(-1);
const pending = ((pick(snapshot?.params, "message.data.requests") as Record<string, unknown>[] | undefined) ?? []).filter((r) => r.status === "pending");
console.log("plugin.ui.snapshot on reconnect", snapshot ? `line ${snapshot.line}` : "none", "pending", JSON.stringify(pending.map((r) => ({ requestId: r.requestId, method: r.method }))));
const from = sock.notifications.length;
sock.notify("executor_plugin_message", { message: { type: "request", id: `plugin-ui-respond-${requestId}-1`, method: "plugin.ui.respond", params: { requestId, result: false } } });
const settled = await sock.waitFor((n) => isEvent("plugin.ui.request_settled")(n) && JSON.stringify(n.params).includes(requestId), 15_000, "settled", from).catch(() => null);
console.log("answered false after reconnect", settled ? `line ${settled.line} response=${JSON.stringify(pick(settled.params, "message.data.request.response"))}` : "no settle");
const next = await sock.waitFor(isEvent("plugin.ui.request_added"), 10_000, "input request", from).catch(() => null);
if (next) {
  const id = String(pick(next.params, "message.data.request.requestId"));
  sock.notify("executor_plugin_message", { message: { type: "request", id: `plugin-ui-respond-${id}-1`, method: "plugin.ui.respond", params: { requestId: id, error: "cancelled by spike" } } });
  const done = await sock.waitFor((n) => n.method === "plugin_message" && JSON.stringify(n.params).includes(id) && ["plugin.ui.request_settled", "plugin.ui.request_removed"].includes(String(pick(n.params, "message.event"))), 10_000, "input settled", from).catch(() => null);
  console.log("input answered with error", done ? `line ${done.line} ${JSON.stringify(pick(done.params, "message.data"))}` : "no settle");
}
await Bun.sleep(3000);
const commandResponse = sock.notifications.find((n) => n.method === "plugin_message" && pick(n.params, "message.id") === commandRequestId);
console.log("command.execute response", commandResponse ? `line ${commandResponse.line} ${JSON.stringify(pick(commandResponse.params, "message"))}` : "not seen on this socket");
cap.log("verdict", "note", { requestId, pushDelivered: Boolean(pushPayload), pushLine: pushPayload?.line ?? null, statusLines: statuses.map((e) => e.line), snapshotLine: snapshot?.line ?? null, pendingInSnapshot: pending.length, settledLine: settled?.line ?? null });
sock.close();
user.close();
process.exit(0);
