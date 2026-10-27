import { Capture, counts, gatewayURL, pick, rivetProtocols, userCredentials } from "./lib";

const EVENTS = ["threadStatusUpdated", "draftUpdated", "feedItemUpdated", "threadMentionUpdated", "threadMentionStatusUpdated", "followedThreadActivityUpdated", "threadCommentUnreadsUpdated", "resourceRevisionUpdated", "dictationStateUpdated"];

export type UserEvent = { name: string; args: unknown; at: number; line: number };

export async function connectUserActor(cap: Capture, verbose = false) {
  const creds = await userCredentials(cap, "read");
  const url = gatewayURL("userActor", "/connect", creds.userId);
  const protocols = rivetProtocols("json", { type: "user", wsToken: creds.wsToken });
  cap.log("user", "note", { open: url, protocols });
  const ws = new WebSocket(url, protocols);
  ws.binaryType = "arraybuffer";
  const seen: string[] = [];
  const events: UserEvent[] = [];
  const send = (frame: unknown) => {
    cap.log("user", "send", frame);
    ws.send(JSON.stringify(frame));
  };
  const opened = Promise.withResolvers<void>();
  ws.onopen = () => {
    cap.log("user", "note", { opened: true, protocol: ws.protocol });
    for (const eventName of EVENTS) send({ body: { tag: "SubscriptionRequest", val: { eventName, subscribe: true } } });
    send({ body: { tag: "ActionRequest", val: { id: 1, name: "getRecentThreads", args: [{ limit: 3 }] } } });
    opened.resolve();
  };
  ws.onmessage = (e) => {
    const text = typeof e.data === "string" ? e.data : new TextDecoder().decode(e.data);
    let msg: unknown = text;
    try { msg = JSON.parse(text); } catch {}
    const tag = pick(msg, "body.tag");
    const label = tag === "Event" ? `Event:${pick(msg, "body.val.name")}` : String(tag ?? "raw");
    seen.push(label);
    const line = cap.log("user", "recv", typeof e.data === "string" ? msg : { binary: true, len: e.data.byteLength, text });
    if (tag === "Event") events.push({ name: String(pick(msg, "body.val.name")), args: pick(msg, "body.val.args"), at: Date.now(), line });
    if (verbose) console.log(new Date().toISOString(), label, "line", line, label.startsWith("Event") ? JSON.stringify(msg).slice(0, 400) : "");
  };
  ws.onclose = (e) => { cap.log("user", "note", { closed: { code: e.code, reason: e.reason } }); if (verbose) console.log("closed", e.code, e.reason); };
  ws.onerror = (e) => cap.log("user", "note", { error: String(e) });
  await opened.promise;
  return { ws, seen, events, close: () => ws.close(1000, "done") };
}

if (import.meta.main) {
  const seconds = Number(process.argv[2] ?? 60);
  const cap = new Capture("s14-user-actor");
  const user = await connectUserActor(cap, true);
  await Bun.sleep(seconds * 1000);
  console.log("frame counts", counts(user.seen), "capture", cap.file);
  user.close();
  process.exit(0);
}
