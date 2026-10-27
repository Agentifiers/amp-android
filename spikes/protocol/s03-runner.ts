import { Capture, internal, pick, userAction, userCredentials } from "./lib";

const cap = new Capture("s03-runner");
const creds = await userCredentials(cap, "read");
const ua = await userAction(cap, creds, "listRunners", []);
const uaRunners = (pick(ua.body, "output") as Record<string, unknown>[] | undefined) ?? [];
console.log("userActor listRunners", ua.status, uaRunners.map((r) => `${r.runnerId ?? r.id}@${r.workingDirectory ?? "?"}`), "keys", Object.keys(uaRunners[0] ?? {}), "line", ua.line);
const internalRunners = await internal(cap, "listRunners", {});
console.log("internal listRunners", internalRunners.status, JSON.stringify(internalRunners.result), "line", internalRunners.line);
