import { Capture, loadState, pick, type Notification } from "./lib";
import { ensureExecutor, openObserver } from "./s04-live-thread";
import { connectUserActor } from "./s14-user-actor";
import { ntfyWebPush } from "./webpush";

const SCRATCH_RESULT = "/tmp/amp-scratch/s15-plugin.txt";
const cap = new Capture("s15b-plugin-dialog");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const push = await ntfyWebPush(cap);
const user = await connectUserActor(cap);
const { sock } = await openObserver(cap, threadId);
await Bun.sleep(3000);
const upsert = await sock.request("client_upsert_notification_subscription", { subscription: push.subscription, threadURL: `https://ampcode.com/threads/${threadId}` });
console.log("push subscription", push.topic, JSON.stringify(upsert));
await ensureExecutor(cap, sock);

const pluginEvent = (event: string) => (n: Notification) => n.method === "plugin_message" && pick(n.params, "message.event") === event;
const commandList = await sock.waitFor((n) => pluginEvent("command.list")(n) && JSON.stringify(n.params).includes("spike-dialog"), 30_000, "command.list with spike-dialog");
const command = ((pick(commandList.params, "message.data.commands") as Record<string, unknown>[]) ?? []).find((c) => JSON.stringify(c).includes("spike-dialog"))!;
console.log("command.list line", commandList.line, JSON.stringify(command));

const sendPlugin = (message: Record<string, unknown>) => sock.notify("executor_plugin_message", { message });
const from = sock.notifications.length;
const t0 = Date.now();
sendPlugin({ type: "request", id: `spike-command-${crypto.randomUUID()}`, method: "command.execute", params: { pluginName: command.pluginName, commandId: command.id ?? command.commandId, threadID: threadId } });

const answers: Record<string, unknown> = { "ui.confirm": true, "ui.input": "hello from android" };
const seenRequests: string[] = [];
for (let i = 0; i < 2; i++) {
  const added = await sock.waitFor((n) => pluginEvent("plugin.ui.request_added")(n) && !seenRequests.includes(String(pick(n.params, "message.data.request.requestId"))), 30_000, `plugin.ui.request_added #${i + 1}`, from);
  const request = pick(added.params, "message.data.request") as Record<string, unknown>;
  seenRequests.push(String(request.requestId));
  console.log(`plugin.ui.request_added line ${added.line}`, JSON.stringify(added.params));
  if (i === 0) {
    const pushed = await push.nextMessage(0, 15_000);
    console.log("push while dialog pending", pushed ? JSON.stringify((await push.decryptMessage(pushed)).payload) : "none in 15s");
    const statuses = user.events.filter((e) => e.name === "threadStatusUpdated" && e.at >= t0 && JSON.stringify(e.args).includes(threadId));
    console.log("userActor threadStatusUpdated while dialog pending", statuses.map((e) => `line ${e.line} ${JSON.stringify(e.args)}`).join("\n  ") || "none");
  }
  const result = answers[String(request.method)];
  sendPlugin({ type: "request", id: `plugin-ui-respond-${request.requestId}-${i + 1}`, method: "plugin.ui.respond", params: { requestId: request.requestId, result } });
  const settled = await sock.waitFor((n) => n.method === "plugin_message" && ["plugin.ui.request_settled", "plugin.ui.request_removed"].includes(String(pick(n.params, "message.event"))) && JSON.stringify(n.params).includes(String(request.requestId)), 15_000, "settled", from).catch(() => null);
  console.log("after respond", settled ? `line ${settled.line} ${JSON.stringify(settled.params)}` : "no settle event in 15s");
}
await Bun.sleep(3000);
const tail = sock.notifications.slice(from).filter((n) => n.method === "plugin_message").map((n) => `${pick(n.params, "message.type")}:${pick(n.params, "message.event") ?? pick(n.params, "message.method")}@${n.line}`);
console.log("plugin_message sequence", tail.join(" "));
const file = Bun.file(SCRATCH_RESULT);
console.log("plugin wrote", (await file.exists()) ? JSON.stringify(await file.text()) : "nothing");
cap.log("verdict", "note", { command, requests: seenRequests, pluginWrote: (await file.exists()) ? await file.text() : null });
sock.close();
user.close();
process.exit(0);
