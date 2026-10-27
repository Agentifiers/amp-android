package com.agentifiers.amp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.store.AppSettingsProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Checking lasts until the setting is read, so a cold start never shows a window before it knows to lock. */
enum class LockState { Checking, Locked, Unlocked }

/**
 * The lock rule without Android: the process locks at cold start when app lock is on, and on return to the
 * foreground after at least `after.seconds` in the background. Time spent locked never counts, so the
 * device-credential screen that unlocks the app cannot lock it again on return.
 */
internal data class LockMachine(val state: LockState = LockState.Checking, val backgroundSinceMs: Long? = null) {
    fun loaded(lock: AppLock): LockMachine =
        if (state != LockState.Checking) this else copy(state = if (lock is AppLock.On) LockState.Locked else LockState.Unlocked)

    fun backgrounded(nowMs: Long): LockMachine = if (state == LockState.Unlocked) copy(backgroundSinceMs = nowMs) else this

    fun foregrounded(nowMs: Long, lock: AppLock): LockMachine {
        val since = backgroundSinceMs ?: return this
        val due = lock is AppLock.On && nowMs - since >= lock.after.seconds * 1000
        return LockMachine(if (due) LockState.Locked else state, backgroundSinceMs = null)
    }

    fun unlocked(): LockMachine = LockMachine(LockState.Unlocked)

    fun lockedNow(lock: AppLock): LockMachine = if (lock is AppLock.On) LockMachine(LockState.Locked) else this
}

/** One lock for the whole process: every window shows the lock screen together and one unlock opens them all. */
@Singleton
class AppLockController @Inject constructor(private val store: DataStore<AppSettingsProto>) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSetting = MutableStateFlow<AppLock>(AppLock.Off)
    private val mutableState = MutableStateFlow(LockState.Checking)
    private var machine = LockMachine()
    private var startedActivities = 0

    val setting: StateFlow<AppLock> = mutableSetting.asStateFlow()
    val state: StateFlow<LockState> = mutableState.asStateFlow()

    init {
        scope.launch {
            store.data.collect { settings ->
                mutableSetting.value = settings.appLock
                move(machine.loaded(settings.appLock), "cold start")
            }
        }
    }

    fun setAppLock(lock: AppLock) {
        scope.launch { store.updateData { it.withAppLock(lock) } }
    }

    fun unlock() = move(machine.unlocked(), "unlock")

    /** Returns false when app lock is off, because there is nothing to lock with. Waits until the setting is read. */
    suspend fun lockNow(): Boolean {
        state.first { it != LockState.Checking }
        move(machine.lockedNow(setting.value), "lock now")
        return machine.state == LockState.Locked
    }

    private fun move(next: LockMachine, cause: String) {
        val before = machine.state
        machine = next
        mutableState.value = next.state
        if (next.state != before && next.state != LockState.Checking) Log.i(TAG, "${next.state.name.lowercase()} ($cause)")
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) move(machine.foregrounded(SystemClock.elapsedRealtime(), setting.value), "background timeout")
    }

    override fun onActivityStopped(activity: Activity) {
        if (--startedActivities == 0 && !activity.isChangingConfigurations) move(machine.backgrounded(SystemClock.elapsedRealtime()), "background")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "AmpLock"
    }
}
