import { createDecipheriv, createECDH, createHmac, randomBytes } from "node:crypto";
import { type Capture, pick } from "./lib";

const hmac = (key: Buffer, data: Buffer) => createHmac("sha256", key).update(data).digest();

export type NtfyMessage = Record<string, unknown>;

export async function ntfyWebPush(cap: Capture) {
  const topic = `up${randomBytes(6).toString("hex")}`;
  const endpoint = `https://ntfy.sh/${topic}`;
  const ecdh = createECDH("prime256v1");
  ecdh.generateKeys();
  const uaPublic = ecdh.getPublicKey();
  const authSecret = randomBytes(16);
  const streamed: NtfyMessage[] = [];

  function decrypt(body: Buffer) {
    const salt = body.subarray(0, 16);
    const rs = body.readUInt32BE(16);
    const idlen = body[20];
    const asPublic = body.subarray(21, 21 + idlen);
    const ciphertext = body.subarray(21 + idlen);
    const ikm = hmac(hmac(authSecret, ecdh.computeSecret(asPublic)), Buffer.concat([Buffer.from("WebPush: info\0"), uaPublic, asPublic, Buffer.from([1])]));
    const prk = hmac(salt, ikm);
    const cek = hmac(prk, Buffer.from("Content-Encoding: aes128gcm\0\x01")).subarray(0, 16);
    const nonce = hmac(prk, Buffer.from("Content-Encoding: nonce\0\x01")).subarray(0, 12);
    const d = createDecipheriv("aes-128-gcm", cek, nonce);
    d.setAuthTag(ciphertext.subarray(ciphertext.length - 16));
    const plain = Buffer.concat([d.update(ciphertext.subarray(0, ciphertext.length - 16)), d.final()]);
    let end = plain.length - 1;
    while (end >= 0 && plain[end] === 0) end--;
    return { rs, plaintext: plain.subarray(0, end).toString("utf8"), delimiter: plain[end] };
  }

  async function decryptMessage(m: NtfyMessage) {
    let raw: Buffer;
    if (m.encoding === "base64") raw = Buffer.from(String(m.message), "base64");
    else if (pick(m, "attachment.url")) raw = Buffer.from(await (await fetch(String(pick(m, "attachment.url")))).arrayBuffer());
    else raw = Buffer.from(String(m.message), "utf8");
    const dec = decrypt(raw);
    const payload = JSON.parse(dec.plaintext) as Record<string, unknown>;
    const line = cap.log("push", "note", { decrypted: payload, recordSize: dec.rs, ntfyTime: m.time });
    return { payload, line };
  }

  const sub = await fetch(`https://ntfy.sh/${topic}/json`);
  cap.log("ntfy.subscribe", "recv", { status: sub.status, topic });
  (async () => {
    const reader = sub.body!.getReader();
    let buf = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += new TextDecoder().decode(value);
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl);
        buf = buf.slice(nl + 1);
        if (!line) continue;
        const m = JSON.parse(line) as NtfyMessage;
        cap.log("ntfy.stream", "recv", { ...m, message: m.message === undefined ? undefined : `<${String(m.message).length} chars>` });
        streamed.push(m);
      }
    }
  })();
  await Bun.sleep(1500);

  async function nextMessage(fromIndex: number, timeoutMs: number) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const hit = streamed.slice(fromIndex).find((m) => m.event === "message");
      if (hit) return hit;
      await Bun.sleep(500);
    }
    return null;
  }

  return {
    topic,
    endpoint,
    subscription: { endpoint, keys: { auth: authSecret.toString("base64url"), p256dh: uaPublic.toString("base64url") } },
    streamed,
    nextMessage,
    decryptMessage,
  };
}
