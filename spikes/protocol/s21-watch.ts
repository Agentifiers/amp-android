import { Capture, internal, pick, userAction, userCredentials } from "./lib";

// Waits, without a socket, for one of the user's orb threads to report its orb asleep, then records it read-only with
// s21-orb-sleep.ts and sends the one allowed wake with s21-orb-sleep.ts --wake.
// Usage: bun s21-watch.ts [minutes] [preferred thread title…]
const PREFERRED = process.argv.slice(3);
const rank = (title?: string) => (PREFERRED.includes(title ?? "") ? PREFERRED.indexOf(title ?? "") : PREFERRED.length);
const deadline = Date.now() + Number(process.argv[2] ?? 85) * 60_000;
const cap = new Capture("s21-watch");

const list = await internal(cap, "listThreads", { limit: 100 });
const orbs = ((pick(list.result, "threads") as { id: string; title?: string; executorType?: string; archived?: boolean }[] | undefined) ?? [])
  .filter((t) => t.executorType === "sandbox" && !t.archived)
  .sort((a, b) => rank(a.title) - rank(b.title));
console.log("orb threads", orbs.length);

let asleep: string | undefined;
while (!asleep && Date.now() < deadline) {
  const user = await userCredentials(cap, "read");
  const recent = await userAction(cap, user, "getRecentThreads", [{ limit: 100, sinceMs: Date.now() - 7 * 864e5 + 0.5 }]);
  const rows = (pick(recent.body, "output") as { threadId: string; executorConnected?: boolean }[] | undefined) ?? [];
  const connected = new Map(rows.map((r) => [r.threadId, r.executorConnected]));
  asleep = orbs.find((t) => connected.get(t.id) === false)?.id;
  console.log(new Date().toISOString(), "awake", orbs.filter((t) => connected.get(t.id) === true).length, "asleep", asleep ?? "none");
  if (!asleep) await Bun.sleep(60_000);
}
await Bun.file(cap.file).delete();
if (!asleep) {
  console.log("no orb slept before the deadline");
  process.exit(0);
}
for (const args of [[asleep], [asleep, "--wake"]]) {
  const run = Bun.spawnSync(["bun", `${import.meta.dir}/s21-orb-sleep.ts`, ...args], { stdout: "inherit", stderr: "inherit" });
  if (run.exitCode !== 0) process.exit(run.exitCode);
}
process.exit(0);
