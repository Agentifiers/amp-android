import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

export const AMP = (process.env.AMP_URL ?? "https://ampcode.com").replace(/\/+$/, "");
export const ACTORS = `${AMP}/actors`;
const CAPTURE_DIR = join(import.meta.dir, "captures");

export const API_KEY = process.env.AMP_API_KEY ?? "";
if (!API_KEY) throw new Error("set AMP_API_KEY to an Amp API key");

async function publishableRivetToken(): Promise<string> {
  const bin = Buffer.from(await Bun.file(process.env.AMP_CLI_BINARY ?? join(homedir(), ".amp/bin/amp")).arrayBuffer());
  const needle = Buffer.from('else t.password="pk_', "utf16le");
  const at = bin.indexOf(needle);
  if (at < 0) throw new Error("publishable rivet token not found in CLI binary");
  const tail = bin.subarray(at + needle.length - 6, at + needle.length + 200).toString("utf16le");
  return tail.match(/^pk_[A-Za-z0-9]+/)![0];
}
export const RIVET_PK = await publishableRivetToken();

const SECRET_KEYS = new Set(["wsToken", "apiKey", "orbToken", "token", "ingressToken", "authorization", "Authorization", "device_code", "access_token", "refresh_token", "password", "p256dh", "auth"]);
export function redact(value: unknown): unknown {
  const text = JSON.stringify(value, (k, v) => (SECRET_KEYS.has(k) && typeof v === "string" ? "<redacted>" : v));
  if (text === undefined) return value;
  return JSON.parse(
    text
      .split(API_KEY).join("<redacted-apikey>")
      .split(RIVET_PK).join("<rivet-pk>")
      .replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, "<redacted-jwt>")
      .replace(/eyJ[\w%-]{20,}/g, "<redacted-jwt>")
      .replace(/sgamp_[\w-]+/g, "<redacted-apikey>")
      .replace(/([?&](?:token|code|ticket|login|auth|sig|signature|x-goog-signature|x-goog-credential|upload_id)[\w-]*=)[^&"\s\\]+/gi, "$1<redacted>"),
  );
}

export class Capture {
  readonly file: string;
  line = 0;
  constructor(name: string) {
    mkdirSync(CAPTURE_DIR, { recursive: true });
    this.file = join(CAPTURE_DIR, `${name}.jsonl`);
    Bun.write(this.file, "");
  }
  log(ch: string, dir: "send" | "recv" | "note", data: unknown): number {
    this.line++;
    appendFileSync(this.file, JSON.stringify({ n: this.line, t: new Date().toISOString(), ch, dir, data: redact(data) }) + "\n");
    return this.line;
  }
}

export async function http(cap: Capture, ch: string, path: string, init: RequestInit & { json?: unknown } = {}) {
  const headers = new Headers(init.headers);
  if (!headers.has("Authorization")) headers.set("Authorization", `Bearer ${API_KEY}`);
  else if (headers.get("Authorization") === "") headers.delete("Authorization");
  let body = init.body;
  if (init.json !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(init.json);
  }
  const url = path.startsWith("http") ? path : `${AMP}${path}`;
  const method = init.method ?? (body ? "POST" : "GET");
  cap.log(ch, "send", { method, url, body: init.json ?? (typeof body === "string" ? body : body ? "<binary>" : undefined) });
  const res = await fetch(url, { ...init, method, headers, body });
  const ct = res.headers.get("content-type") ?? "";
  const raw = await res.arrayBuffer();
  let parsed: unknown = undefined;
  const text = new TextDecoder().decode(raw);
  if (ct.includes("json")) {
    try { parsed = JSON.parse(text); } catch { parsed = text; }
  } else parsed = text.length > 4000 ? `${text.slice(0, 4000)}...<${raw.byteLength} bytes>` : text;
  const line = cap.log(ch, "recv", { status: res.status, contentType: ct, etag: res.headers.get("etag") ?? undefined, body: parsed });
  return { status: res.status, body: parsed, raw: new Uint8Array(raw), headers: res.headers, line };
}

export async function internal(cap: Capture, method: string, params: Record<string, unknown> = {}) {
  const r = await http(cap, `internal.${method}`, `/api/internal?${method}`, { method: "POST", json: { method, params } });
  return { ...r, ok: pick(r.body, "ok") === true, result: pick(r.body, "result"), error: pick(r.body, "error") };
}

export function gatewayURL(actor: string, path: string, key: string): string {
  const q = new URLSearchParams();
  q.append("rvt-namespace", "default");
  q.append("rvt-method", "get");
  q.append("rvt-key", key);
  q.append("rvt-token", RIVET_PK);
  q.append("rvt-skip-ready-wait", "true");
  return `${ACTORS}/gateway/${actor}${path}?${q}`;
}

export function rivetProtocols(encoding: "bare" | "json", params: unknown): string[] {
  return ["rivet", `rivet_encoding.${encoding}`, "rivet_skip_ready_wait", `rivet_conn_params.${encodeURIComponent(JSON.stringify(params))}`];
}

const B62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
export function messageId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(22));
  return "M-" + Array.from(bytes, (b) => B62[b % 62]).join("");
}

