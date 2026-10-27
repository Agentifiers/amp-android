package com.agentifiers.amp.design

import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.TurnKey
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.UserId

/** The Thread pane: title bar, More Actions, transcript, queue, composer, share sheet, multiplayer. */
object ThreadTags {
    fun pane(id: ThreadId) = "thread-pane-${id.raw}"
    const val error = "thread-error"
    const val noSelection = "thread-no-selection"
    const val readonlyBanner = "readonly-banner"

    const val title = "thread-title"
    const val agentStatus = "agent-status"
    const val project = "thread-project"
    const val mode = "thread-mode"
    const val executor = "thread-executor"
    const val parent = "thread-parent"
    const val pr = "thread-pr"
    const val presence = "thread-presence"
    const val multiplayer = "thread-multiplayer"
    const val share = "thread-share"
    const val menu = "thread-menu"
    const val menuMode = "thread-menu-mode"

    const val menuCopyLink = "thread-menu-copy-link"
    const val menuCopyMarkdown = "thread-menu-copy-markdown"
    const val menuExport = "thread-menu-export"
    const val menuCost = "thread-menu-cost"
    const val menuOrb = "thread-menu-orb"
    const val menuPin = "thread-menu-pin"
    const val menuColor = "thread-menu-color"
    const val menuLabels = "thread-menu-labels"
    const val menuRename = "thread-menu-rename"
    const val menuSnooze = "thread-menu-snooze"
    const val menuArchive = "thread-menu-archive"
    const val menuArchiveRemoveWorktree = "thread-menu-archive-remove-worktree"
    const val menuDelete = "thread-menu-delete"
    const val menuMarkUnread = "thread-menu-mark-unread"
    const val menuFind = "thread-menu-find"
    const val menuPalette = "thread-menu-palette"
    const val menuWatch = "thread-menu-watch"
    const val menuFullWidth = "thread-menu-full-width"
    const val menuNewWindow = "thread-menu-new-window"
    const val menuOpenWeb = "thread-menu-open-web"
    const val menuReportBug = "thread-menu-report-bug"
    const val menuShowCreases = "thread-menu-show-creases"
    const val menuResetCreases = "thread-menu-reset-creases"

    /** Title bar controls that moved into More Actions because the bar ran out of room. */
    const val menuShare = "thread-menu-share"
    const val menuMultiplayer = "thread-menu-multiplayer"
    const val menuPr = "thread-menu-pr"
    const val menuProject = "thread-menu-project"
    const val menuParent = "thread-menu-parent"
    const val menuPresence = "thread-menu-presence"

    const val find = "thread-find"
    const val findCount = "thread-find-count"
    const val findOlder = "thread-find-older"
    const val findInput = "thread-find-input"
    const val findNext = "thread-find-next"
    const val findPrevious = "thread-find-previous"
    const val findClose = "thread-find-close"
    const val findStop = "thread-find-stop"

    const val transcript = "transcript"
    const val loadEarlier = "transcript-load-earlier"
    const val jumpToLatest = "transcript-jump-latest"
    fun message(id: MessageId) = "transcript-message-${id.raw}"
    fun messageMore(id: MessageId) = "message-${id.raw}-more"

    fun turn(key: TurnKey) = "turn-${key.anchor.raw}"
    fun turnEnd(key: TurnKey) = "turn-${key.anchor.raw}-end"

    fun step(firstToolUse: ToolCallId) = "step-${firstToolUse.raw}"
    fun stepToggle(firstToolUse: ToolCallId) = "step-${firstToolUse.raw}-toggle"
    fun toolCard(id: ToolCallId) = "tool-card-${id.raw}"
    fun toolToggle(id: ToolCallId) = "tool-card-${id.raw}-toggle"
    fun text(key: StepKey) = "text-${key.raw.replace(':', '-')}"
    fun thinking(key: StepKey) = "thinking-${key.raw.replace(':', '-')}"
    fun manual(id: MessageId) = "manual-${id.raw}"
    fun pending(id: MessageId) = "pending-${id.raw}"
    fun pendingRetry(id: MessageId) = "pending-${id.raw}-retry"
    fun image(message: MessageId, index: Int) = "image-${message.raw}-$index"
    const val activityRow = "transcript-activity"
    const val compaction = "transcript-compaction"
    const val recap = "transcript-recap"
    const val recapDismiss = "transcript-recap-dismiss"
    const val errorRetry = "thread-error-retry"
    const val errorDismiss = "thread-error-dismiss"
    const val errorOpenWeb = "thread-error-open-web"
    const val lightbox = "lightbox"
    const val lightboxClose = "lightbox-close"
    const val lightboxSave = "lightbox-save"
    const val lightboxShare = "lightbox-share"
    const val lightboxBackground = "lightbox-background"
    const val gallery = "gallery"
    const val galleryPosition = "gallery-position"
    const val galleryPrevious = "gallery-previous"
    const val galleryNext = "gallery-next"
    fun galleryItem(index: Int) = "gallery-item-$index"
    const val imageBroken = "image-broken"
    const val imageBrokenOpenWeb = "image-broken-open-web"

    const val queue = "queue-strip"
    fun queued(id: QueuedMessageId) = "queued-${id.raw}"
    fun queuedAction(id: QueuedMessageId, action: QueuedAction) = "queued-${id.raw}-${action.slug}"

    enum class QueuedAction(val slug: String) {
        Steer("steer"),
        Up("up"),
        Down("down"),
        Remove("remove"),
    }

    const val composerNotice = "composer-notice"
    const val composerInput = "composer-input"

    const val composerSend = "composer-send"
    const val composerQueue = "composer-queue"
    const val composerInterrupt = "composer-interrupt"

