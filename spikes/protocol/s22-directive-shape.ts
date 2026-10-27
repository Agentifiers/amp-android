import { internal, pick, type Capture } from "./lib";

// Prints only the structure of each `:::` directive in one thread's assistant text; no text, URL, or file name leaves
// this process, so the capture is a no-op instead of captures/<name>.jsonl.
const quiet = { log: () => 0 } as unknown as Capture;
const title = process.argv[2];
if (!title) throw new Error("usage: bun s22-directive-shape.ts <thread title>");

const list = await internal(quiet, "listThreads", { limit: 100 });
const threads = (pick(list.result, "threads") as { id: string; title?: string }[] | undefined) ?? [];
const thread = threads.find((t) => t.title === title);
if (!thread) throw new Error(`listThreads ${list.status}: no thread with that title among ${threads.length}`);

const messages: { role?: string; content?: { type?: string; text?: string }[] }[] = [];
for (let fromIndex: unknown = 0; typeof fromIndex === "number"; ) {
  const page = await internal(quiet, "getThreadMessagesPage", { thread: thread.id, fromIndex, limit: 50 });
  messages.push(...((pick(page.result, "messages") as typeof messages | undefined) ?? []));
  fromIndex = pick(page.result, "nextMessageIndex");
}
console.log("messages", messages.length);

function urlShape(url: string): string {
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(url)?.[1];
  const path = scheme ? url.replace(/^[a-z][a-z0-9+.-]*:\/*[^/]*/i, "") : url;
  const host = scheme ? /^[a-z][a-z0-9+.-]*:\/*([^/?#]*)/i.exec(url)?.[1] ?? "" : "";
  const hostKind = !scheme ? "none" : /(^|\.)ampcode\.com$/.test(host) ? host : host === "localhost" || /^127\./.test(host) ? "loopback" : "other";
  const parts = path.split(/[?#]/)[0].split("/").filter(Boolean);
  const ext = /\.([a-z0-9]{1,5})(?:[?#]|$)/i.exec(path)?.[1]?.toLowerCase() ?? "none";
  const route = parts.map((p, i) => (i < parts.length - 1 && /^[a-z-]{2,20}$/.test(p) ? p : `<${p.length}ch ${/^[0-9a-f-]+$/i.test(p.replace(/\.[a-z0-9]+$/i, "")) ? "hex" : "mixed"}>`)).join("/");
  return `scheme=${scheme ?? "relative"} host=${hostKind} absolutePath=${path.startsWith("/")} path=/${route} ext=${ext} query=${path.includes("?")}`;
}

function lineShape(line: string): string {
  if (!line.trim()) return "blank";
  const image = /^\s*!\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)\s*$/.exec(line);
  if (image) return `image alt=${image[1].length}ch title=${image[3] === undefined ? "none" : `${image[3].length}ch`} ${urlShape(image[2])}`;
  const link = /^\s*\[([^\]]*)\]\(([^)\s]+)\)\s*$/.exec(line);
  if (link) return `link text=${link[1].length}ch ${urlShape(link[2])}`;
  if (/^\s*:{3,}/.test(line)) return `fence ${JSON.stringify(line.trim().replace(/[^\s:{}=a-z-]/gi, "?"))}`;
  return `text ${line.length}ch indent=${line.length - line.trimStart().length}`;
}

messages.forEach((m, index) => {
  for (const block of m.content ?? []) {
    if (block.type !== "text" || !block.text?.includes(":::")) continue;
    const lines = block.text.split("\n");
    console.log(`message ${index} role=${m.role} text lines=${lines.length}`);
    let inside = false;
    lines.forEach((line, i) => {
      const fence = /^\s*:{3,}/.test(line);
      if (fence || inside) console.log(`  ${i}: ${lineShape(line)}`);
      if (fence) inside = /^\s*:{3,}\s*[a-z]/i.test(line);
      else if (line.includes(":::")) console.log(`  ${i}: mid-line ::: at col ${line.indexOf(":::")}`);
    });
  }
});
