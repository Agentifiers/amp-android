import { Capture, loadState, pick } from "./lib";
import { rpcCall } from "./s06-rpc";

const cap = new Capture("s18b-rpc-allowlist");
const threadId = process.argv[2] ?? String((await loadState()).approvalThread);
const METHODS = [
  "client_append_user_msg", "client_tool_approval_response", "client_cancel", "client_retry", "client_set_thread_title",
  "client_upsert_notification_subscription", "client_mark_thread_unread", "client_mark_message_read", "client_dismiss_active_error",
  "client_remove_queued_msg", "client_steer_queued_msg", "client_update_thread_settings", "client_set_thread_open",
  "client_resume", "client_spawn_executor", "client_filesystem_read_directory_v2", "client_git_command", "client_reauthorize",
];
const rows: Record<string, string> = {};
for (const method of METHODS) {
  const r = await rpcCall(cap, threadId, method, { probe: true });
  rows[method] = `${r.status} ${pick(r.body, "error.code") ?? "ok"} ${pick(r.body, "error.message") ?? JSON.stringify(pick(r.body, "result"))}`.slice(0, 160);
  console.log(method.padEnd(42), rows[method], "line", r.line);
}
cap.log("verdict", "note", rows);
process.exit(0);
