import { Capture, internal, loadState, pick } from "./lib";

const cap = new Capture("teardown2");
const state = await loadState();
for (const thread of [...new Set([...(state.threads ?? []), String(state.approvalThread)])]) {
  const cost = await internal(cap, "threadDisplayCostInfo", { threadID: thread, markdown: false });
  const labels = await internal(cap, "getThreadLabels", { thread });
  const archived = await internal(cap, "archiveThread", { thread, archived: true });
  console.log(thread, "cost", JSON.stringify(cost.result).slice(0, 200), "labels", JSON.stringify(labels.result), "archive ok", archived.ok, "lines", cost.line, labels.line, archived.line);
}
const runners = await internal(cap, "listRunners", {});
console.log("listRunners", JSON.stringify(pick(runners.result, "runners") ?? runners.result), "line", runners.line);
process.exit(0);
