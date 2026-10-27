import { Capture, loadState } from "./lib";
import { openObserver } from "./s04-live-thread";
import { rpcAppend, waitForTurnEnd } from "./s06-rpc";
import { ntfyWebPush } from "./webpush";

const cap = new Capture("s11-push");
const threadId = String((await loadState()).runnerThread);
const push = await ntfyWebPush(cap);
console.log("ntfy topic", push.topic);

const probe = await fetch(push.endpoint, { method: "POST", body: new Uint8Array([1, 2, 0xff, 0xfe]), headers: { "Content-Encoding": "aes128gcm" } });
console.log("ntfy self-test binary POST", probe.status);
const self = await push.nextMessage(0, 3000);
console.log("ntfy self-test delivered:", Boolean(self), "encoding", self?.encoding);
const afterSelfTest = push.streamed.length;

const { sock } = await openObserver(cap, threadId);
const upsert = await sock.request("client_upsert_notification_subscription", {
  subscription: push.subscription,
  threadURL: `https://ampcode.com/threads/${threadId}`,
}).catch((e: Error & { rpc?: unknown }) => ({ rejected: e.message, rpc: e.rpc }));
console.log("client_upsert_notification_subscription ->", JSON.stringify(upsert));
await Bun.sleep(500);
sock.close();
await Bun.sleep(1000);

const sent = await rpcAppend(cap, threadId, "Reply with exactly the word: done");
console.log("trigger turn via /rpc", sent.status, "messageId", sent.id);
const turn = await waitForTurnEnd(cap, threadId, sent.id);
console.log("turn finished after", turn.seconds, "s; polling ntfy");

const delivered = await push.nextMessage(afterSelfTest, 120_000);
if (!delivered) {
  console.log("NO DELIVERY to ntfy within 120s after turn end");
  process.exit(0);
}
console.log("ntfy message metadata", JSON.stringify({ ...delivered, message: `<${String(delivered.message).length} chars>` }));
const { payload } = await push.decryptMessage(delivered);
console.log("decrypted push payload", JSON.stringify(payload));
process.exit(0);
