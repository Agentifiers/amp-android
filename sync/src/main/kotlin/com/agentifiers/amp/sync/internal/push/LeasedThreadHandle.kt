package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.sync.api.ViewerToken
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records each viewer lease in [leases], so a thread a window shows never posts a notification, and asks for the
 * thread's push subscription when a window opens it (`notifications.md`, "Subscriptions are per thread").
 */
internal class LeasedThreadHandle(
    private val engine: ThreadHandle,
    private val leases: LeaseLedger,
    private val onOpened: (ThreadHandle) -> Unit,
) : ThreadHandle by engine {
    override fun attach(token: ViewerToken): AutoCloseable {
        leases.acquire(id)
        onOpened(engine)
        val inner = engine.attach(token)
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                inner.close()
                leases.release(id)
            }
        }
    }
}
