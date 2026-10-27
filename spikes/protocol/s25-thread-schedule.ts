import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { AMP, API_KEY, Capture, pick } from "./lib";

// S25: where a thread's scheduled run can be read, read-only, each call once, with the CLI Bearer key.
// `bun s25-thread-schedule.ts` writes the answers to /tmp/u57-probe only; `bun s25-thread-schedule.ts --scrub` writes
// captures/s25-thread-schedule.jsonl with every id, title, prompt, schedule, and error replaced and every time shifted.
const RAW = "/tmp/u57-probe/raw.json";

type Answer = { ch: string; method: "GET" | "POST"; path: string; json?: unknown; sent: string; status: number; contentType: string | null; body: unknown };

async function call(ch: string, method: "GET" | "POST", path: string, logged: string, json?: unknown): Promise<Answer> {
  const headers: Record<string, string> = { Authorization: `Bearer ${API_KEY}` };
  if (json) headers["Content-Type"] = "application/json";
  const sent = new Date().toISOString();
  const res = await fetch(`${AMP}${path}`, { method, headers, body: json ? JSON.stringify(json) : undefined, redirect: "manual" });
  const text = await res.text();
  let body: unknown = text.slice(0, 2000);
  try { body = JSON.parse(text); } catch {}
  console.log(ch, res.status, pick(body, "ok") ?? pick(body, "type") ?? typeof body);
  return { ch, method, path: logged, json, sent, status: res.status, contentType: res.headers.get("content-type"), body };
}

async function probe() {
  const list = await call("internal.listAutomations", "POST", "/api/internal?listAutomations", "/api/internal?listAutomations", { method: "listAutomations", params: {} });
  const automations = pick(list.body, "result.automations");
  const answers = [list];
  const target = Array.isArray(automations) ? pick(automations[0], "targetThreadID") : undefined;
  console.log("automations", Array.isArray(automations) ? automations.length : "none");
  if (typeof target === "string") {
    // SvelteKit's stringify_remote_arg: base64url of devalue's JSON, unpadded (web chunk bd6913a5.js).
    const payload = Buffer.from(JSON.stringify([{ threadID: 1 }, target])).toString("base64url");
    answers.push(await call("remote.1nb0kyl/getThreadAutomationDetails", "GET", `/_app/remote/1nb0kyl/getThreadAutomationDetails?payload=${payload}`, "/_app/remote/1nb0kyl/getThreadAutomationDetails?payload=<base64url devalue {threadID}>"));
  }
  mkdirSync("/tmp/u57-probe", { recursive: true });
  writeFileSync(RAW, JSON.stringify({ answers }, null, 2));
  chmodSync(RAW, 0o600);
}

const FAKE_EPOCH = Date.parse("2026-01-05T09:00:00.000Z");
const FAKE_BY_KEY: Record<string, (n: number, id: string) => string> = {
  id: (_, id) => `automation-${id}`,
  targetThreadID: (_, id) => `T-00000000-0000-4000-8000-0000${id}`,
  title: (n) => `Example schedule ${n}`,
  targetThreadTitle: (n) => `Example thread ${n}`,
  prompt: (n) => `Example prompt ${n}`,
  schedule: (n) => `0 ${8 + n} * * *`,
  scheduleLabel: (n) => `Every day at ${8 + n}:00`,
  lastRunError: (n) => `Example error ${n}`,
  slackChannelID: (n) => `C0000000${n}`,
  agentMode: (n) => `example-mode-${n}`,
};
const TIMES: Record<string, true> = { nextRunAt: true, lastRunAt: true, createdAt: true, updatedAt: true };

/** Ids and text become numbered fakes per key; every time moves by one offset, so the gaps between them stay. */
async function scrub() {
  const raw = await Bun.file(RAW).json();
  const times = raw.answers.flatMap((a: Answer) => JSON.stringify(a.body).match(/\d{4}-\d\d-\d\dT[\d:.]+(?:Z|[+-]\d\d:\d\d)/g) ?? []);
  const shift = times.length === 0 ? 0 : FAKE_EPOCH - Math.min(...times.map((t: string) => Date.parse(t)));
  const fakes = new Map<string, string>();
  const counts: Record<string, number> = {};
  const fake = (key: string, value: string) => {
    if (!fakes.has(value)) {
      counts[key] = (counts[key] ?? 0) + 1;
      fakes.set(value, FAKE_BY_KEY[key](counts[key], String(counts[key]).padStart(8, "0")));
    }
    return fakes.get(value)!;
  };
  const walk = (value: unknown, key = ""): unknown => {
    if (typeof value === "string" && FAKE_BY_KEY[key]) return fake(key, value);
    if (typeof value === "string" && TIMES[key]) return new Date(Date.parse(value) + shift).toISOString();
    if (Array.isArray(value)) return value.map((v) => walk(v, key));
    if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, walk(v, k)]));
    return value;
  };
  const cap = new Capture("s25-thread-schedule");
  for (const a of raw.answers as Answer[]) {
    cap.log(a.ch, "send", { method: a.method, url: `${AMP}${a.path}`, body: a.json });
    let text = JSON.stringify({ status: a.status, contentType: a.contentType, body: walk(a.body) });
    for (const [real, replacement] of fakes) text = text.split(real).join(replacement);
    cap.log(a.ch, "recv", JSON.parse(text));
  }
  console.log("scrubbed", fakes.size, "values", "shift", shift !== 0);
}

if (process.argv.includes("--scrub")) await scrub();
else await probe();
