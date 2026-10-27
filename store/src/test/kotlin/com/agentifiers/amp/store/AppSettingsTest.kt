package com.agentifiers.amp.store

import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.LockAfter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsTest {

    private suspend fun roundTrip(value: AppSettingsProto): AppSettingsProto {
        val bytes = ByteArrayOutputStream().also { appSettingsSerializer.writeTo(value, it) }.toByteArray()
        return appSettingsSerializer.readFrom(ByteArrayInputStream(bytes))
    }

    @Test
    fun aMissingFileMeansAppLockIsOff() = runTest {
        assertEquals(AppLock.Off, appSettingsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))).appLock)
    }

    @Test
    fun everyLockAfterChoiceSurvivesTheFile() = runTest {
        val read = LockAfter.entries.map { roundTrip(AppSettingsProto().withAppLock(AppLock.On(it))).appLock }

        assertEquals(
            listOf(
                AppLock.On(LockAfter.Immediately),
                AppLock.On(LockAfter.OneMinute),
                AppLock.On(LockAfter.FiveMinutes),
                AppLock.On(LockAfter.ThirtyMinutes),
            ),
            read,
        )
    }

    @Test
    fun turningAppLockOffClearsTheChoice() = runTest {
        val on = AppSettingsProto().withAppLock(AppLock.On(LockAfter.OneMinute))

        assertEquals(AppLock.Off, roundTrip(on.withAppLock(AppLock.Off)).appLock)
    }

    @Test
    fun aChoiceFromANewerBuildStaysLocked() {
        assertEquals(AppLock.On(LockAfter.FiveMinutes), AppSettingsProto(appLockAfter = "TwoHours").appLock)
    }
}
