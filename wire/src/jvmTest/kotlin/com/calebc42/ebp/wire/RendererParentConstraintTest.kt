// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererParentConstraintTest {
    private val nodeType = "example.pinnable"
    private val vocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + (
            nodeType to NodeRow(emptySet(), setOf("sticky"))
        ),
        trueRequiresParent = mapOf(
            nodeType to TrueRequiresParentRule(
                member = "sticky",
                parentTypes = setOf("lazy_column"),
            ),
        ),
        extensions = mapOf("example.design" to setOf(nodeType)),
    )

    private fun node(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    private fun validate(raw: String, advertisedTypes: Set<String>? = null) {
        SpecValidator.validateSurfaceSpec(
            node(raw),
            advertisedTypes = advertisedTypes,
            nodeVocabulary = vocabulary,
        )
    }

    @Test
    fun absentAndFalseAreContextFreeWhileTrueRequiresADirectAllowedParent() {
        validate("""{"t":"$nodeType"}""")
        validate("""{"t":"$nodeType","sticky":false}""")
        validate(
            """{"t":"lazy_column","children":[{"t":"$nodeType","sticky":true}]}""",
        )

        val root = assertThrows(ContentInvalid::class.java) {
            validate("""{"t":"$nodeType","sticky":true}""")
        }
        assertEquals("spec.sticky", root.path)

        val grandchild = assertThrows(ContentInvalid::class.java) {
            validate(
                """{"t":"lazy_column","children":[{"t":"column","children":[{"t":"$nodeType","sticky":true}]}]}""",
            )
        }
        assertEquals("spec.children[0].children[0].sticky", grandchild.path)
    }

    @Test
    fun authoredConstraintMemberMustBeBoolean() {
        val invalid = assertThrows(ContentInvalid::class.java) {
            validate("""{"t":"$nodeType","sticky":"true"}""")
        }
        assertEquals("spec.sticky", invalid.path)
    }

    @Test
    fun installedButUnadvertisedNodeStillDegrades() {
        validate(
            """{"t":"$nodeType","sticky":"not-a-boolean"}""",
            advertisedTypes = CORE_NODE_SET,
        )
    }

    @Test
    fun unadvertisedParentCannotGrantItsRendererOwnedBehavior() {
        val invalid = assertThrows(ContentInvalid::class.java) {
            validate(
                """{"t":"lazy_column","children":[{"t":"$nodeType","sticky":true}]}""",
                advertisedTypes = setOf(nodeType),
            )
        }
        assertEquals("spec.children[0].sticky", invalid.path)
    }

    @Test
    fun vocabularyRejectsUninstalledParentsAndNonOptionalMembers() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeVocabulary(
                schema = NODE_SCHEMA + (
                    nodeType to NodeRow(emptySet(), setOf("sticky"))
                ),
                trueRequiresParent = mapOf(
                    nodeType to TrueRequiresParentRule(
                        member = "sticky",
                        parentTypes = setOf("missing_parent"),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NodeVocabulary(
                schema = NODE_SCHEMA + (
                    nodeType to NodeRow(setOf("sticky"), emptySet())
                ),
                trueRequiresParent = mapOf(
                    nodeType to TrueRequiresParentRule(
                        member = "sticky",
                        parentTypes = setOf("lazy_column"),
                    ),
                ),
            )
        }
    }
}
