import { Capture, internal, pick, userAction, userCredentials } from "./lib";

const cap = new Capture("s02-threads");
const list = await internal(cap, "listThreads", { limit: 5 });
const threads = (pick(list.result, "threads") as Record<string, unknown>[] | undefined) ?? [];
console.log("listThreads", list.status, list.ok, "count", threads.length, "line", list.line);
console.log("thread keys", threads[0] ? Object.keys(threads[0]) : list.error);
for (const t of threads) console.log(" ", t.id, JSON.stringify(t.title), t.updatedAt ?? t.lastActivityAt);

const creds = await userCredentials(cap, "read");
console.log("user creds poolName", creds.poolName, "userId", creds.userId);
const sinceMs = Date.now() - 7 * 864e5 + 0.5; // integers >= 2^32 in rivet JSON args crash the action (probe-json-int.ts); a fractional value passes
const recent = await userAction(cap, creds, "getRecentThreads", [{ limit: 5, sinceMs }]);
const output = pick(recent.body, "output");
console.log("getRecentThreads", recent.status, Array.isArray(output) ? `count ${output.length}` : recent.body, "line", recent.line);
if (Array.isArray(output) && output[0]) console.log("summary keys", Object.keys(output[0]), JSON.stringify(output[0]).slice(0, 600));