export async function threadCredentials(cap: Capture, body: Record<string, unknown>) {
  const r = await http(cap, "thread-actors", "/api/thread-actors", { method: "POST", json: { usesThreadActors: true, ...body } });
  if (r.status >= 300) throw new Error(`thread-actors ${r.status} ${JSON.stringify(redact(r.body))}`);
  return r.body as { threadId: string; wsToken: string; poolName?: string; threadVersion?: number; capability?: string }; // shape per CLI Y$i; checked by status 200
}

export async function userCredentials(cap: Capture, capability: "read" | "write" = "read") {
  const r = await http(cap, "user-actor-credentials", "/api/user-actor-credentials", { method: "POST", json: { capability } });
  if (r.status >= 300) throw new Error(`user-actor-credentials ${r.status}`);
  return r.body as { userId: string; wsToken: string; poolName: string };
}

export async function userAction(cap: Capture, creds: { userId: string; wsToken: string }, name: string, args: unknown[]) {
  const r = await http(cap, `userActor.action.${name}`, gatewayURL("userActor", `/action/${encodeURIComponent(name)}`, creds.userId), {
    method: "POST",
    headers: { "x-rivet-encoding": "json", "x-rivet-conn-params": JSON.stringify({ type: "user", wsToken: creds.wsToken }), Authorization: "" },
    json: { args },
  });
  return r;
}

type JsonRpcFrame = { id?: string; type?: string; method?: string; params?: Record<string, unknown>; result?: unknown; error?: { message: string } }; // wire frames from the actor; fields are checked before use
export type Notification = { method: string; params: Record<string, unknown>; at: number; line: number };

export const RELAY_MAGIC = 0xae;
export function encodeRelayFrame(kind: 1 | 2, channelId: string, payload: Uint8Array, opts: { binary: boolean; fin?: boolean }): Uint8Array {
  const id = new TextEncoder().encode(channelId);
  const out = new Uint8Array(5 + id.length + payload.length);
  out.set([RELAY_MAGIC, 0x01, kind, (opts.binary ? 1 : 0) | (opts.fin === false ? 0 : 2), id.length]);
  out.set(id, 5);
  out.set(payload, 5 + id.length);
  return out;
}
export function decodeRelayFrame(buf: Uint8Array) {
  if (buf[0] !== RELAY_MAGIC || buf[1] !== 0x01) return null;
  const idLen = buf[4];
  return {
    kind: buf[2],
    binary: (buf[3] & 1) === 1,
    fin: (buf[3] & 2) === 2,
    channelId: new TextDecoder().decode(buf.subarray(5, 5 + idLen)),
    payload: buf.subarray(5 + idLen),
  };
}

