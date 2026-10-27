import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { AMP, API_KEY, Capture, pick } from "./lib";

// S24: where the self-hosted orb providers the web's New Thread picker lists can come from, read-only, each call once,
// all with the CLI Bearer key. `bun s24-orb-providers.ts <orb thread id>` writes the answers to /tmp/u55-probe only;
// `bun s24-orb-providers.ts --scrub` then writes captures/s24-orb-providers.jsonl with the workspace id replaced.
const RAW = "/tmp/u55-probe/raw.json";

type Probe = { ch: string; method: "GET" | "POST"; path: string; json?: unknown; logged: string };

/** devalue's `parse` for the plain shapes a remote query returns: objects, arrays, primitives, undefined (-1). */
function devalueParse(text: string): unknown {
  const table: unknown[] = JSON.parse(text);
  const hydrate = (index: number): unknown => {
    if (index === -1) return undefined;
    const value = table[index];
    if (Array.isArray(value)) {
      return typeof value[0] === "string" ? { $devalue: value[0], args: value.slice(1) } : value.map((i) => hydrate(Number(i)));
    }
    if (value !== null && typeof value === "object") {
      return Object.fromEntries(Object.entries(value).map(([k, i]) => [k, hydrate(Number(i))]));
    }
    return value;
  };
  return hydrate(0);
}

async function probe(orbThread: string) {
  const info: unknown = await fetch(`${AMP}/api/internal?getUserInfo`, {
    method: "POST",
    headers: { Authorization: `Bearer ${API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ method: "getUserInfo", params: {} }),
  }).then((r) => r.json());
  const workspaceID = pick(info, "result.team.id");
  if (typeof workspaceID !== "string") throw new Error("getUserInfo has no team id");
  // SvelteKit's stringify_remote_arg: base64url of devalue's JSON, unpadded (web chunk bd6913a5.js).
  const payload = Buffer.from(JSON.stringify([{ workspaceID: 1 }, workspaceID])).toString("base64url");
  const probes: Probe[] = [
    { ch: "remote.1igxvbu/getWorkspaceSelfHostedOrbs", method: "GET", path: `/_app/remote/1igxvbu/getWorkspaceSelfHostedOrbs?payload=${payload}`, logged: "/_app/remote/1igxvbu/getWorkspaceSelfHostedOrbs?payload=<base64url devalue {workspaceID}>" },
    { ch: "remote.18dvixz/getPersonalOrbProviders", method: "GET", path: "/_app/remote/18dvixz/getPersonalOrbProviders", logged: "/_app/remote/18dvixz/getPersonalOrbProviders" },
    { ch: "internal.getThreadOrbProviderConfig", method: "POST", path: "/api/internal?getThreadOrbProviderConfig", json: { method: "getThreadOrbProviderConfig", params: {} }, logged: "/api/internal?getThreadOrbProviderConfig" },
    { ch: "thread-actors.sandbox-status", method: "GET", path: `/api/thread-actors/${orbThread}/sandbox-status`, logged: "/api/thread-actors/<orb thread>/sandbox-status" },
  ];
  const answers = [];
  for (const p of probes) {
    const headers: Record<string, string> = { Authorization: `Bearer ${API_KEY}` };
    if (p.json) headers["Content-Type"] = "application/json";
    const sent = new Date().toISOString();
    const res = await fetch(`${AMP}${p.path}`, { method: p.method, headers, body: p.json ? JSON.stringify(p.json) : undefined, redirect: "manual" });
    const text = await res.text();
    let body: unknown = text.slice(0, 2000);
    try { body = JSON.parse(text); } catch {}
    const encoded = pick(body, "type") === "result" ? pick(body, "result") : undefined;
    answers.push({ ...p, path: p.logged, sent, status: res.status, contentType: res.headers.get("content-type"), body, decoded: typeof encoded === "string" ? devalueParse(encoded) : undefined });
    console.log(p.ch, res.status, pick(body, "type") ?? pick(body, "error.code") ?? pick(body, "error") ?? typeof body);
  }
  mkdirSync("/tmp/u55-probe", { recursive: true });
  writeFileSync(RAW, JSON.stringify({ workspaceID, answers }, null, 2));
  chmodSync(RAW, 0o600);
}

/** Every provider name and id becomes a fake one, numbered in order of first appearance per key. */
async function scrub() {
  const raw = await Bun.file(RAW).json();
  const fakes = new Map<string, string>([[raw.workspaceID, "workspace-00000001"]]);
  const counts: Record<string, number> = {};
  const IDENTIFYING: Record<string, true> = { configID: true, id: true, name: true, workspaceID: true };
  const walk = (value: unknown, key = ""): unknown => {
    if (typeof value === "string" && IDENTIFYING[key]) {
      if (!fakes.has(value)) {
        counts[key] = (counts[key] ?? 0) + 1;
        fakes.set(value, key === "name" ? `example-orbs-${counts[key]}` : `${key}-0000000${counts[key]}`);
      }
      return fakes.get(value);
    }
    if (Array.isArray(value)) return value.map((v) => walk(v, key));
    if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, walk(v, k)]));
    return value;
  };
  const cap = new Capture("s24-orb-providers");
  for (const a of raw.answers) {
    cap.log(a.ch, "send", { method: a.method, url: `${AMP}${a.path}`, body: a.json, sentAt: a.sent });
    let text = JSON.stringify({ status: a.status, contentType: a.contentType, body: a.body, decoded: walk(a.decoded) });
    for (const [real, replacement] of fakes) text = text.split(real).join(replacement);
    cap.log(a.ch, "recv", JSON.parse(text));
  }
  console.log("scrubbed", fakes.size, "values");
}

if (process.argv.includes("--scrub")) await scrub();
else await probe(process.argv[2] ?? "T-01a178ef-1d5b-7446-802c-8e6d5bd8c5b0");
