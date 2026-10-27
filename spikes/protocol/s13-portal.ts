import { Capture, http, internal, loadState, pick } from "./lib";

const PORT = 8765;
const cap = new Capture("s13-portal");
const threadId = String((await loadState()).runnerThread);
const local = await fetch(`http://127.0.0.1:${PORT}/`).then((r) => r.status).catch(() => "down");
console.log("local http.server", local);

const resolved = await internal(cap, "resolveThreadPortalURL", { threadID: threadId, port: PORT });
console.log("resolveThreadPortalURL", resolved.status, JSON.stringify(resolved.result ?? resolved.error), "line", resolved.line);
const portalURL = String(pick(resolved.result, "url") ?? "");

const anon = portalURL ? await fetch(portalURL, { redirect: "manual" }) : null;
cap.log("portal.anon", "recv", { status: anon?.status, location: anon?.headers.get("location") });
console.log("anonymous GET portal", anon?.status, anon?.headers.get("location"));

const minted = await internal(cap, "mintPortalLoginURL", { url: portalURL || `https://example.invalid/` });
console.log("mintPortalLoginURL", minted.status, JSON.stringify(redactQuery(minted.result ?? minted.error)), "line", minted.line);
const embed = await http(cap, "portal-auth.embed", "/api/portal-auth/embed", { method: "POST", json: { url: portalURL } });
console.log("POST /api/portal-auth/embed (Bearer)", embed.status, JSON.stringify(redactQuery(embed.body)).slice(0, 300), "line", embed.line);

const loginURL = pick(minted.result, "url");
if (typeof loginURL === "string") {
  let url = loginURL;
  const cookies: string[] = [];
  for (let hop = 0; hop < 8; hop++) {
    const r = await fetch(url, { redirect: "manual", headers: cookies.length ? { Cookie: cookies.join("; ") } : {} });
    for (const c of r.headers.getSetCookie()) cookies.push(c.split(";")[0]);
    const loc = r.headers.get("location");
    const body = r.status === 200 ? await r.text() : "";
    cap.log("portal.login.hop", "recv", { hop, status: r.status, host: new URL(url).host, path: new URL(url).pathname, location: loc ? new URL(loc, url).host + new URL(loc, url).pathname : null, setCookieNames: r.headers.getSetCookie().map((c) => c.split("=")[0]), bodyStart: body.slice(0, 200) });
    console.log(`hop ${hop}: ${r.status} ${new URL(url).host}${new URL(url).pathname} -> ${loc ? new URL(loc, url).host + new URL(loc, url).pathname : "(end)"}`);
    if (!loc) { console.log("final body starts:", JSON.stringify(body.slice(0, 160))); break; }
    url = new URL(loc, url).toString();
  }
}

function redactQuery(v: unknown) {
  return JSON.parse(JSON.stringify(v ?? null).replace(/([?&](?:token|code|ticket|t|auth|login)[^=]*=)[^&"]+/gi, "$1<redacted>"));
}
process.exit(0);