export class ThreadSocket {
  ws!: WebSocket;
  notifications: Notification[] = [];
  binary: { at: number; bytes: Uint8Array }[] = [];
  private pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: unknown) => void; method: string }>();
  private counter = 0;
  private listeners = new Set<(n: Notification) => void>();
  private binaryListeners = new Set<(b: Uint8Array) => void>();
  closed: { code: number; reason: string } | null = null;

  constructor(readonly cap: Capture, readonly threadId: string, readonly params: Record<string, unknown>, readonly ch = "thread") {}

  open(): Promise<void> {
    const url = gatewayURL("threadActor", "/websocket/", this.threadId);
    const protocols = rivetProtocols("bare", this.params);
    this.cap.log(this.ch, "note", { open: url, protocols });
    this.ws = new WebSocket(url, protocols);
    this.ws.binaryType = "arraybuffer";
    const { promise, resolve, reject } = Promise.withResolvers<void>();
    {
      this.ws.onopen = () => {
        this.cap.log(this.ch, "note", { opened: true, protocol: this.ws.protocol });
        resolve();
      };
      this.ws.onerror = (e) => {
        this.cap.log(this.ch, "note", { error: String(pick(e, "message") ?? e) });
        reject(new Error("ws error"));
      };
      this.ws.onclose = (e) => {
        this.closed = { code: e.code, reason: e.reason };
        this.cap.log(this.ch, "note", { closed: this.closed });
        for (const p of this.pending.values()) p.reject(new Error(`socket closed ${e.code} ${e.reason}`));
        this.pending.clear();
      };
      this.ws.onmessage = (e) => this.onMessage(e.data);
    }
    return promise;
  }

  private onMessage(data: string | ArrayBuffer) {
    if (typeof data !== "string") {
      const bytes = new Uint8Array(data);
      const relay = decodeRelayFrame(bytes);
      this.cap.log(this.ch, "recv", relay
        ? { binary: true, relay: { kind: relay.kind, binary: relay.binary, fin: relay.fin, channelId: relay.channelId, len: relay.payload.length, text: relay.binary ? undefined : new TextDecoder().decode(relay.payload) } }
        : { binary: true, len: bytes.length });
      this.binary.push({ at: Date.now(), bytes });
      for (const l of this.binaryListeners) l(bytes);
      return;
    }
    let msg: unknown;
    try { msg = JSON.parse(data); } catch {
      this.cap.log(this.ch, "recv", { rawText: data });
      return;
    }
    for (const m of (Array.isArray(msg) ? msg : [msg]) as JsonRpcFrame[]) {
      const line = this.cap.log(this.ch, "recv", m);
      if (m.id !== undefined && (m.result !== undefined || m.error !== undefined)) {
        const p = this.pending.get(m.id);
        if (!p) continue;
        this.pending.delete(m.id);
        if (m.error) p.reject(Object.assign(new Error(`${p.method}: ${m.error.message}`), { rpc: m.error, line }));
        else p.resolve(m.result);
      } else if (m.method || m.type) {
        const { type, ...rest } = m;
        const n = m.method ? { method: m.method, params: m.params ?? {}, at: Date.now(), line } : { method: String(type), params: { ...rest, bareFrame: true }, at: Date.now(), line };
        this.notifications.push(n);
        for (const l of this.listeners) l(n);
      }
    }
  }

  request(type: string, params: Record<string, unknown> = {}, timeoutMs = 120_000): Promise<unknown> {
    const id = `thread-client-${Date.now()}-${this.counter++}`;
    const frame = { jsonrpc: "2.0", id, method: type, params };
    this.cap.log(this.ch, "send", frame);
    this.ws.send(JSON.stringify(frame));
    const { promise, resolve, reject } = Promise.withResolvers<unknown>();
    const timer = setTimeout(() => {
      this.pending.delete(id);
      reject(new Error(`${type} timed out`));
    }, timeoutMs);
    this.pending.set(id, {
      method: type,
      resolve: (v) => { clearTimeout(timer); resolve(v); },
      reject: (e) => { clearTimeout(timer); reject(e); },
    });
    return promise;
  }

  notify(type: string, params: Record<string, unknown> = {}) {
    const frame = { jsonrpc: "2.0", method: type, params };
    this.cap.log(this.ch, "send", frame);
    this.ws.send(JSON.stringify(frame));
  }

  sendBinary(bytes: Uint8Array, describe: unknown) {
    this.cap.log(this.ch, "send", { binary: true, ...(describe as object) });
    this.ws.send(bytes);
  }

  on(fn: (n: Notification) => void) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  onBinary(fn: (b: Uint8Array) => void) { this.binaryListeners.add(fn); return () => this.binaryListeners.delete(fn); }

  waitFor(pred: (n: Notification) => boolean, timeoutMs: number, label: string, fromIndex = 0): Promise<Notification> {
    const hit = this.notifications.slice(fromIndex).find(pred);
    if (hit) return Promise.resolve(hit);
    const { promise, resolve, reject } = Promise.withResolvers<Notification>();
    const timer = setTimeout(() => { off(); reject(new Error(`timeout waiting for ${label}`)); }, timeoutMs);
    const off = this.on((n) => { if (pred(n)) { clearTimeout(timer); off(); resolve(n); } });
    return promise;
  }

  close() { try { this.ws.close(1000, "spike done"); } catch {} }
}

export function sleep(ms: number) { return Bun.sleep(ms); }

export function pick(v: unknown, path: string): unknown {
  let cur = v;
  for (const k of path.split(".")) {
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[k];
  }
  return cur;
}

export function counts(items: string[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const i of items) out[i] = (out[i] ?? 0) + 1;
  return Object.fromEntries(Object.entries(out).sort((a, b) => b[1] - a[1]));
}

export async function saveState(patch: Record<string, unknown>) {
  const f = Bun.file(join(import.meta.dir, "captures", "state.json"));
  const cur = (await f.exists()) ? await f.json() : {};
  await Bun.write(f, JSON.stringify({ ...cur, ...patch }, null, 2));
}
export async function loadState(): Promise<{ threads?: string[]; [k: string]: unknown }> {
  const f = Bun.file(join(import.meta.dir, "captures", "state.json"));
  return (await f.exists()) ? await f.json() : {};
}
