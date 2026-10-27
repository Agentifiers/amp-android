# Git and diffs

This document defines how the app reads a thread's Git state, renders diffs in the Changes pane, and turns review into messages to the agent. It covers the sources of change data, the diff model the app computes from file contents, the Changes pane, per-line and per-hunk comments, the Ship, Review, Restack, Rebase, Push to Branch, and Fix CI flows, pull request and CI status, commit-to-thread links, and the project facts these features read. Pane placement is in `layout.md`. Test rules are in `verification.md`. The screen inventory in `screens.md` links here for the Changes pane.

The doc mixes two modes. The tables and wire names are reference. The sections that say why the app makes a choice are explanation.

Cites point at `spikes/` and `docs/protocol/`, or name Amp's docs or ampcode.com's web bundle. The web bundle is minified, so each bundle cite names a string or function to search for. Where the bundle code is ambiguous, the claim carries [INFERENCE].

## Modules that own this work

Behavior comes first in this document. Module names appear where they decide who owns a rule.

| Module | Owns | Source |
|---|---|---|
| `:sync` | Every wire call below, behind `Machine.changes`. Picks the change source, refreshes on `executor_workspace_maybe_changed`, and keeps duplicate git results from reaching features. | `docs/design/sketch/settings.gradle.kts`, `:sync` block. `docs/design/sketch/machine/ChangesFiles.kt`, `ChangesTab` |
| `:core:model` | `ChangesView`, `FileChange`, `FileDiffView`, `Hunk`, `DiffLine`, `DiffSelection`. Pure Kotlin. | `docs/design/sketch/core-model/Machine.kt` |
| `:feature:changes` | The Changes pane, the diff viewer, comments, staging, and the action buttons. It talks to the thread only through `ThreadIntent`. | `docs/design/sketch/settings.gradle.kts`, `:feature:changes` block |
| `:core:design` | The monospace diff line primitive and test tags. | `docs/design/sketch/settings.gradle.kts`, `:core:design` block |
| `:store` | The per-account cache of change snapshots, blobs, file orders, and comment drafts. | `docs/design/sketch/settings.gradle.kts`, `:store` block |

## Where change data comes from

### Four sources

| Source | The app uses it when | Wire | Tier and spike verdict | It returns |
|---|---|---|---|---|
| Live git | The thread's executor is connected: a runner, a CLI executor, or an awake orb. | `client_git_command{requestId, operation}` and `client_git_diff_snapshot{requestId, baseRevision, headRevision, includeUntracked, content, repositoryRoot?}` on the thread socket. File contents arrive inline or through `executor_git_blob_chunk`, or over HTTP to the actor when `observers.gitBlobHTTPAvailable` is true. (`docs/protocol/private-protocol.md:218-224`) | `cli`. VERIFIED on a runner for `status`, `head`, `diff_range_snapshot`, `file_diff`, and `client_git_diff_snapshot` (`spikes/protocol/RESULTS.md:17`). The blob paths are NOT VERIFIED. | Branch, head, base ref, ahead commits, and per-file changes with old and new contents or blob SHAs. |
| Recorded captures | The thread runs in an orb and its executor is not connected, because the orb is paused or Orb Saver Mode kept it asleep. | `GET /api/threads/<id>/diff-captures/latest` with `If-None-Match`, then `GET /api/threads/<id>/diff-captures/blob/<sha>/raw` per file. `GET /api/threads/<id>/diff-captures/diff?from=<sha>&to=<sha>` for files whose blobs are too large. (`docs/protocol/private-protocol.md:225-228`) | `latest` and `blob` are `cli`. `diff` is `web-plain`. All three are NOT VERIFIED. The spike could not start an orb. On its runner thread, `latest` returned 204 and `client_request_capture` returned `INVALID_ARGS` with "Persisted diff captures are only available for orb threads" (`spikes/protocol/RESULTS.md:17`). | Ranges (All Changes, Uncommitted, one per commit), each with sections and files that carry blob SHAs and diff stats. No contents. |
| Public v2 repository diff | A pushed ref with no thread executor, such as a pull request head on the project page, and only when the app holds an External API token with `amp.git:read`. | `GET /api/v2/workspace/projects/{projectID}/repository/diff?ref=&base=` (Amp's docs) | Public, but a user app cannot get a token today. Sign in with Amp apps are "not generally available", and machine-to-machine clients read only workspace-owned repositories (Amp's docs). The first release does not call it. | Per-file unified patches, `filteredFiles` without patches, and `incomplete`. |
| Local cache | Always, for the first paint. Never as the final answer. | `:store` tables keyed by thread, scope, and `diffHash`. Blobs keyed by SHA. | Local. | The last snapshot the app saw, with the time it was captured. |

### How the app picks a source

The engine runs these steps each time the Changes pane becomes visible, the scope changes, or `executor_workspace_maybe_changed` arrives (`docs/protocol/private-protocol.md:157`, `:224`):

1. Paint the cached snapshot for the thread and scope, labeled "As of" plus its capture time.
2. If the executor is connected, request live git and replace the cache with the result.
3. If the executor is not connected and the thread runs in an orb, fetch the latest capture with the stored ETag. A 304 keeps the current data. A 204 means no capture exists (`docs/protocol/private-protocol.md:226`).
4. If neither source answers, keep the cached snapshot and show why: "Runner offline", "Orb paused", or "No recorded changes yet".

