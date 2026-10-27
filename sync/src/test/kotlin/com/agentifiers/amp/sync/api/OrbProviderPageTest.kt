package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderConfigId
import com.agentifiers.amp.model.OrbProviderListing
import com.agentifiers.amp.model.OrbProviderOption
import com.agentifiers.amp.model.OrbProviderOwner
import com.agentifiers.amp.sync.internal.wire.Devalue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbProviderPageTest {
    @Test
    fun `a signed-in layout lists each provider with its default and owner`() {
        val message = """{"status":200,"type":"data","signedIn":true,"values":[{"orbProviderOptions":1},[2,7],""" +
            """{"configID":3,"name":4,"isDefault":5,"owner":6},"configID-00000001","example-orbs-1",true,"workspace",""" +
            """{"configID":8,"name":9,"isDefault":10,"owner":11},"configID-00000002","  my-mac  ",false,"personal",null,null]}"""

        assertEquals(
            OrbProviderListing.Listed(
                listOf(
                    OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-00000001"), "example-orbs-1"), true, OrbProviderOwner.Workspace),
                    OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-00000002"), "my-mac"), false, OrbProviderOwner.Personal),
                ),
            ),
            OrbProviderPage.parse(message),
        )
    }

    @Test
    fun `an entry without a configID or with a name Amp would refuse is dropped`() {
        val message = """{"status":200,"type":"data","signedIn":true,"values":[{"orbProviderOptions":1},[2,5,8],""" +
            """{"configID":3,"name":4},"","nameless",{"configID":6,"name":7},"configID-3","line${"\\n"}break",""" +
            """{"configID":9,"name":10},"configID-4","kept"]}"""

        assertEquals(
            OrbProviderListing.Listed(listOf(OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-4"), "kept"), false, OrbProviderOwner.Workspace))),
            OrbProviderPage.parse(message),
        )
    }

    @Test
    fun `a layout without a user, a redirect, and a 401 all mean the web session is signed out`() {
        assertEquals(OrbProviderListing.SignedOut, OrbProviderPage.parse("""{"status":200,"type":"data","signedIn":false,"values":[{}]}"""))
        assertEquals(OrbProviderListing.SignedOut, OrbProviderPage.parse("""{"status":200,"type":"redirect"}"""))
        assertEquals(OrbProviderListing.SignedOut, OrbProviderPage.parse("""{"status":401}"""))
    }

    @Test
    fun `a renamed route, a server error, and a failed fetch each give one short reason`() {
        assertEquals(OrbProviderListing.Failed("ampcode.com no longer serves the orb list (404)."), OrbProviderPage.parse("""{"status":404}"""))
        assertEquals(OrbProviderListing.Failed("ampcode.com answered 502."), OrbProviderPage.parse("""{"status":502}"""))
        assertEquals(OrbProviderListing.Failed("Couldn't reach ampcode.com."), OrbProviderPage.parse("""{"error":"TypeError: Failed to fetch"}"""))
        assertEquals(
            OrbProviderListing.Failed("ampcode.com did not list self-hosted orbs."),
            OrbProviderPage.parse("""{"status":200,"type":"data","signedIn":true,"values":[{"user":1},null]}"""),
        )
        assertEquals(OrbProviderListing.Failed("ampcode.com sent a page this app can't read."), OrbProviderPage.parse("<html>"))
        assertEquals(OrbProviderListing.Failed("ampcode.com sent a page this app can't read."), OrbProviderPage.parse("""{"status":200,"type":"unreadable"}"""))
        assertEquals(OrbProviderListing.Failed("ampcode.com did not list self-hosted orbs."), OrbProviderPage.parse("""{"status":200,"type":"data","values":null}"""))
        assertEquals(
            OrbProviderListing.Listed(listOf(OrbProviderOption(OrbProvider.SelfHosted.Listed(OrbProviderConfigId("c-1"), "solo"), false, OrbProviderOwner.Workspace))),
            OrbProviderPage.parse("""{"status":200,"type":"data","values":[{"orbProviderOptions":1},[2],{"configID":3,"name":4},"c-1","solo"]}"""),
        )
    }

    @Test
    fun `devalue specials, typed values, holes, and cycles unflatten to plain JSON`() {
        val flat = Json.parseToJsonElement(
            """[{"gone":-1,"zero":-6,"at":1,"list":2,"ids":4,"self":0,"later":6},["Date","2026-10-27T00:00:00.000Z"],[3,-2],"x",["Set",3,5],7,["Promise",5]]""",
        ) as JsonArray

        assertEquals(
            Json.parseToJsonElement("""{"gone":null,"zero":0,"at":"2026-10-27T00:00:00.000Z","list":["x",null],"ids":["x",7],"self":null,"later":null}"""),
            Devalue.unflatten(flat),
        )
    }
}
