package com.agentifiers.amp.notify

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.api.BackgroundAccess
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide handles for :notify's Android components. The application context is captured by
 * [NotifyInitProvider], the way Firebase and androidx.startup capture theirs, so the debug `inject-push` event and
 * every receiver reach the same [Notifier].
 */
internal object NotifyRuntime {
    lateinit var app: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val provider: BackgroundAccessProvider by lazy { EntryPointAccessors.fromApplication(app, NotifyEntryPoint::class.java).background() }
    val notifier: Notifier by lazy { Notifier(app, EntryPointAccessors.fromApplication(app, NotifyEntryPoint::class.java).databases()) }

    fun background(account: AccountId): BackgroundAccess = provider.forAccount(account)

    fun foreground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun attach(context: Context) {
        app = context.applicationContext
    }
}

/**
 * Starts :notify without a line in :app. A ContentProvider runs before `Application.onCreate`, and the engine is built
 * there, so the start is posted to the main looper and runs right after it.
 */
class NotifyInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        NotifyRuntime.attach(requireNotNull(context))
        Handler(Looper.getMainLooper()).post(NotifyStartup::start)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Everything :notify runs for the life of the process: channels, the poll backstop, push registration, Watch, and live turns. */
internal object NotifyStartup {
    fun start() {
        NotifyRuntime.notifier.ensureChannels()
        PollWorker.schedule(NotifyRuntime.app)
        PushRegistrar.start(NotifyRuntime.app, NotifyRuntime.provider, NotifyRuntime.scope)
        WatchStarter.start(NotifyRuntime.app, NotifyRuntime.provider, NotifyRuntime.scope)
        LiveTurns.start(NotifyRuntime.provider)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = LiveTurns.onStart()

            override fun onStop(owner: LifecycleOwner) {
                WatchStarter.onAppStopped(NotifyRuntime.app, NotifyRuntime.provider)
                LiveTurns.onStop(NotifyRuntime.app, NotifyRuntime.provider)
            }
        })
    }
}
