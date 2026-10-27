package com.agentifiers.amp.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceChannelTest {
    @Test
    fun `a build with a relay, Firebase and Play services forwards over the relay`() {
        assertEquals(DeviceChannel.Relay, DeviceChannel.pick(relay = true, firebase = true, playServices = true, distributor = true))
    }

    @Test
    fun `a build with no relay takes UnifiedPush even with Firebase and Play services`() {
        assertEquals(DeviceChannel.UnifiedPush, DeviceChannel.pick(relay = false, firebase = true, playServices = true, distributor = true))
    }

    @Test
    fun `a build with no relay and no distributor falls back to polling`() {
        assertEquals(DeviceChannel.None, DeviceChannel.pick(relay = false, firebase = true, playServices = true, distributor = false))
    }

    @Test
    fun `a relay without Firebase config takes UnifiedPush`() {
        assertEquals(DeviceChannel.UnifiedPush, DeviceChannel.pick(relay = true, firebase = false, playServices = true, distributor = true))
    }
}
