import { appendFileSync } from "node:fs";

export default function (amp: any) {
  amp.registerCommand("spike-dialog", { title: "Spike dialog", category: "spike", description: "android-spike plugin UI wire probe" }, async (ctx: any) => {
    const confirmed = await ctx.ui.confirm({ title: "Spike confirm", message: "Allow the spike to continue?", confirmButtonText: "Allow" });
    const text = await ctx.ui.input({ title: "Spike input", helpText: "Type anything" });
    appendFileSync("/tmp/amp-scratch/s15-plugin.txt", JSON.stringify({ confirmed, text }) + "\n");
    await ctx.ui.notify(`spike-dialog confirmed=${confirmed} text=${text}`);
  });
}
