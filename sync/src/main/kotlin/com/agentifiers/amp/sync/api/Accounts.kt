package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.sync.internal.auth.PendingCredential
import kotlinx.coroutines.CompletableDeferred

/**
 * An identity a sign-in method proved, before anything is stored. The UI asks "Add [email] on the server's
 * host?" and answers with [add] or [cancel]; the sign-in flow that emitted it is waiting on that answer.
 */
public class AccountCandidate internal constructor(
    public val email: String,
    public val server: ServerEnvironment,
    public val credential: CredentialKind,
    internal val pending: PendingCredential,
) {
    internal val decision = CompletableDeferred<Boolean>()

    public fun add() {
        decision.complete(true)
    }

    public fun cancel() {
        decision.complete(false)
    }
}

public data class AccountCommandResult(val intent: IntentId, val command: AccountCommand, val outcome: Outcome<Unit>)
