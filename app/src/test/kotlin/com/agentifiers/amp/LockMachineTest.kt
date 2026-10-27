package com.agentifiers.amp

import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.LockAfter
import org.junit.Assert.assertEquals
import org.junit.Test

class LockMachineTest {
    private val oneMinute = AppLock.On(LockAfter.OneMinute)
    private val unlocked = LockMachine().loaded(oneMinute).unlocked()

    @Test
    fun coldStartLocksOnlyWhenAppLockIsOn() {
        assertEquals(LockState.Locked, LockMachine().loaded(oneMinute).state)
        assertEquals(LockState.Unlocked, LockMachine().loaded(AppLock.Off).state)
    }

    @Test
    fun changingTheSettingLaterNeverLocksByItself() {
        assertEquals(LockState.Unlocked, LockMachine().loaded(AppLock.Off).loaded(oneMinute).state)
    }

    @Test
    fun returningBeforeTheTimeoutStaysUnlocked() {
        assertEquals(LockState.Unlocked, unlocked.backgrounded(1_000).foregrounded(60_999, oneMinute).state)
    }

    @Test
    fun returningAtTheTimeoutLocks() {
        assertEquals(LockState.Locked, unlocked.backgrounded(1_000).foregrounded(61_000, oneMinute).state)
    }

    @Test
    fun immediatelyLocksOnAnyReturn() {
        assertEquals(LockState.Locked, unlocked.backgrounded(5_000).foregrounded(5_000, AppLock.On(LockAfter.Immediately)).state)
    }

    @Test
    fun theUnlockScreenItselfDoesNotLockAgain() {
        val locked = unlocked.backgrounded(0).foregrounded(120_000, oneMinute)
        val back = locked.backgrounded(121_000).unlocked().foregrounded(500_000, oneMinute)

        assertEquals(LockState.Unlocked, back.state)
    }

    @Test
    fun lockNowNeedsAppLockOn() {
        assertEquals(LockState.Locked, unlocked.lockedNow(oneMinute).state)
        assertEquals(LockState.Unlocked, LockMachine().loaded(AppLock.Off).lockedNow(AppLock.Off).state)
    }
}