    const val composerMode = "composer-mode"
    const val composerExecutor = "composer-executor"
    const val composerReferencePrevious = "composer-reference-previous"
    const val composerSpawnRetry = "composer-spawn-retry"
    const val composerSpawnOther = "composer-spawn-other"
    const val composerSpawnDiscard = "composer-spawn-discard"
    fun outbox(id: MessageId) = "composer-outbox-${id.raw}"
    const val composerDiscard = "composer-notice-discard"
    const val composerResend = "composer-notice-resend"
    const val suggestedReplies = "suggested-replies"
    fun suggestedReply(index: Int) = "suggested-reply-$index"

    /** The New Thread composer and its executor and mode pickers. */
    const val newThread = "new-thread-composer"
    const val newThreadNotice = "new-thread-notice"
    const val executorPicker = "executor-picker"
    const val executorSectionOrb = "executor-section-orb"
    const val executorSectionRunners = "executor-section-runners"
    const val executorSectionShared = "executor-section-shared"
    const val executorOrb = "executor-orb"
    fun executorRunner(id: RunnerId) = "executor-runner-${slug(id.raw)}"
    fun runnerDirectory(index: Int) = "runner-directory-$index"
    const val runnerCurrent = "runner-worktree-current"
    const val runnerNewWorktree = "runner-worktree-new"
    const val worktreeName = "worktree-name"
    const val worktreeCreate = "worktree-create"
    const val orbProjectNone = "orb-project-none"
    fun orbProject(id: ProjectId) = "orb-project-${slug(id.raw)}"
    fun orbSize(size: OrbSize) = "orb-size-${slug(size.key)}"
    const val orbProviderBuiltIn = "orb-provider-built-in"
    fun orbProviderSelfHosted(provider: OrbProvider.SelfHosted) = "orb-provider-self-hosted-${slug(provider.name)}"
    fun orbProviderForget(provider: OrbProvider.SelfHosted.Named) = "orb-provider-forget-${slug(provider.name)}"
    const val orbProviderSignIn = "orb-provider-sign-in"
    const val orbProviderStatus = "orb-provider-status"
    const val orbProviderAdd = "orb-provider-add"
    const val orbProviderName = "orb-provider-name"
    const val orbProviderSave = "orb-provider-save"
    const val orbUse = "orb-use"
    const val modePicker = "mode-picker"
    const val modeTabAmp = "mode-tab-amp"
    const val modeTabRaw = "mode-tab-raw"
    const val modeSearch = "mode-search"
    const val modeFast = "mode-fast"
    const val modeRawOpenWeb = "mode-raw-open-web"
    fun modeOption(key: String) = "mode-option-${slug(key)}"

    private fun slug(raw: String) = raw.lowercase().replace(nonAlphanumeric, "-").trim('-')
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    const val composerMentionPicker = "composer-mention-picker"
    fun mentionMember(id: UserId) = "mention-member-${id.raw}"
    fun mentionThread(id: ThreadId) = "mention-thread-${id.raw}"
    fun mentionFile(index: Int) = "mention-file-$index"

    const val composerAttach = "composer-attach"
    const val attachCamera = "attach-camera"
    const val attachPhotos = "attach-photos"
    const val attachFiles = "attach-files"
    const val attachScreenshot = "attach-screenshot"
    const val attachRecord = "attach-record"
    const val attachLatestRecording = "attach-latest-recording"
    const val attachLatestRecordingOk = "attach-latest-recording-ok"
    const val attachLatestRecordingAttach = "attach-latest-recording-attach"
    const val attachLatestRecordingCancel = "attach-latest-recording-cancel"
    fun attachmentRemove(index: Int) = "attachment-$index-remove"
    fun attachmentRetry(index: Int) = "attachment-$index-retry"

    const val composerMic = "composer-mic"
    const val composerMicStop = "composer-mic-stop"
    const val composerMicSend = "composer-mic-send"

    const val shareSheet = "share-sheet"
    const val shareCopy = "share-copy"
    const val shareSystem = "share-system"
    const val shareVisibilityPrivate = "share-visibility-private"
    const val shareVisibilityWorkspace = "share-visibility-workspace"
    const val shareVisibilityGroup = "share-visibility-group"
    const val shareVisibilityUnlisted = "share-visibility-unlisted"
    const val shareContribute = "share-contribute"
    const val shareCreateWorkspace = "share-create-workspace"
    const val shareStatus = "share-status"
    const val shareFailure = "share-failure"
    const val shareOpenWeb = "share-open-web"

    const val multiplayerEnable = "multiplayer-enable"
    const val multiplayerEnd = "multiplayer-end"
    const val multiplayerCancel = "multiplayer-cancel"
    const val multiplayerTimeLeft = "multiplayer-time-left"
    const val multiplayerDuration5m = "multiplayer-duration-5m"
    const val multiplayerDuration1h = "multiplayer-duration-1h"
    const val multiplayerDuration1d = "multiplayer-duration-1d"
    const val multiplayerDuration7d = "multiplayer-duration-7d"
    const val multiplayerFailure = "multiplayer-failure"
    const val multiplayerOpenWeb = "multiplayer-open-web"

    fun presenceViewer(id: UserId) = "thread-presence-${id.raw}"
    const val composerTyping = "composer-typing"

    const val codeCopy = "code-copy"
    const val codeShowAll = "code-show-all"

    const val toolOpenChanges = "tool-open-changes"
    const val toolOpenFile = "tool-open-file"
    const val toolShowAll = "tool-show-all"
    fun toolThread(id: ThreadId) = "tool-thread-${id.raw}"
}
