import { Capture, gatewayURL, http, pick, userCredentials } from "./lib";
const cap = new Capture("probe-json-int");
const creds = await userCredentials(cap, "read");
const now = Date.now() - 86400000;
for (const body of [`{"args":[{"limit":5,"sinceMs":${now}.0}]}`, `{"args":[{"limit":5,"sinceMs":2147483647}]}`, `{"args":[{"limit":5,"sinceMs":2147483648}]}`, `{"args":[{"limit":5,"sinceMs":4294967296}]}`]) {
  const r = await http(cap, "raw", gatewayURL("userActor", "/action/getRecentThreads", creds.userId), { method: "POST", headers: { "x-rivet-encoding": "json", "x-rivet-conn-params": JSON.stringify({ type: "user", wsToken: creds.wsToken }), Authorization: "", "Content-Type": "application/json" }, body });
  const out = pick(r.body, "output");
  console.log(body, r.status, Array.isArray(out) ? `count ${out.length}` : JSON.stringify(r.body).slice(0, 120));
}
