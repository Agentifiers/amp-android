import { Capture, counts, loadState, pick } from "./lib";
import { agentStates, ensureExecutor, openObserver, sendAndWaitIdle } from "./s04-live-thread";

const cap = new Capture("s05-steer-interrupt");
const threadId = String((await loadState()).runnerThread);
const { sock } = await openObserver(cap, threadId);
await ensureExecutor(cap, sock);
const busy = (n: { method: string; params: Record<string, unknown> }) => n.method === "agent_state" && pick(n.params, "state") === "running_tools";

const a = await sendAndWaitIdle(sock, "Run the shell command `sleep 15 && echo first-done` and report its output.", { waitIdle: false });
await sock.waitFor(busy, 60_000, "turn A running tools", a.from);
const steerFrom = sock.notifications.length;
const steer = await sendAndWaitIdle(sock, "Also, after that, run `python3 --version` and include the result.", { steer: true, waitIdle: false });
console.log("steer append result", steer.result, "line of steer request: see capture after", steerFrom);
await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") === "idle", 300_000, "turn A idle", steerFrom);
const turnA = sock.notifications.slice(a.from);
console.log("turn A counts", counts(turnA.map((n) => n.method)));
console.log("turn A states", agentStates(sock, a.from).join(" -> "));
const steerEvents = turnA.filter((n) => n.method.startsWith("queued_") || (n.method === "message_added" && pick(n.params, "message.messageId") === steer.id) || (n.method === "message_added" && pick(n.params, "message.role") === "user"));
for (const n of steerEvents) console.log("  steer-related", n.method, "line", n.line, JSON.stringify(n.params).slice(0, 300));

const b = await sendAndWaitIdle(sock, "Run the shell command `sleep 60 && echo second-done` and report its output.", { waitIdle: false });
await sock.waitFor(busy, 60_000, "turn B running tools", b.from);
const cancelFrom = sock.notifications.length;
const t0 = Date.now();
const cancel = await sock.request("client_cancel", {});
console.log("client_cancel result", cancel);
const idle = await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") === "idle", 60_000, "idle after cancel", cancelFrom);
console.log("idle after cancel in", Date.now() - t0, "ms; line", idle.line);
await Bun.sleep(1500);
const after = sock.notifications.slice(cancelFrom);
console.log("after-cancel counts", counts(after.map((n) => n.method)));
console.log("after-cancel states", agentStates(sock, cancelFrom).join(" -> "));
for (const n of after.filter((n) => ["cancelled", "message_added", "message_updated", "error", "error_set"].includes(n.method))) console.log("  ", n.method, "line", n.line, JSON.stringify(n.params).slice(0, 300));
console.log("capture", cap.file);
sock.close();
process.exit(0);
