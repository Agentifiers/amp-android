import { Capture, http, pick } from "./lib";

const cap = new Capture("s01-auth");
const cfg = await http(cap, "oauth-config", "/api/auth/oauth-config", { headers: { Authorization: "" } });
console.log("oauth-config", cfg.status, cfg.body);
const clientID = String(pick(cfg.body, "clientID"));
const apiURL = String(pick(cfg.body, "apiURL"));

const device = await http(cap, "device-authorize", `${apiURL}/user_management/authorize/device`, {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded", Authorization: "" },
  body: new URLSearchParams({ client_id: clientID }).toString(),
});
const b = device.body;
console.log("device authorize", device.status, {
  user_code: pick(b, "user_code"),
  verification_uri: pick(b, "verification_uri"),
  verification_uri_complete: pick(b, "verification_uri_complete"),
  expires_in: pick(b, "expires_in"),
  interval: pick(b, "interval"),
  has_device_code: typeof pick(b, "device_code") === "string",
  deviceLoginURL: pick(cfg.body, "deviceLoginPath") ? `https://ampcode.com${pick(cfg.body, "deviceLoginPath")}?user_code=${pick(b, "user_code")}` : undefined,
});

const poll = await http(cap, "device-poll-once", `${apiURL}/user_management/authenticate`, {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded", Authorization: "" },
  body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code: String(pick(b, "device_code")), client_id: clientID }).toString(),
});
console.log("single poll (expect authorization_pending)", poll.status, poll.body);
console.log("capture", cap.file);
