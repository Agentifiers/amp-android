package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.Guard

/**
 * Whether a Guarded command still means anything, read from a fold that has caught up after `client_resume`,
 * so it never drops a command on stale state. The setup-failed wording of `executor_status` is not captured;
 * any status naming a failure counts.
 */
internal fun holds(guard: Guard, fold: ThreadFold): Boolean = when (guard) {
    is Guard.QueuedStillPresent -> guard.queued in fold.queue
    Guard.AgentFailed -> fold.error != null || fold.live.activity == AgentActivity.Failed
    Guard.SetupFailed -> fold.executor.status?.contains("fail", ignoreCase = true) == true
    is Guard.GatePending -> fold.gates.contains(guard.gate)
}
