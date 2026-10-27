package com.agentifiers.amp.fleet.newthread

import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderConfigId
import com.agentifiers.amp.model.OrbProviderOption
import com.agentifiers.amp.model.OrbProviderOwner
import com.agentifiers.amp.model.OrbSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbProvidersTest {
    private val k8s = OrbProvider.SelfHosted.named("example-orbs-1")!!
    private val gpu = OrbProvider.SelfHosted.named("example-orbs-2")!!
    private val listedK8s = OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-00000001"), "example-orbs-1")
    private val listedGpu = OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-00000002"), "example-orbs-2")
    private val workspace = listOf(
        OrbProviderOption(listedK8s, isDefault = true, owner = OrbProviderOwner.Workspace),
        OrbProviderOption(listedGpu, isDefault = false, owner = OrbProviderOwner.Personal),
    )

    @Test
    fun `a provider name is trimmed and must fit Amp's 1 to 64 characters on one line`() {
        assertEquals("our-k8s-orbs", OrbProvider.SelfHosted.named("  our-k8s-orbs \n")?.name)
        assertEquals(64, OrbProvider.SelfHosted.named("a".repeat(64))?.name?.length)
        assertNull(OrbProvider.SelfHosted.named("a".repeat(65)))
        assertNull(OrbProvider.SelfHosted.named("   "))
        assertNull(OrbProvider.SelfHosted.named("two\nlines"))
    }

    @Test
    fun `typed names stay in the order added, once each, and remember the last pick`() {
        val book = OrbProviderBook().add(k8s).add(gpu).add(k8s).used(gpu)
        assertEquals(listOf(k8s, gpu), book.offered)
        assertEquals(gpu, book.initial)
    }

    @Test
    fun `forgetting the last pick opens the picker on Built-in Orbs`() {
        val book = OrbProviderBook().add(k8s).add(gpu).used(gpu)
        assertEquals(OrbProviderBook(typed = listOf(k8s)), book.forget(gpu))
        assertEquals(OrbProvider.Amp, book.forget(gpu).initial)
    }

    @Test
    fun `the picker opens on the last pick while it is listed, else the workspace default, else Built-in`() {
        assertEquals(listedGpu, OrbProviderBook(workspace, last = listedGpu).initial)
        assertEquals(OrbProvider.Amp, OrbProviderBook(workspace, last = OrbProvider.Amp).initial)
        assertEquals(listedK8s, OrbProviderBook(workspace, last = OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-gone"), "gone")).initial)
        assertEquals(listedK8s, OrbProviderBook(workspace).initial)
        assertEquals(OrbProvider.Amp, OrbProviderBook(workspace.map { it.copy(isDefault = false) }).initial)
    }

    @Test
    fun `a typed name that a listed provider has merges into the listed one`() {
        val book = OrbProviderBook(workspace).add(gpu).add(OrbProvider.SelfHosted.named("laptop")!!).used(gpu)
        assertEquals(listOf("configID-00000001", "configID-00000002", "laptop"), book.offered.map { (it as? OrbProvider.SelfHosted.Listed)?.configId?.raw ?: it.name })
        assertEquals(listedGpu, book.initial)
        assertEquals(listedGpu, book.merged(gpu))
    }

    @Test
    fun `the stored book reads back with its listed providers, typed names, and last pick`() {
        val book = OrbProviderBook(workspace).add(OrbProvider.SelfHosted.named("laptop")!!).used(listedGpu)
        val (listed, names, last) = OrbProviderCodec.encode(book)

        assertEquals("configID-00000001\tWorkspace\ttrue\texample-orbs-1\nconfigID-00000002\tPersonal\tfalse\texample-orbs-2", listed)
        assertEquals("laptop", names)
        assertEquals("config:configID-00000002", last)
        assertEquals(book, OrbProviderCodec.decode(listed, names, last))
        assertEquals(OrbProviderBook(typed = listOf(k8s), last = k8s), OrbProviderCodec.decode(null, "example-orbs-1", "example-orbs-1"))
    }

    @Test
    fun `the executor chip names a self-hosted provider before the size`() {
        assertEquals("Orb · example-orbs-2 · a1.small", executorLabel(ExecutorChoice.Orb(null, OrbSize("a1.small"), listedGpu), emptyList()))
        assertEquals("Orb · a1.small", executorLabel(ExecutorChoice.Orb(null, OrbSize("a1.small"), OrbProvider.Amp), emptyList()))
    }
}
