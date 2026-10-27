import { $ } from "bun";
import { Capture, loadState } from "./lib";

const cap = new Capture("s15a-routing-blocker");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const list = await $`amp config model-providers list --workspace --json`.nothrow().quiet();
const providers = JSON.parse(list.stdout.toString()) as { id: string }[];
cap.log("model-providers.list.workspace", "recv", providers);
for (const p of providers) {
  const show = await $`amp config model-providers show ${p.id} --workspace`.nothrow().quiet();
  cap.log("model-providers.show.workspace", "recv", JSON.parse(show.stdout.toString()));
}
const check = await $`amp config model-providers check-access --provider-model zhipuai/glm-5.3-flash --thread ${threadId}`.nothrow().quiet();
const result = JSON.parse(check.stdout.toString());
const line = cap.log("model-providers.check-access", "recv", result);
console.log("check-access zhipuai/glm-5.3-flash (agent mode low)", JSON.stringify(result.error ?? result).slice(0, 300), "line", line);
process.exit(0);
