package com.agentifiers.amp.design.components

import kotlin.metadata.KmClassifier
import kotlin.metadata.KmValueParameter
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reads the controls' Kotlin signatures from their compiled metadata. A control that loses a required parameter
 * still compiles at every call site that passes it, so only this test notices the requirement went away.
 */
class ControlSignatureTest {
    private fun parameters(facade: String, function: String, name: String): List<KmValueParameter> {
        val metadata = Class.forName("com.agentifiers.amp.design.components.$facade").getAnnotation(Metadata::class.java)
        val pkg = (KotlinClassMetadata.readStrict(metadata) as KotlinClassMetadata.FileFacade).kmPackage
        return pkg.functions.filter { it.name == function }.map { f -> f.valueParameters.single { it.name == name } }
    }

    private fun parameter(facade: String, function: String, name: String) = parameters(facade, function, name).single()

    private fun describe(p: KmValueParameter): String {
        val type = (p.type.classifier as KmClassifier.Class).name + if (p.type.isNullable) "?" else ""
        return "${p.name}: $type" + if (p.declaresDefaultValue) " = default" else ""
    }

    @Test
    fun anIconButtonCannotBeCreatedWithoutAContentDescription() {
        assertEquals("contentDescription: kotlin/String", describe(parameter("AmpIconButtonKt", "AmpIconButton", "contentDescription")))
    }

    @Test
    fun everyControlTakesARequiredTestTag() {
        val controls = listOf(
            "AmpButtonKt" to "AmpButton",
            "AmpIconButtonKt" to "AmpIconButton",
            "AmpChipKt" to "AmpChip",
            "AmpSuggestionChipKt" to "AmpSuggestionChip",
            "AmpPaneHeaderKt" to "AmpPaneHeader",
            "AmpEmptyStateKt" to "AmpEmptyState",
        )
        assertEquals(
            List(controls.size) { "testTag: kotlin/String" },
            controls.map { (facade, function) -> describe(parameter(facade, function, "testTag")) },
        )
    }

    @Test
    fun everyTextFieldOverloadTakesARequiredTestTag() {
        assertEquals(
            listOf("testTag: kotlin/String", "testTag: kotlin/String", "testTag: kotlin/String"),
            parameters("AmpTextFieldKt", "AmpTextField", "testTag").map(::describe) + parameters("AmpTextFieldKt", "AmpSecureTextField", "testTag").map(::describe),
        )
    }
}