A live git request goes out only while `observers.hasExecutor` is true (`docs/protocol/private-protocol.md:156`). The git error code `EXECUTOR_NOT_CONNECTED` (ampcode.com's web bundle, search `EXECUTOR_NOT_CONNECTED`) switches the pane to step 3. It never starts a retry loop.

Every git result arrives twice, once as the JSON-RPC response and once as a `client_git_command_result` notification with the same `requestId`. `:sync` reads the notification and drops the response body (`spikes/protocol/RESULTS.md:17`). The response only acknowledges the request and carries no data. A request settles on the notification, on an error response, or after a 120 s timeout, never on the bare response, because the notification can arrive well after it (`spikes/protocol/captures/s07-changes.jsonl:63-65` shows 75 ms to the response and 233 ms to the result).

### Never wake an orb from the background

Opening an unarchived orb thread, opening a portal, or sending a message wakes the orb and bills the user. Orb Saver Mode stops a mere open from waking it (Amp's docs). The **Files**, **Terminal**, and **Desktop** panes count as interaction (Amp's docs). The Changes pane is not on that list.

The app holds these rules:

- `:notify` reaches the engine only through `NotifyEntryPoint`, which yields `BackgroundAccess` and nothing else. `BackgroundAccess` has no Changes calls (`docs/design/architecture.md`, "Hilt composes the modules"). Notifications, widgets, and the cover screen never fetch diffs.
- The Changes pane loads only while a window in the `STARTED` state shows the thread and holds its lease (`docs/design/architecture.md`, "One socket per visible thread"). A sleeping orb's thread shows "Waking up the orb" in the machine pane while the shown thread wakes it, and the pane loads live git once the executor connects (`screens.md`, "Wake an orb only for a shown thread").
- The Changes pane never sends a request that starts an executor. The wake belongs to the thread engine, which sends it only for a thread a `STARTED` window shows.
- Capture requests read Amp's servers, not the orb [INFERENCE: the capture endpoints take no executor parameter and answered on a runner thread with no orb].
- Intelligent file order on a capture goes through the thread actor connection, not the executor (ampcode.com's web bundle, search `function Kq`). [INFERENCE] It does not wake the orb.

## Live git reference

### Operations

`client_git_command` takes one `operation` object. The result is `client_git_command_result{ok, exitCode, stdout, stderr, error?}` (`docs/protocol/private-protocol.md:219`).

| `operation.type` | Parameters | The app uses it for |
|---|---|---|
| `repository_root` | none | Resolving the root of the thread's repository |
| `head` | none | The current commit. Measured output is the SHA and a newline (`spikes/protocol/captures/s07-changes.jsonl:62`). |
| `branch` | none | The branch name in the scope picker summary |
| `status` | none | Porcelain status with NUL separators. Measured output is ` M calc.py\0?? __pycache__/calc.cpython-312.pyc\0` (`spikes/protocol/captures/s07-changes.jsonl:59`). |
| `status_snapshot` | none | Branch, base ref, ahead count, ahead commits, and files in one call [INFERENCE: same shape as the status snapshot schema in ampcode.com's web bundle, search `aheadCommits`] |
| `comparison_base_ref`, `comparison_base_head` | none | The base branch name and its commit, for Ship and All Changes |
| `ahead_count`, `behind_count`, `ahead_commits` | none | The commit list and the "N commits ahead" summary |
| `remote_branches` | `query?` | The **Compare With Branch…** list, at most 500 branches (ampcode.com's web bundle, search `max(500)`) |
| `branch_comparison_base` | `branch` | The merge base with another branch. Returns `{branch, branchHead, baseHash}` (ampcode.com's web bundle, search `branchHead`). |
| `live_sync_git_state` | `headHash, baseCandidateHashes, preferThreadBase?` | Not used. The app does not sync a local checkout. |
| `diff_range_snapshot` | `baseHash, headHash?` | All changes of a range in one JSON payload with per-file `diff`, `fullFileDiff`, `diffToken`, and contents (`spikes/protocol/RESULTS.md:17`) |
| `file_diff` | `path, changeType, full` | One file's unified diff. `full:true` asks for the whole file as context. |
| `stage_file`, `unstage_file` | `path` | Staging in the Uncommitted scope |
| `file_diff_in_repository`, `stage_file_in_repository`, `unstage_file_in_repository` | not decoded | The same operations for an additional repository [INFERENCE: they add a repository root to the parameters above] |

`client_git_diff_snapshot` takes `baseRevision` and `headRevision`, each a 7 to 64 character hex SHA, `index`, or `worktree`, plus `includeUntracked` and `content`. `content` is `compact`, `renderable`, or `metadata` (ampcode.com's web bundle, search `` `compact`,`renderable`,`metadata` ``). The app asks for `renderable`, which carried `oldContent` and `newContent` for the text file in the spike (`spikes/protocol/captures/s07-changes.jsonl:71`). For a scope with more than 100 files it asks for `metadata` and loads contents per file on demand.

### Errors

| Code | Meaning | What the app shows |
|---|---|---|
| `EXECUTOR_NOT_CONNECTED` | No executor on the thread | Switches to the capture or the cache. No error text. |
| `INVALID_ARGS` | A bad path, revision, or operation | "Could not load changes." plus the server message, and a **Refresh** button |
| `GIT_OPERATION_FAILED` | Git exited non-zero | The first line of `stderr`. Orb clones are shallow and single-branch, so a branch comparison can fail until the agent unshallows the clone (Amp's docs). |
| Not a repository | Any failure whose message contains git's `not a git repository`, whether the executor reports it as a non-zero exit (128, git's own code) with that `stderr` or as `ok:false` with that `error.message`. `:sync` gives it the code `NOT_A_REPOSITORY`. | `ChangesView.NoRepository`: the neutral empty state **No git repository**, "This thread's workspace isn't a git repository, so there are no changes to show. Files and Terminal still work.", and a **Refresh** link for after `git init`. It replaces any cached snapshot. The web hides the Changes tab when its status snapshot reports `unavailableCode:"not_git_repository"` (`_app/immutable/chunks/c940662c.js`, search `not_git_repository`), so both say there is nothing to review rather than that something broke. |
| `TOO_LARGE` | The result exceeded a transfer limit | Stat-only rows for the affected files. See the limits table. |
| `INTERNAL_ERROR` | Anything else | "Could not load changes." and **Refresh** |

The codes come from ampcode.com's web bundle (search `GIT_OPERATION_FAILED`).

### Measured round trips

On the spike runner with a two-file repository, each git request took 156 ms to 261 ms from send to notification: `status` 156 ms, `head` 188 ms, `diff_range_snapshot` 233 ms, `file_diff` 261 ms, and `client_git_diff_snapshot` 234 ms (`spikes/protocol/captures/s07-changes.jsonl:57-71`, timestamps). These are measured values for one small repository. They do not predict a large diff.

## Projects and repositories

### Project facts the Changes pane reads

The pane needs five facts from the thread's project. `:sync` reads them with `getThreadProject{thread}` and `getProject{projectID}`, both called by the CLI (`docs/protocol/private-protocol.md:405`, `:451`), and re-reads them every time a thread opens, because a Ship Behavior change applies to open threads only after a reload on the web (Amp's docs).

| Fact | Field | Effect |
|---|---|---|
| Ship Behavior | `changesWorkflow`. The web treats `push-to-branch` and `custom-ship` as those behaviors and any other value as Ship (ampcode.com's web bundle, search `merge-to-main`). | The primary button label and prompt |
| Custom Ship prompt | `customShipPrompt`, up to 10,000 characters, no template variables (Amp's docs) | The prompt text when Ship Behavior is Custom Ship |
| Base branch | `baseBranch`. Empty means the repository default (Amp's docs). | Never the Ship target. See [Target ref and the target line](#target-ref-and-the-target-line). |
| Repository mode | `repositoryMode`, which is `github`, `mapped`, or Amp-hosted (Amp's docs) | GitHub-only features show only for `github` |
| Additional repositories | `additionalRepositories`, up to 20 (Amp's docs) | Multi-repository grouping |

Editing Ship Behavior and the custom prompt uses `updateProject{projectID, changesWorkflow?, customShipPrompt?}`, which the CLI calls (`docs/protocol/private-protocol.md:435`). Only the project creator, a workspace admin, or the owner of a private project can change it, so the app disables the control for everyone else (Amp's docs).

### GitHub-only features

Pull request URLs, commit and pull request previews, the CI row, and webhooks exist only for GitHub repositories. Bitbucket, GitLab, and other HTTPS hosts get Ship and Push to Branch without a pull request URL (Amp's docs). GitHub Enterprise Server is not supported (Amp's docs). When a workspace admin turns GitHub off, previews disappear (Amp's docs).

| Feature | GitHub project | Other host or no project |
|---|---|---|
| Pull request chip | Shown | Hidden |
| CI row and **Fix CI** | Shown | Hidden |
| Push to Branch prompt | Adds the pull request sentence | Omits it (ampcode.com's web bundle, search `isGitHubRepository`) |
| Push confirm text | "Push to new branch & open GitHub PR" or "Push to GitHub PR branch" plus the branch | "Push to" plus the branch, or "Push to branch" (ampcode.com's web bundle, search `function MA`) |

The app shows no error when previews are missing. Missing GitHub data is a normal state for these projects.

### GitHub problem states

ampcode.com names six GitHub problems and the fix for each (Amp's docs). The app shows the same message and sends the user to the same fix. No `cli`-tier call reports these states, and their error codes are unknown (Amp's docs). The app matches the message text where it reaches an observer: an `EngineError` message from a server call, a thread error row, or the output of a shell tool card [INFERENCE: these are the places a GitHub failure can surface outside the web's own pages].

| Message or symptom | What the app adds | Action |
|---|---|---|
| "No GitHub account connected" | Nothing | **Connect GitHub** |
| "GitHub connection appears stale or expired" | Nothing | **Reconnect** |
| "Amp could not access this GitHub repository" | "Check the repository URL, then add the repository to Amp's GitHub app." | **Configure** |
| "Your GitHub account is connected, but repository access is still required" | "Pick the repositories Amp may access." | **Repositories** |
| "GitHub connection is disabled by your workspace admin" | "Ask a workspace admin to turn GitHub on." | None |
| `git push` output with `403` and `Permission to <owner>/<repo>.git denied` in a shell tool card | A hint row under the card: "Amp's GitHub app can't push to `<owner>/<repo>`." | **Configure** |

Every action opens the Integrations page, `/settings/integrations` (Amp's docs), in the account's web view (`screens.md`, "How to read this document"). Commits that GitHub marks **Unverified** are not detected, because no change source carries signature state (see [Commit identity and signing](#commit-identity-and-signing)).

Orbs fetch a short-lived GitHub token for each Git command, and branch protection applies as it does on the user's machine (Amp's docs). The app never handles those tokens. A push rejected for another reason shows only the agent's output. The web lists push denials through `getThreadRepositoryPushDenials`, which is `web-remote` (Amp's docs), so the app does not call it.

### Multiple repositories in one thread

A project can clone up to 20 additional repositories next to the primary one, under `../repos/` (Amp's docs). Live git reports them as `additionalRepositories` on the status snapshot (ampcode.com's web bundle, search `additionalRepositories:f(qn).max(20)`). Captures carry `repositories[{key, name}]` and tag each commit range with a `repositoryKey` (ampcode.com's web bundle, search `version:a(3)`).

The app groups by repository everywhere:

- The file list has one heading per repository, primary first, each with its branch and "N commits ahead".
- Scope picker entries for commits read `<repositoryName>: <subject>` when the thread has more than one repository, as on the web (ampcode.com's web bundle, search `function XV`).
- Staging sections read `<repositoryName> · Unstaged` and `<repositoryName> · Staged` (ampcode.com's web bundle, search ``zH(`Unstaged` ``).
- A comment on a file in an additional repository names that repository in the message.

Only the primary repository runs `.agents/setup` (Amp's docs). That fact does not change the pane.

### Runner worktrees

A runner that reports the `create-worktree` capability can create a Git worktree for a new thread (`spikes/protocol/RESULTS.md:13`). The worktree is a sibling directory named `<repository>-<name>` on a new branch of the same name. Names allow letters, digits, hyphens, and underscores, start with a letter or digit, and fail if the branch or directory exists (Amp's docs). The CLI passes the worktree as `runnerWorktree` to `createRemoteExecutorThread` (`docs/protocol/private-protocol.md:482`). The app keeps one creation flow for every thread, so a failed spawn can retry on the same thread. It creates the thread with `POST /api/thread-actors`, then sends `runnerWorktree` in `client_spawn_executor` [INFERENCE: the spawn takes the same field name as the CLI's call]. That field is read, not observed. The sheet layout, and what the app does when the spawn fails, are in `screens.md`, "Executor picker".

The web offers **Archive and Remove Worktree** through a `web-remote` command (Amp's docs). The app has no stable path for it. Its **Archive** action on a worktree thread says "The worktree stays on disk." and offers **Ask Amp to Remove It**, which sends the agent a message to archive the thread and remove the worktree, a request the docs describe (Amp's docs). Removal never forces anything. A worktree with uncommitted changes stays, and so does a branch with unmerged commits (Amp's docs).

### Commit identity and signing

Commit author, co-author trailers, and signing keys are web settings (Amp's docs). The app opens them in the account's web view. No change source carries a commit's signature state: ahead commits have `hash`, `shortHash`, `subject`, `message?`, `parentHash?`, and `metadata.isMergeBase` only (ampcode.com's web bundle, search `isMergeBase`). The app therefore shows no **Verified** badge.

### Project page

The project list comes from `listAccessibleProjects`, which the CLI calls (`docs/protocol/private-protocol.md:564`). The native project page shows the project's threads and its Ship Behavior. Recent commits and the file browser come from `web-remote` functions on ampcode.com (Amp's docs), and the public repository endpoints need a token a user app cannot get yet. The page therefore opens those two sections on ampcode.com in a Custom Tab.

## The diff model

### Types

The app keeps one model for every source. Live snapshots, captures, and v2 patches all map into it.

| Type | Fields | Notes |
|---|---|---|
| `ChangesView` | `Loading`, `Unavailable(reason)`, `NoRepository`, `Failed(message)`, or `Ready(source, repos, order, actions, stale)` | `stale` turns true on `executor_workspace_maybe_changed` until the refresh lands (`docs/design/sketch/core-model/Machine.kt`, `ChangesView`). `NoRepository` is a workspace with no git repository, not an outage. |
| `ChangeSource` | `LiveGit(head, base)`, `Capture(capturedAt, etag)`, `PushedRef(commit)`, or `Cached(capturedAt)` | Shown as text in the pane header: "Live", "Recorded" plus the time, or "As of" plus the time |
| `RepoChanges` | `repo`, `branch`, `ahead`, `behind`, `sections` | One per repository |
| `ChangeSection` | `id`, `title`, `kind`, `files`, `summary?`, `codeTour?` | Uncommitted has two sections, Unstaged and Staged. `summary` and `codeTour` come from intelligent order. |
| `FileChange` | `id`, `path`, `previousPath?`, `change`, `stat`, `binary`, `muted`, `stage`, `oldBlob?`, `newBlob?`, `oldSize?`, `newSize?`, `omittedReason?` | Field names follow the snapshot file schema (ampcode.com's web bundle, search `contentOmittedReason`) |
| `ChangeKind` | `Added`, `Modified`, `Deleted`, `Renamed(from)`, `Copied(from)`, `TypeChanged`, `Unmerged`, `Untracked` | The eight values of the protocol `changeType` enum (ampcode.com's web bundle, search `type_changed`). The sketch's four-value `ChangeKind` grows to eight. |
| `DiffStat` | `added`, `deleted`, `changed` | The spike saw all three (`spikes/protocol/captures/s07-changes.jsonl:71`). |
| `StageState` | `Unstaged`, `Staged`, `Partial`, `NotApplicable` | From `hasStagedChanges` and `hasUnstagedChanges` |
| `FileDiffView` | `Loading`, `Ready(hunks, lineCount)`, `Binary(oldSize, newSize)`, `TooLarge(reason)`, `Omitted(reason)`, or `Failed(message)` | One per file. `Ready` holds ranges into a shared line store, not strings per hunk (`docs/design/sketch/core-model/Machine.kt`, `FileDiffView`). |
| `Hunk` | `oldStart`, `oldCount`, `newStart`, `newCount`, `header`, `firstLine`, `lineCount` | `header` is the text after the second `@@`, such as `fn scale_table` |
| `DiffLine` | `kind`, `oldNo?`, `newNo?`, `textRange`, `intraline` | `kind` is `Context`, `Added`, `Removed`, or `NoNewline`. `intraline` is a list of changed character ranges. |
| `DiffSelection` | `path`, `side`, `startLine`, `endLine` | `side` is `Old` or `New`, sent as `deletions` or `additions` |

Each change kind shows a letter and a word, because status must be text and not color alone (`docs/design/verification.md:13`):

| `changeType` | Letter | Row text |
|---|---|---|
| `added` | A | added |
| `modified` | M | modified |
| `deleted` | D | deleted |
| `renamed` | R | renamed from `<previousPath>` |
| `copied` | C | copied from `<previousPath>` |
| `type_changed` | T | type changed |
| `unmerged` | U | conflict |
| `untracked` | ? | untracked |

### Renames, binary files, and omitted files

- **Renames.** The file header reads `<previousPath>` then `<path>`. A rename with no content change shows "Renamed without changes" and no hunks.
- **Binary.** A file is binary when the snapshot says `isBinary`, or when its bytes fail strict UTF-8 decoding or contain a NUL byte. The web uses the same test for blobs (ampcode.com's web bundle, search `fatal:!0`). The spike's `__pycache__/calc.cpython-312.pyc` arrived as `isBinary:true` with `newSizeBytes:251` (`spikes/protocol/captures/s07-changes.jsonl:71`). The row shows old and new sizes. PNG, JPEG, GIF, and WebP files show old and new images side by side.
- **Too large or omitted.** A blob request that returns 413 becomes `TooLarge` with the web's text. For captures the text is "File too large to display" (ampcode.com's web bundle), and for live blobs it is "File exceeds the buffered transfer limit" (ampcode.com's web bundle). A snapshot file with `contentOmittedReason` becomes `Omitted` with that reason. A file with neither contents nor a reason shows "The file content is not available." (ampcode.com's web bundle).

## Computing a diff from file contents

Captures carry blob SHAs and no patch. Live snapshots in `metadata` mode carry neither. The app computes those diffs on the phone, the same way the web does (ampcode.com's web bundle, search `createSmartDiffPatchOffThread`):

1. Fetch the old and new blobs, at most four at a time. The web uses four for live blobs (ampcode.com's web bundle, search `this.active>=4`) and five for capture blobs (ampcode.com's web bundle, search `fileConcurrency=5`). Added files have no old blob and deleted files have no new blob.
2. Decode each blob as strict UTF-8. A failure or a NUL byte makes the file binary.
3. If either blob is too large and both SHAs exist, ask the server for the patch with `diff-captures/diff?from=&to=`, as the web does (ampcode.com's web bundle, search `getDelta`). That endpoint is `web-plain` and NOT VERIFIED. If it fails, the file stays `TooLarge`.
4. Normalize CRLF to LF and record whether each side ends with a newline. A missing final newline adds a `NoNewline` line with the text `\ No newline at end of file` (ampcode.com's web bundle, search `No newline at end of file`).
5. Compute the line diff with Myers' algorithm on a background dispatcher.
6. Build hunks with 3 lines of context, and merge two changes into one hunk when 6 or fewer unchanged lines separate them. These are the web's values (ampcode.com's web bundle, search `var _=3`, and ampcode.com's web bundle, search `e.fromA-t.toA>6`).
7. Compute intra-line ranges for the visible change blocks. See "Word-level highlights".

The spike showed that file contents are enough to reproduce Git's own hunks. It rebuilt the `calc.py` diff from `oldContent` and `newContent` with `git diff --no-index`, and the hunks matched `file_diff` exactly (`spikes/protocol/RESULTS.md:17`). The app's diff engine has a golden test of the same form. It diffs fixtures of old and new contents and compares the hunks with `git diff` output.

When a source already gives a patch (`file_diff`, `diff` on a snapshot file, or a v2 `patch`), the app parses the unified diff into the same model instead of recomputing it.

## The Changes pane

### Placement

The pane follows the arrangements in `layout.md`, "Arrangements by window class".

| Window | Changes pane | Diff layout |
|---|---|---|
| Cover display, Compact | Full screen, 411dp | File list, then one file's unified diff |
| Inner display portrait, Triage | Machine pane beside the Thread pane, 360dp | File list, then one file's unified diff |
| Inner display portrait, Review | Full width beside the rail, 720dp | One continuous multi-file diff, split by default |
| Inner display landscape, Triage | One panel, 363dp | File list, then one file's unified diff |
| Inner display landscape, Review | Two panels, 745dp | One continuous multi-file diff, split by default |
| Inner display landscape, Focus | Three panels | File list in panel 1, split diff across panels 2 and 3 |
| DeX, Large or Extra-large | One machine column | Split when the pane is at least 1,000dp wide, otherwise unified |

A Changes pane narrower than 600dp always shows the unified diff and has no **Show Split Diff** toggle, in its toolbar or in **More Actions**: each side of a split diff would get about 180dp, and every code line would wrap.

In one panel the file list and the diff stack. Tap a file to open its diff, and **Back** returns to the list (`docs/design/sketch/machine/ChangesFiles.kt`, `ChangesTab`). In Review, each file is a collapsible header in one lazy list, with a sticky header that holds **Previous File**, **Next File**, and **File List**, which opens the list as a sheet. **Collapse All Files** and **Expand All Files** match the web labels (ampcode.com's web bundle, search `Collapse All Files`).

Opening a diff from a tool card selects the Changes tab and scrolls to the file without changing the arrangement (`docs/design/layout.md`, "Arrangements"). Puck's `puck_platform_navigation` with a `diffs` destination does the same (`docs/protocol/private-protocol.md:159`).

### Scope picker

The scope picker sits at the bottom of the pane, as on the web (Amp's docs). Its options come from the status snapshot (ampcode.com's web bundle, search `function XV`).

| Option | Shown when | Compares | Label |
|---|---|---|---|
| All Changes | At least one commit is ahead of the merge base | Merge base to working tree, untracked files included, per repository | **All Changes** |
| Uncommitted | Always | Two sections. Unstaged is `index` to `worktree` with untracked files. Staged is `HEAD` to `index` (ampcode.com's web bundle, search ``zH(`Unstaged` ``). | **Uncommitted** |
| One commit | One per ahead commit, newest first | The commit's parent, or the previous ahead commit, to the commit | First line of the subject, or the short hash when the subject is empty. A `HEAD` badge marks the current commit. |
| Compare With Branch… | The executor is connected | `branch_comparison_base` result to the working tree | `origin/<branch>` once chosen |

Commit labels use the subject's first line, not its first word (ampcode.com's web bundle, search ``split(`\n`)[0]||o.shortHash``). The picker's closed state reads "`<label>` · N ahead", shorter than the web's "`<label>` · N commits ahead of `<base>`" (ampcode.com's web bundle, search `` ahead${ ``), so it leaves Ship room in the action bar. The full wording is the picker's content description and the first line of its open menu (`changes-scope-ahead-of-base`). The branch list has a **Filter branches** field, shows "Loading branches…", "Searching all branches…", or "No matching branches", and marks the merge base with a **Merge Base** badge (ampcode.com's web bundle).

Captures supply their own ranges. A `head` range becomes Uncommitted, an `all` range becomes All Changes, and a `commit` range becomes one commit (ampcode.com's web bundle, search `function ZV`). Compare With Branch needs the executor, so it is absent on a capture.

Empty states use the web's words (ampcode.com's web bundle, search `No Changes Yet`):

| State | Text |
|---|---|
| Uncommitted is empty, but commits exist | "No uncommitted changes" |
| The repository has no changes at all | "No Changes" |
| The pane has not loaded any data yet | "No Changes Yet" |

### File order

By default the Changes pane orders files intelligently. Amp reads the diff, puts the files that best explain the change first, and mutes less important files such as generated code. Alphabetical order is the other mode (Amp's docs).

**Request.** `:sync` sends `client_changes_file_order` with up to 42 sections, each with `sectionID`, `diffHash`, `baseRevision`, `headRevision`, `includeUntracked`, and up to 500 files of `{path, changeType, diff}` (ampcode.com's web bundle, search `.max(42)`). `includeUntracked` is required, with the same value as the section's snapshot request. Without it Amp answers `-32602 Invalid method params` (`spikes/protocol/captures/s20-changes-params.jsonl:1016-1019`, RESULTS.md S20b). Amp's schema caps each file's `diff` at 20,000 characters. The web also truncates each file's diff so the request stays under 200,000 characters, and it fails with "Changes file order request is too large" past that (ampcode.com's web bundle). The app cuts each diff to 20,000 characters, or to an equal share of the 200,000 character budget when that is smaller. The request needs every file's contents, so the app sends it only while the pane is visible.

**Result.** `client_changes_file_order_result{requestId, result}` carries `result:{status:"success", sections}` or `result:{status:"failure", error}`, with no top-level `ok` (`s20-changes-params.jsonl:1020`). Each section returns `orderedPaths` (up to 500), and optionally `summary` and `codeTour` of up to 20,000 characters each (ampcode.com's web bundle, search `codeTour`). The web rejects a result when fewer than 10 percent of the returned paths match the displayed files (ampcode.com's web bundle, search `Wq=.1`). The app shows `summary` at the top of its section and `codeTour` behind a **Code Tour** expander.

**Muting.** A file that the result does not list goes to the end of its section and is muted [INFERENCE: the web marks only listed paths as important headers, ampcode.com's web bundle, search `importantFileHeaderURIs:o`]. A muted file is collapsed, its row is dimmed, and its row text says "muted". Tapping it expands it.

**Updates.** If the diff changes after an order is applied, Amp keeps that order while the agent works and computes a new one when the turn finishes. If the new order would move files, a blue dot appears on the order button, and **Apply Updated File Order** applies it (Amp's docs). The app also states the dot as text. The button's label changes to **Apply Updated File Order**, so `agent-device is text` can check it.

**Button labels** follow the web (ampcode.com's web bundle, search `Order Files Intelligently`):

| State | Label | Explanation shown under the button |
|---|---|---|
| Intelligent order applied | **Order Files Alphabetically** | none |
| Alphabetical order | **Order Files Intelligently** | none |
| A new order is ready | **Apply Updated File Order** | none |
| More than 500 files | **Intelligent File Order Unavailable** | "This diff is too large to order intelligently." |
| Viewer lacks access | **Intelligent File Order Unavailable** | The blocked reason, such as "Intelligent file order requires contributor access. Ask the thread owner to enable multiplayer." (ampcode.com's web bundle, search `function qq`) |
| The request fails, is refused, or returns under 10 percent of the files | **Intelligent File Order Unavailable** | "Intelligent order isn't available for this thread." The server's message goes to the debug log (`AmpWs`), never to the screen. |

When intelligent order is unavailable, files are listed alphabetically (Amp's docs). Alphabetical order sorts by path within each section (ampcode.com's web bundle, search `localeCompare`). In alphabetical mode no file is muted. Binary files and files with more than 2,000 changed lines start collapsed, the same line count above which the v2 API omits a patch (Amp's docs).

### File list and file tree

The file list is a flat list by default. Intelligent order is a sequence, and a tree would scatter it across folders. In alphabetical mode the list header offers **Show as Tree** and **Show as List**. The tree groups by directory under each repository heading. The web's Changes pane has no tree and instead offers **Reveal in File Tree**, which jumps to the Files pane (ampcode.com's web bundle, search `Reveal in File Tree`). The app keeps that action too.

Each row shows the change letter and word, the path with the directory dimmed, and `+added −deleted`. Its overflow menu has **Copy Path**, **Open File**, and **Reveal in File Tree**, as on the web (ampcode.com's web bundle, search ``label:`Copy Path`,onSelect``). All three are real buttons, because agent-device does not see custom accessibility actions (`docs/design/verification.md:11`).

### Staging

Staging works on whole files in the Uncommitted scope. The protocol has `stage_file{path}` and `unstage_file{path}` and no hunk-level operation (`docs/protocol/private-protocol.md:219`), so the app offers no hunk staging. Each file row in the Unstaged section has **Stage changes**, and each row in Staged has **Unstage changes**. While the request runs, the button reads "Staging changes..." or "Unstaging changes..." (ampcode.com's web bundle, search `Stage changes`). After the result, the app refreshes both sections and keeps the moved file in view.

Staging needs a connected executor, so it is hidden on a capture or the cache. It is also hidden for viewers who cannot send messages [INFERENCE: the web gates staging behind the same write controls as the workflow buttons].

### Unified and split diff

The prototype measured how many columns of 12sp monospace fit in a pane. Its `colsFor` function subtracts 24dp of padding and divides by the measured character width. A 360dp pane fits 48 columns and a 714dp pane fits 98. One column is about 7dp [INFERENCE: (360 - 24) / 48 = 7.0 and (714 - 24) / 98 = 7.04].

The app's gutters are fixed in columns. A unified line uses 10 columns of gutter: two 4-digit line numbers, the `+` or `-` marker, and a space. A split side uses 6: one 4-digit line number, the marker, and a space. Files longer than 9,999 lines widen each number by one column per extra digit.

| Pane | Width | Columns (measured) | Layout | Code columns |
|---|---|---|---|---|
| Triage, one panel | 363dp | 48 [INFERENCE: (363 - 24) / 7; 48 measured at 360dp, `logcat-LayoutProto.txt:4`] | Unified | 38 |
| Cover display | 411dp | 53 (`logcat-LayoutProto.txt:102`) | Unified | 43 |
| Inner portrait, Triage | 360dp | 48, measured on the emulator's equal 360dp landscape panel (`logcat-LayoutProto.txt:4`) | Unified | 38 |
| Inner portrait, Review | 720dp | about 98 [INFERENCE: 714dp measured 98, `logcat-LayoutProto.txt:11`] | Split, with the boundary at the pane's center | about 41 per side |
| Review, two panels, split | 745dp, as 382dp and 363dp sides | about 51 and 48 [INFERENCE: (382 - 24) / 7 and (363 - 24) / 7] | Split | 45 old, 42 new |
| Review, two panels, unified | 745dp | about 103 [INFERENCE: (745 - 24) / 7; 98 measured at 714dp, `logcat-LayoutProto.txt:11`] | Unified | 93 |

The split sides are 382dp and 363dp because the Review pane spans panels 2 and 3, from the 335dp crease to 1080dp, and the second crease sits at 717dp (`docs/design/layout.md`, "Windows are measured in panels"). The prototype's emulator had equal 360dp panels and measured 354dp and 360dp sides (`logcat-LayoutProto.txt:11`). The app puts the column boundary of a split diff on the crease, old side left and new side right, so no line of code crosses the fold. The two-panel screenshot shows the problem this solves. With a unified diff, the crease runs through the middle of the code.

Split is the default when the pane spans two or more panels, and **Show Unified Diff** switches back. Below 600dp there is no split and no toggle. The choice is a trade. Split gives the sides 45 and 42 code columns and keeps both versions of a changed line in view. Unified gives one line 93 columns. Long-lined languages read better unified, and the app remembers the choice per window. The labels **Show Split Diff** and **Show Unified Diff** match the web (ampcode.com's web bundle, search `Show Split Diff`).

A split row pairs a removed line with an added line from the same change block. Unpaired lines leave the other side blank. Context lines show on both sides.

Hunks show 3 lines of context. Between hunks, a row offers **Show 25 More Lines** and **Show All**. Each further press doubles the count, as on the web, which starts at 25 (ampcode.com's web bundle, search `var Ji=25`). Expanding needs the full file, which comes from the blobs, from `fullFileDiff`, or from `file_diff{full:true}`.

### Syntax highlighting

The web highlights diffs with Shiki, which runs TextMate grammars (ampcode.com's web bundle, search ``preferredHighlighter:n=`shiki-js` ``). The app uses TextMate grammars too, so a token gets the same scope name on both clients. The tokenizer is the `tm4e` core engine (`org.eclipse.tm4e.core`), which the Android editor library sora-editor imports in its `language-textmate` module (https://github.com/Rosemoe/sora-editor, `TextMateLanguage.java`). [INFERENCE] The tm4e core runs without an editor attached, and its speed on the TriFold is unmeasured. The engine lives in `:core:design` so the Files viewer and transcript code blocks share it.

- The language comes from the file name and extension, with a table for names such as `Dockerfile` and `Makefile`.
- When both contents exist, the app tokenizes each whole side once, because TextMate state carries across lines and a hunk alone can start inside a comment or a string. Hunk lines then take their tokens from the side they belong to.
- When only a patch exists, the app tokenizes each side's hunk lines in order from an empty state. Some lines can get the wrong color in that case [INFERENCE].
- Tokenizing runs on a background dispatcher. The visible window goes first, and the rest follows. Lines render as plain text until their tokens arrive.
- Lines longer than 1,000 characters are not tokenized, matching the web's `tokenizeMaxLineLength` (ampcode.com's web bundle). A side larger than 1 MiB or 20,000 lines renders plain.
- Results are cached per blob SHA, so switching scopes does not re-tokenize an unchanged file.

### Word-level highlights

The web marks changed words inside changed lines with Pierre's `word-alt` mode and skips lines longer than 1,000 characters (ampcode.com's web bundle, search ``lineDiffType:d=`word-alt` ``, and ampcode.com's web bundle). The app does the same work in five steps:

1. Pair removed and added lines inside one change block. Equal counts pair by position. Otherwise, pair greedily by similarity, and leave a line unpaired when less than 30 percent of its tokens match.
2. Split each line of a pair into tokens: runs of letters, digits, and underscores, runs of whitespace, and single punctuation characters.
3. Diff the token lists with Myers' algorithm.
4. Join two changed tokens when a single whitespace token separates them, so a changed phrase reads as one span [INFERENCE: what `word-alt` adds over `word`].
5. Store the result as character ranges in `DiffLine.intraline` and draw them with a stronger background than the line.

The app computes intra-line ranges only for change blocks near the visible window and skips lines over 1,000 characters.

### Long lines

Line wrapping is on by default, as on the web (ampcode.com's web bundle, search ``Kn=A(()=>I(gn)??!0)``). A wrapped line keeps one line number and a continuation mark on its following rows. **Disable Line Wrapping** and **Enable Line Wrapping** toggle it, with the web's labels. With wrapping off, each file scrolls sideways as one block, so its lines stay aligned. The prototype used one horizontal scroll per diff block in the same way.

A line longer than 20,000 characters shows its first 20,000 and a **Show Full Line** button that opens the line in a text viewer. Minified files usually hit this limit.

## Asking for changes from the diff

A review comment on the diff becomes part of a message to the agent. This is the "ask for changes on a hunk" step of the review loop (`docs/design/product.md`, "The core loops").

### Select and comment

- Tap a line's gutter to select the line. Shift-click or drag on the gutter extends the selection within one side. A selected line reports `selected`.
- With a selection, a bar shows **Comment**, **Explain**, and **Copy**.
- Each hunk header has **Comment on Hunk**, **Explain**, and **Copy Hunk** as real buttons (`docs/design/sketch/machine/ChangesFiles.kt`, `ChangesTab`). **Comment on Hunk** selects the hunk's new-side lines, or its old-side lines when the hunk only deletes.
- **Comment** opens a text field. **Add Comment** stores the comment as a pending pill above the composer of the same thread. A pill shows the first 36 characters of the comment (ampcode.com's web bundle, search `Tr(e.text,36)`).

A pending comment records `{id, uri, lineNumber, lineRange?, side, text, commitContext?}`, where `side` is `additions` or `deletions` and `commitContext` is `{hash, subject, repositoryName}` (ampcode.com's web bundle, search `commitContext`). The app keeps drafts per thread in `:store`, so they survive a restart.

### Limits

A thread holds at most 20 pending comments with at most 10,240 bytes of text in total (ampcode.com's web bundle, search `too-many-comments`). Past the first limit, the app shows "You can stage up to 20 comments." Past the second, it shows "Pending comments can contain up to 10 KiB of text." Both are the web's texts (ampcode.com's web bundle, search `function vK`). [INFERENCE] The second message's size constant `sd` equals 10,240.

### How comments reach the agent

Pending comments go out with the next message the user sends: a composer message, Ship, Push, Review, or Rebase (ampcode.com's web bundle, search `includePendingDiffComments??!0`). Fix CI includes them too [INFERENCE: the web sends Fix CI through a quick-action callback whose comment handling the bundle does not show]. Restack does not include them (ampcode.com's web bundle, search `includePendingDiffComments:!1`). **Send Comments** on the pill strip sends them alone. After a send, the pills clear.

The text block goes before the message, separated by a blank line (ampcode.com's web bundle, search `function CK`). Its format is the web's (ampcode.com's web bundle, search `Diff review comments:`):

```
Diff review comments:
1. src/quant.rs:42 - Use 0..64 here and drop the + 1.
2. src/dct/idct.rs:10-14 [commit 4bd66138fb8e in dct: Fix zigzag walk] - Why clamp before rounding?

Fix these, then rerun the roundtrip suite.
```

The line part is the line number, or `start-end` for a range. The bracket part appears only when the comment was made on a commit scope, and it names the repository only in multi-repository threads.

A composer message carrying comments follows the composer's send mode. It steers while the agent works unless the user picks **Queue**. **Send Comments** behaves the same way.

### Explain a selection

**Explain** sends `client_explain_diff_selection{path, side, startLine, endLine, fileStatus, selectedCode, contextScope, fileHead, contextBefore, contextAfter}` and shows the returned `{explanation}` under the selection (`docs/protocol/private-protocol.md:223`). The selected code may be up to 20,000 characters, each context field up to 640,000, and the explanation up to 20,000 (ampcode.com's web bundle, search `selectedCode`). The explanation does not enter the transcript. This message is NOT VERIFIED. If it fails, the bar keeps **Comment** and hides **Explain** for the session.

## Ship and the other Changes actions

Every Changes action is a prompt. The app builds the text, shows it in a confirm sheet, and sends it as a user message. There is no `cli`-tier ship call (`docs/protocol/private-protocol.md:230`).

### Actions

| Action | Shown when | Confirm title | Confirm text | Send button | Built-in prompt name |
|---|---|---|---|---|---|
| **Ship** | Always (Amp's docs) | **Ship Changes?**, or **Ship?** when a custom or saved prompt applies | The target line below | **Ship** | `ship` |
| **Push** | Ship Behavior is Push to Branch | **Push to Branch?** | The target line below | **Push to Branch** | `ship` |
| **Review** | The built-in is not hidden | **Review Changes?** | "Request a code review for all changes", "Request a code review for `<base>..<head>`" on a commit scope, or "Request a code review" | **Review** | `review` |
| **Restack** | The built-in is not hidden | **Restack Changes?** | "Amp will organize all your committed and uncommitted changes into a cleaner set of commits. The final file contents stay the same." | **Restack** | `refactor-commits` |
| **Rebase onto `<target>`** | The built-in is not hidden and the workspace is available | **Rebase?** | "Fetch origin and rebase onto `<target>`" | **Rebase** | `update-base` |
| **Fix CI** | GitHub project with a failed CI result on the latest push | **Fix CI?** | The editable message | **Send** | none |
| **Sync** | Orb threads | **Sync to Your Machine** | `amp sync <thread URL>` with **Copy** | none | none |

The Ship, Push, and **Ship?** titles, labels, and texts come from ampcode.com's web bundle (search `function MA`). Review and Restack titles and texts come from ampcode.com's web bundle (search `Review Changes?`). Fix CI comes from ampcode.com's web bundle (search `Fix CI?`). The Rebase label comes from ampcode.com's web bundle (search `` Rebase onto ${ ``). Built-in names come from Amp's docs.

The action bar at the bottom of the pane holds the scope picker, then Ship, then Review, Restack, Rebase, and Sync in that order. Ship always stays in the bar, and stays there disabled, with its reason line under the bar, when it cannot run. The picker gives way to Ship: its label ellipsizes so Ship fits whole beside it. Each later button stays while it fits whole, and the rest move into the machine header's **More Actions** as `<tag>-menu` items. The bar refits on every layout pass, so buttons come back when the pane widens or the picker's label gets shorter.

The app shows no custom Changes buttons. On the web, a saved prompt with "Show as a button in the Changes tab" becomes an extra button (Amp's docs). The web reads those prompts through `getChangesPrompts`, a `web-remote` function (Amp's docs), and native code never calls `web-remote` functions. `screens.md` states the same rule under Changes.

The web sends Rebase on a single click, with no confirm dialog (ampcode.com's web bundle, search `function $r`). The app adds **Rebase?**, so every action that sends a message has the same confirm step.

Sync builds `amp sync` plus the thread URL, prefixed with `AMP_URL=` for servers other than ampcode.com (ampcode.com's web bundle, search `` `sync`,o].join ``). The web offers it when the executor type is `sandbox` (ampcode.com's web bundle, search `` Cb(I(Wn),`sync-cli`) ``). [INFERENCE] `sandbox` is the orb executor type. The app shows the command with **Copy** and does not run it (`docs/design/product.md`, "Not built").

The web confirms Review and Restack with a 900 ms hold-to-confirm control (ampcode.com's web bundle, search `holdDurationMs(){return 900}`). The app uses a tap and a confirm sheet instead, because agent-device ignores long presses.

### The confirm sheet

The sheet shows the title, the target line, and the full prompt in an editable field, with **Cancel** and the send button. Plugins can append text to the Ship and Push prompts through the `changes.prompt` event, and the user sees and can edit that text before sending (Amp's docs). The web requests the appends from the executor with a plugin RPC of method `changes.prompt` and result `{appends: string[]}` (ampcode.com's web bundle, search `` method:`changes.prompt` ``). The app does the same while the executor is connected and skips it otherwise.

The web lets users turn off the Ship confirmation with a `web-remote` setting (Amp's docs). The app cannot read that setting, so it has its own **Confirm Ship and Push** switch, on by default.

### Target ref and the target line

This section is the one rule for the target of **Ship**, **Push**, and **Rebase**. `screens.md` points here.

The target comes from the live status snapshot's `baseRef`. The app strips `refs/remotes/` and `refs/heads/`, adds `origin/` when missing, and falls back to `origin/HEAD` (ampcode.com's web bundle, search `function jA`).

Until live git has reported a base ref for the thread, the target is unknown. The target line reads "base unknown", and **Ship**, **Push**, and **Rebase** stay disabled with the explanation "Needs live status from the executor." On a paused orb the explanation adds **Wake Orb**. On an offline runner it adds the runner-offline reason. The app never guesses a target. It does not use the project's `baseBranch`, the cached snapshot, or `origin/main`, though the web falls back to `origin/main` (ampcode.com's web bundle, search ``targetRef:t??`origin/main` ``). A prompt that names the wrong branch would ask the agent to push there.

The target line under the title is the web's tooltip text (ampcode.com's web bundle):

| Ship Behavior | Condition | Target line |
|---|---|---|
| Ship | Uncommitted changes and N commits ahead | "Commit changes and push N+1 commits to `<target>`" |
| Ship | Uncommitted changes, no commits ahead | "Commit changes and push to `<target>`" |
| Ship | No uncommitted changes, N commits ahead | "Push N commits to `<target>`" (singular for one) |
| Ship | Nothing to push | "Push to `<target>`" |
| Custom Ship | | "Uses this project's custom Ship prompt" |
| Ship with a saved `ship` prompt | | "Uses your saved ship prompt" |
| Push to Branch, GitHub | Current branch is the base branch | "Push to new branch & open GitHub PR" |
| Push to Branch, GitHub | On another branch | "Push to GitHub PR branch `<branch>`" |
| Push to Branch, other host | Branch known | "Push to `<branch>`" |
| Push to Branch, other host | Branch unknown | "Push to branch" |

### Prompt texts

The default texts are fixed strings in the web build (ampcode.com's web bundle). `{target}` is the normalized target ref.

**Ship** (ampcode.com's web bundle, search `Ship all committed`):

```
Ship all committed and uncommitted changes to {target}. Commit any uncommitted changes before pushing. If push fails because {target} is ahead, rebase and resolve merge conflicts, checking with me before proceeding if there are any substantive conflicts, then push again. If rebasing floods add/add conflicts in unrelated files, run `git rebase --abort`, `git fetch --quiet --unshallow origin`, then rebase again. Fetch origin and rebase onto latest {target}, then run the full test suite before pushing. Do not repeat it after a later fetch, rebase, or rejected push unless conflict resolution or other local edits changed files after the successful test run. Ignore a failing check from the test suite only after verifying that the same check also fails on {target} without this thread's changes.
```

**Push to Branch** (ampcode.com's web bundle, search `Push the changes to a feature branch`):

```
Push the changes to a feature branch on the remote. If the current branch is {target}, first make a new feature branch with a short descriptive name following this repository's conventions; otherwise, reuse the existing feature branch we're on. If there are uncommitted changes, commit them on the feature branch before pushing. Do not merge directly into {target}.
```

For a GitHub project the app appends "Include {threadURL} in the GitHub pull request description and report its URL." When the thread URL is unknown, it appends "Get a pull request URL from GitHub after pushing and report it back to me." instead.

**Restack** (ampcode.com's web bundle, search `Refactor all committed`):

```
Refactor all committed and uncommitted changes since the merge base into logical, easy-to-review commits. Inspect the complete diff first, then reorder, split, squash, and edit commits as needed. Preserve the final tree and behavior. Do not push.
```

**Rebase** (ampcode.com's web bundle, search `Fetch origin and rebase the current work`). `{target}` follows [Target ref and the target line](#target-ref-and-the-target-line).

```
Fetch origin and rebase the current work onto latest {target}. If rebasing floods add/add conflicts in unrelated files, run `git rebase --abort`, `git fetch --quiet --unshallow origin`, then rebase again. Preserve all uncommitted changes and restore them after rebasing.
```

**Review** (ampcode.com's web bundle, search `function uy`). The default text is "Ask the oracle to review {review target}." For the Uncommitted scope the text is "Ask the oracle to review the current changes." The review target depends on the scope (ampcode.com's web bundle, search `function ly`):

| Scope | Review target |
|---|---|
| All Changes | `all changes since merge base <hash>, including untracked files` |
| One commit | `<baseHash>..<headHash>` |
| Compare With Branch | `all changes since the merge base <hash> with origin/<branch>, including untracked files` |

The web switches to a longer review text when the pane receives an agent kind, with one variant for `agent-sdk` and one for every other kind (ampcode.com's web bundle). [INFERENCE] The agent kind identifies a thread that runs an agent harness other than Amp's own. The app uses the same three texts with the same rule.

**Fix CI** (ampcode.com's web bundle, search `Fix CI?`). The user can edit it before sending.

```
CI failed on the latest push. Look at the failing checks, find the cause, fix it, run the relevant tests locally, and push the fix.
```

### Which Ship text applies

The web resolves the Ship text in this order, and the app follows it (ampcode.com's web bundle, function `a`):

1. If Ship Behavior is Push to Branch, the app uses the Push to Branch text.
2. If Ship Behavior is Custom Ship and the custom prompt is not blank, the app sends the custom prompt exactly as saved (Amp's docs).
3. If the user or workspace saved a `ship` prompt on the **Prompts** page, the app uses that text (Amp's docs).
4. Otherwise, the app uses the default Ship text.

Plugin appends follow, joined with blank lines (ampcode.com's web bundle, function `r`). Pending diff comments go first. Saved overrides for `review`, `refactor-commits`, and `update-base` replace those defaults the same way (ampcode.com's web bundle, search ``I(ya)[`refactor-commits`]``).

For Ship and the other built-in actions, the user or workspace can save an override on the **Prompts** page. The web reads saved prompts through `getChangesPrompts`, a `web-remote` function (Amp's docs). The app reads the overrides through the internal methods `listPrompts{thread}`, `expandPrompt{thread, name}`, and `resolveShipWorkflowPrompt{thread}` (`docs/protocol/private-protocol.md:530-533`). Those methods are in the CLI's method table, but the CLI does not call them, so they are NOT VERIFIED. If they fail, the sheet fills in the built-in text and shows "Your saved prompt could not be loaded. This is Amp's default text." above the field. The user sees exactly what will be sent and can edit it.

### Every action queues

Ship, Review, and other prompts Amp writes for the user always queue (Amp's docs). The app sends them as `client_append_user_msg{content, messageId, meta}` without `steer`. Ship and Push also set `meta.changesWorkflow` to `ship` or `push_branch`, a field in the message schema (ampcode.com's web bundle, search ``changesWorkflow:d([`ship`,`push_branch`])``) that the web sets on these sends (ampcode.com's web bundle, search `async function Fu`).

An append without `steer` while the agent is busy is read, not observed. Spike row S5 captured only a steered append and an interrupt (`spikes/protocol/RESULTS.md:15`). The M3 gate in `roadmap.md` adds a queued send.

While a turn runs, the message waits in the queued-message strip of the Thread pane and starts after the turn ends. From the strip, the user can steer it sooner with `client_steer_queued_msg` or remove it with `client_remove_queued_msg` (`docs/protocol/private-protocol.md:132`). A steered message is injected at the next tool boundary, not mid-tool (`spikes/protocol/RESULTS.md:15`). When the agent is idle, the message starts a turn at once.

The web also records a shipping state through `web-remote` `startThreadShipping` and cancels it through `cancelQueuedThreadShipping` when the queued message is removed (ampcode.com's web bundle). The app skips that bookkeeping. Progress shows in the transcript and in `thread_status{merging|merged}` (`docs/design/sketch/RATIONALE.md`, "Tradeoffs accepted", `docs/protocol/private-protocol.md:155`).

### Guarding against a second Ship

While a Ship or Push message is queued or its turn is running, the Ship button is disabled and its explanation reads "Already asked the agent to ship or push these changes." (ampcode.com's web bundle). The app detects that state from any queued or transcript message whose `meta.changesWorkflow` is set [INFERENCE: this covers a Ship sent from another client].

After the work lands and the agent finishes, an **Archive** button appears above the composer if the user sent nothing newer (Amp's docs). The Thread pane owns that button.

## Pull requests and CI

Everything in this section applies to GitHub projects only.

### Pull request status

When a pull request description includes the thread URL or `T-…` ID, the thread's title bar lists that pull request with its state: open, draft, queued, merged, or closed. Queued means waiting in a GitHub merge queue (Amp's docs). The app shows the same chip in the Thread title bar and the Changes header, with the number and state as text, and **Open on GitHub** opens it in a Custom Tab.

The web gets pull request data from its page data and from `web-remote` hover-card functions (Amp's docs). No `cli`-tier source is verified. Until one is, the app builds the chip from what it can see:

- After a Push message, the app reads GitHub pull request URLs from the agent's replies, because the Push prompt asks the agent to report the URL [INFERENCE: the reply format is not fixed]. The chip then shows "PR #N" without a state.
- `thread_status` with `merged` sets the state to merged (`docs/protocol/private-protocol.md:155`) [INFERENCE: `merged` refers to the thread's pull request].

### The CI row

A CI watch follows one exact commit. Queued and running checks appear in a row at the end of the thread, grouped by workflow with links to their runs. The row hides while the agent works. Completed results appear in the transcript, and CI messages steer an active agent (Amp's docs).

The row's text follows the web (ampcode.com's web bundle, search `` Waiting on ${ ``):

| State | Text |
|---|---|
| Progress could not be read | "CI status unavailable. Retrying…" |
| No run data yet | "Checking CI…" |
| All known checks finished | "Checks finished. Waiting for summary…" |
| N checks queued or running | "Waiting on N checks…" (singular for one) |

The web polls `web-remote` `getThreadGitHubAutomations` every 10 seconds while the page is visible and a CI watch is enabled (ampcode.com's web bundle, search `setInterval(()=>void n(),1e4)`). The app polls the internal `listGitHubAutomations{threadID}` and `getGitHubRepositoryCIStatus{repository, commitSHA?, workflowRunID?}` on the same schedule, only while the Thread pane is visible (`docs/protocol/private-protocol.md:433`, `:464`). The CLI does not call those methods, so they are NOT VERIFIED. If they fail, the app hides the live row. Completed results still reach the transcript, so the user loses only the running count.

These calls go to Amp's servers and never to the orb.

### Fix CI

**Fix CI** appears on a failed CI result in the transcript and on the CI row when a check failed. Its quick-action id on the web is `fix-ci` (Amp's docs). A commit's CI data is `{repository, commit:{sha, message, ciStatus, failureSummary?, failedChecks[]}}` with `ciStatus` of `success`, `failure`, `pending`, or `neutral` (Amp's docs). The confirm sheet holds the editable message and **Send**. The message queues like every Amp-written prompt.

## Commits and threads

Every commit the agent makes through its shell carries an `Amp-Thread-ID:` trailer with the thread URL (Amp's docs). A user can turn the trailer off (Amp's docs).

The commit list is the set of commit entries in the scope picker, newest first. When a commit carries `message`, the app parses its trailers. If `Amp-Thread-ID` names another thread, the commit entry shows **From Thread** with that thread's title, and the button opens it. The parser accepts any host whose path is `/threads/T-<uuid>`, so workspace custom domains work [INFERENCE: a custom-domain workspace serves thread URLs on its own host]. If `message` is absent, the app does not fetch it, and the entry has no link.

Thread search supports `commit:<SHA>` and `pr:<number>` (Amp's docs). The app uses it for **Find Threads for This Commit** on a commit without a trailer, if the search endpoint works. That endpoint is not verified (`docs/design/sketch/COVERAGE.md`, row `find-threads-activity-feed-and-query-syntax`).

## Limits and how the app degrades

| Limit | Value | Source | What the app does |
|---|---|---|---|
| Files for intelligent order | 500 across all sections | Amp's docs, ampcode.com's web bundle (search `function Zq`) | Alphabetical order and **Intelligent File Order Unavailable** |
| Sections per order request | 42 | ampcode.com's web bundle | Alphabetical order |
| Order request size | 200,000 characters of JSON | ampcode.com's web bundle | Truncates per-file diffs first, then falls back to alphabetical |
| Matched paths in an order result | At least 10 percent | ampcode.com's web bundle | Keeps the previous order and shows the error text |
| Branches in Compare With Branch | 500 | ampcode.com's web bundle | **Filter branches** queries `remote_branches{query}` |
| Additional repositories | 20 | ampcode.com's web bundle | Groups each one |
| Pending diff comments | 20 comments, 10,240 bytes | ampcode.com's web bundle | Refuses the new comment with the web's text |
| Explain selection | 20,000 characters selected | ampcode.com's web bundle | Disables **Explain** for larger selections |
| Live blob | HTTP 413 | ampcode.com's web bundle | `TooLarge`, "File exceeds the buffered transfer limit" |
| Capture blob | HTTP 413 | ampcode.com's web bundle | Server patch from `diff-captures/diff`, else `TooLarge`, "File too large to display" |
| Eager content load | Files under 128 KiB on both sides | ampcode.com's web bundle (search `AH=131072`) | Loads small files with the list and larger ones when expanded [INFERENCE: the web's purpose for `AH`] |
| v2 diff patches | None for files over 500 KB, over 2,000 changed lines, binary, or lock files | Amp's docs | Stat-only rows with `filteredFiles` |
| Highlighting and word diff | Lines up to 1,000 characters | ampcode.com's web bundle | Plain text above that |
| Client-side diff | 5 MiB or 50,000 lines per side | This design | Stat-only row with **Open File** |
| Displayed line length | 20,000 characters | This design | **Show Full Line** |

## Driving the pane with agent-device

agent-device caps a snapshot at 5000 nodes and cuts what comes last in document order (the TriFold's published specs). In the Review arrangement the diff is the last pane, so an oversized diff would lose its own rows first [INFERENCE: document order follows pane order from left to right].

A diff that composes every line breaks that cap. A 3,000-line diff with a separate node for the old number, the new number, the marker, and the code is 12,000 nodes [INFERENCE: 3,000 × 4]. The app keeps the count flat with two rules:

- The diff is one `LazyColumn` whose items are lines, with stable keys. A hunk is not an item, because a hunk item would compose all its lines at once.
- Each line is one merged semantics node whose text is the line numbers, the marker, and the code, such as `42 + for i in 0..64 {`. A wrapped line is still one node. A split row is two nodes, one per side, so each side can be selected (`docs/design/sketch/machine/ChangesFiles.kt`, `ChangesTab`).

At a 16sp line height the Review pane shows at most 49 lines [INFERENCE: 792dp / 16dp, before chrome]. With prefetch, the diff composes well under 100 line nodes, and each hunk header adds 4. The budget test loads a 3,000-line fixture from `:fake-amp`, takes an agent-device snapshot of the Review arrangement, and fails above 300 nodes for the Changes pane or 1,500 for the window (`docs/design/sketch/RATIONALE.md`, "Shape", which keeps diff and terminal views line-virtualized under 5000 nodes). These are budgets, not measurements.

Tests move through a diff with **Next Hunk** and **Previous Hunk**, which are real buttons, instead of blind scrolling. Only visible lines carry tags, so a test scrolls to a hunk before it asserts on a line.

### Test tags

| Element | Tag | Reports |
|---|---|---|
| Pane root | `pane-changes` | |
| Source label | `changes-source` | Text "Live", "Recorded", or "As of" |
| Scope picker | `changes-scope` | Content description "`<label>` · N commits ahead of `<base>`" when the branch is ahead |
| Scope option | `changes-scope-<optionId>` | `selected` |
| Scope menu's ahead line | `changes-scope-ahead-of-base` | Text "N commits ahead of `<base>`" |
| Order button | `changes-order` | Label text |
| Unified or split toggle | `changes-layout` | `checked` when split. Absent on a pane narrower than 600dp. |
| Wrap toggle | `changes-wrap` | `checked` |
| File row | `diff-file-<path>` | Text includes the change word and "muted" when muted |
| Stage or unstage | `diff-file-stage-<path>`, `diff-file-unstage-<path>` | |
| Hunk header | `diff-hunk-<path>-<index>` | |
| Hunk buttons | `diff-hunk-comment-<path>-<index>`, `diff-hunk-explain-<path>-<index>`, `diff-hunk-copy-<path>-<index>` | |
| Line | `diff-line-<path>-old-<n>` or `diff-line-<path>-new-<n>` | `selected` |
| Next and previous hunk | `diff-next-hunk`, `diff-prev-hunk` | |
| Comment pill | `diff-comment-<id>` | |
| Send comments | `diff-comments-send` | |
| Action buttons | `changes-ship`, `changes-review`, `changes-restack`, `changes-rebase`, `changes-sync` | Label text |
| Confirm sheet | `changes-confirm-prompt`, `changes-confirm-send`, `changes-confirm-cancel` | |
| Wake orb | `changes-wake-orb` | |
| Pull request chip | `pr-chip-<number>` | State text |
| CI row and Fix CI | `ci-row`, `ci-fix` | Row text |

`diff-file-<path>` matches the convention in `docs/design/verification.md:10` and the M6 gate, which finds `diff-file-calc.py` (`docs/design/roadmap.md:17`).

## Performance targets

These are targets for the first build, to be measured on the TriFold. None is measured yet.

| What | Target |
|---|---|
| Changes pane shows cached data after its tab opens | 300 ms |
| File list after `executor_workspace_maybe_changed`, up to 100 files on a runner | 1 s. Single git requests took 156 ms to 261 ms in the spike (`spikes/protocol/captures/s07-changes.jsonl:57-71`). |
| Line diff of a 3,000-line file pair, off the main thread | 50 ms |
| Word diff of one visible change block | 2 ms |
| Syntax colors for the first visible window | 100 ms, with plain text before that |
| Flinging a 3,000-line diff | Under 1 percent janky frames in `agent-device perf frames --json`, the same bar as the transcript (`docs/design/verification.md:76`) |
| Memory for a decoded 3,000-line diff | 2 MB, using the shared line store |
| Blob cache on disk | 64 MiB per account, least recently used first out |

## Open questions

1. Do recorded captures work for orb threads as `private-protocol.md` describes: the `latest` body, `blob/<sha>/raw`, and `diff?from=&to=` with a Bearer token? The spike could not start an orb. A spike on one orb thread settles it.
2. How does an observer request a blob over the socket? `executor_git_blob_chunk` is an executor message, and the catalogue lists no client request for it. The `/git-blob` HTTP path needs `AmpActor` auth, which the app has not exercised.
3. What `cli`-tier request wakes a paused orb, the native equivalent of the web's **Wake Orb** button?
4. Do `client_git_command` and `client_changes_file_order` work for contributors in multiplayer threads and for read-only viewers? The web's blocked-order messages suggest limits that the spike did not test.
5. Do the internal methods `listPrompts`, `expandPrompt`, and `resolveShipWorkflowPrompt` accept a Bearer token and return the user's saved overrides for the built-in actions?
6. Is there a `cli`-tier source for pull request status, and does `listGitHubAutomations` return the CI progress the web reads from `getThreadGitHubAutomations`?
7. Which client message carries a plugin RPC request such as `changes.prompt` from an observer to the executor?
8. What are the parameters of `stage_file_in_repository`, `unstage_file_in_repository`, and `file_diff_in_repository`?
9. Which CI failure counts as "on the latest push" for **Fix CI**? It could be the thread's last pushed commit or the newest CI result in the transcript.
10. Will Sign in with Amp become generally available, and will its `amp.git:read` scope read GitHub-linked repositories? Until then, the v2 repository diff stays unused.
11. Does `tm4e` tokenize fast enough on the TriFold for the 100 ms first-window target, and does `screens.md` agree to share one highlighter across the diff, the Files viewer, and transcript code blocks?
