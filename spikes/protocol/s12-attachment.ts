import { deflateSync } from "node:zlib";
import { Capture, http, loadState, pick } from "./lib";
import { ensureExecutor, openObserver, sendAndWaitIdle } from "./s04-live-thread";

function crc32(buf: Uint8Array) {
  let c = ~0;
  for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1)); }
  return ~c >>> 0;
}
function chunk(type: string, data: Buffer) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
const W = 16;
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(W, 4); ihdr[8] = 8; ihdr[9] = 2;
const rows = Buffer.concat(Array.from({ length: W }, () => Buffer.concat([Buffer.from([0]), Buffer.alloc(W * 3).fill(Buffer.from([0, 128, 255]))])));
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(rows)), chunk("IEND", Buffer.alloc(0))]);

const cap = new Capture("s12-attachment");
const threadId = String((await loadState()).runnerThread);
const base = { jobID: crypto.randomUUID(), mediaType: "image/png", sizeBytes: png.length, filename: "spike-blue.png", threadID: threadId };
const prep = await http(cap, "attachments.prepare", "/api/attachments/resumable", { method: "POST", json: { ...base, action: "prepare" } });
console.log("prepare", prep.status, Object.keys(prep.body ?? {}), "line", prep.line);
let url = pick(prep.body, "url") as string | undefined;
if (!url) {
  const uploadURL = String(pick(prep.body, "uploadURL"));
  console.log("uploadURL host", new URL(uploadURL).host);
  const put = await fetch(uploadURL, { method: "PUT", body: png, headers: { "Content-Type": "image/png" } });
  const putBody = await put.json().catch(() => null);
  const generation = String(pick(putBody, "generation") ?? put.headers.get("x-goog-generation"));
  cap.log("attachments.put", "recv", { status: put.status, generation, host: new URL(uploadURL).host });
  console.log("PUT bytes", put.status, "generation", generation);
  const done = await http(cap, "attachments.complete", "/api/attachments/resumable", { method: "POST", json: { ...base, action: "complete", generation } });
  console.log("complete", done.status, JSON.stringify(done.body), "line", done.line);
  url = pick(done.body, "url") as string | undefined;
}
if (!url) throw new Error("no attachment url");
const anon = await fetch(url);
console.log("attachment URL GET without auth", anon.status);
const authed = await http(cap, "attachment.get", url);
console.log("attachment URL GET with Bearer", authed.status, authed.headers.get("content-type"), authed.raw.length, "bytes; identical to upload:", Buffer.from(authed.raw).equals(png));

const { sock } = await openObserver(cap, threadId);
await ensureExecutor(cap, sock);
const { id, from } = await sendAndWaitIdle(sock, "", { waitIdle: false, content: [{ type: "text", text: "What single color fills the attached image? Answer with one word." }, { type: "image", source: { type: "url", url }, sourcePath: "spike-blue.png" }] });
await sock.waitFor((n) => n.method === "agent_state" && pick(n.params, "state") === "idle" && sock.notifications.indexOf(n) > from + 1, 120_000, "idle");
const userMsg = sock.notifications.slice(from).find((n) => n.method === "message_added" && pick(n.params, "message.messageId") === id);
const reply = sock.notifications.slice(from).filter((n) => n.method === "message_added" && pick(n.params, "message.role") === "assistant").at(-1);
console.log("echoed user content types", JSON.stringify((pick(userMsg?.params, "message.content") as { type: string }[] | undefined)?.map((c) => c.type)), "line", userMsg?.line);
console.log("assistant reply", JSON.stringify(pick(reply?.params, "message.content")).slice(0, 300), "line", reply?.line);
sock.close();
process.exit(0);
