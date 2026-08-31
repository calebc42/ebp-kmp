// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererSelectionOptionsTest {
    private val nodeType = "example.selector"
    private val rule = SelectionOptionsRule(
        optionsMember = "options",
        selectionMember = "selected",
        labelMember = "label",
        valueMember = "value",
        requiredOptionMembers = mapOf("level" to "integer-1-6"),
        optionalOptionMembers = mapOf("detail" to "non-empty-string"),
    )
    private val vocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + (
            nodeType to NodeRow(setOf("options"), setOf("selected"))
        ),
        selectionOptions = mapOf(nodeType to rule),
        extensions = mapOf("example.design" to setOf(nodeType)),
    )

    private fun validate(raw: String) {
        SpecValidator.validateSurfaceSpec(
            Json.parseToJsonElement(raw) as JsonObject,
            nodeVocabulary = vocabulary,
        )
    }

    @Test
    fun typedRequiredAndOptionalMembersExtendTheClosedOptionShape() {
        validate(
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":1},{"label":"Child","value":"child","level":2,"detail":"Details"}],"selected":"child"}""",
        )
        // A rule may describe a selector whose controlled value is optional.
        validate(
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":1}]}""",
        )
    }

    @Test
    fun missingUnknownAndWrongTypedMembersRejectAtTheirExactPath() {
        val cases = mapOf(
            """{"t":"$nodeType","options":[{"label":"One","value":"one"}]}""" to
                "spec.options[0].level",
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":0}]}""" to
                "spec.options[0].level",
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":7}]}""" to
                "spec.options[0].level",
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":"1"}]}""" to
                "spec.options[0].level",
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":1,"detail":""}]}""" to
                "spec.options[0].detail",
            """{"t":"$nodeType","options":[{"label":"One","value":"one","level":1,"extra":true}]}""" to
                "spec.options[0].extra",
        )
        for ((raw, expectedPath) in cases) {
            val invalid = assertThrows(ContentInvalid::class.java) {
                validate(raw)
            }
            assertEquals(expectedPath, invalid.path)
        }
    }

    @Test
    fun vocabularyRejectsAmbiguousOrUnsupportedTypedMembers() {
        val invalidRules = listOf(
            rule.copy(optionalOptionMembers = mapOf("level" to "integer-1-6")),
            rule.copy(requiredOptionMembers = mapOf("label" to "string")),
            rule.copy(requiredOptionMembers = mapOf("level" to "integer-1-99")),
        )
        for (invalidRule in invalidRules) {
            assertThrows(IllegalArgumentException::class.java) {
                NodeVocabulary(
                    schema = NODE_SCHEMA + (
                        nodeType to NodeRow(setOf("options"), setOf("selected"))
                    ),
                    selectionOptions = mapOf(nodeType to invalidRule),
                )
            }
        }
    }
}
