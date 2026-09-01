// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionSchemaTest {
    private val nodeType = "example.typed"
    private val string = ExtensionValueSchema.StringValue(1, 8)
    private val typed = ExtensionNodeSchema(
        required = mapOf(
            "flag" to ExtensionValueSchema.BooleanValue,
            "count" to ExtensionValueSchema.IntegerValue(1, 3),
            "ratio" to ExtensionValueSchema.NumberValue(0.0, 1.0),
            "label" to string,
            "mode" to ExtensionValueSchema.EnumValue(setOf("one", "two")),
            "settings" to ExtensionValueSchema.ObjectValue(
                required = mapOf("name" to string),
                optional = mapOf("enabled" to ExtensionValueSchema.BooleanValue),
            ),
            "values" to ExtensionValueSchema.ArrayValue(
                items = ExtensionValueSchema.IntegerValue(0, 9),
                minimumItems = 1,
                maximumItems = 2,
            ),
            "tokens" to ExtensionValueSchema.IdentifierMapValue(
                values = string,
                minimumEntries = 1,
                maximumEntries = 2,
            ),
            "child" to ExtensionValueSchema.NodeValue,
            "children" to ExtensionValueSchema.NodeArrayValue(1, 2),
            "on_tap" to ExtensionValueSchema.ActionDescriptorValue,
        ),
        optional = emptyMap(),
    )
    private val row = NodeRow(typed.required.keys, typed.optional.keys)

    private fun vocabulary(
        semanticValidator: ExtensionSemanticValidator? = null,
    ) = NodeVocabulary(
        schema = NODE_SCHEMA + (nodeType to row),
        extensions = mapOf("example.typed" to setOf(nodeType)),
        extensionSchemas = mapOf(nodeType to typed),
        extensionSemanticValidators = semanticValidator?.let {
            mapOf("example.typed" to it)
        } ?: emptyMap(),
    )

    private val validRaw = """{
        "t":"$nodeType",
        "flag":true,
        "count":2,
        "ratio":0.5,
        "label":"valid",
        "mode":"two",
        "settings":{"name":"nested","enabled":false},
        "values":[0,9],
        "tokens":{"primary":"green"},
        "child":{"t":"text","text":"one"},
        "children":[{"t":"text","text":"two"}],
        "on_tap":{"action":"example.go"}
    }"""

    private fun validate(raw: String, advertisedTypes: Set<String>? = null) {
        SpecValidator.validateSurfaceSpec(
            Json.parseToJsonElement(raw) as JsonObject,
            advertisedTypes = advertisedTypes,
            nodeVocabulary = vocabulary(),
        )
    }

    @Test
    fun everyFormatTwoValueKindAcceptsItsValidShape() {
        validate(validRaw)
    }

    @Test
    fun typedFailuresKeepExactDotAndIndexPaths() {
        val cases = mapOf(
            validRaw.replace("\"flag\":true", "\"flag\":\"true\"") to "spec.flag",
            validRaw.replace("\"count\":2", "\"count\":4") to "spec.count",
            validRaw.replace("\"ratio\":0.5", "\"ratio\":2") to "spec.ratio",
            validRaw.replace("\"label\":\"valid\"", "\"label\":\"\"") to "spec.label",
            validRaw.replace("\"mode\":\"two\"", "\"mode\":\"three\"") to "spec.mode",
            validRaw.replace(
                "\"settings\":{\"name\":\"nested\",\"enabled\":false}",
                "\"settings\":{\"name\":\"nested\",\"extra\":false}",
            ) to "spec.settings.extra",
            validRaw.replace("\"values\":[0,9]", "\"values\":[0,9,1]") to "spec.values",
            validRaw.replace("\"values\":[0,9]", "\"values\":[0,10]") to "spec.values[1]",
            validRaw.replace(
                "\"tokens\":{\"primary\":\"green\"}",
                "\"tokens\":{\" primary\":\"green\"}",
            ) to "spec.tokens. primary",
            validRaw.replace(
                "\"child\":{\"t\":\"text\",\"text\":\"one\"}",
                "\"child\":{\"text\":\"one\"}",
            ) to "spec.child",
            validRaw.replace(
                "\"children\":[{\"t\":\"text\",\"text\":\"two\"}]",
                "\"children\":[false]",
            ) to "spec.children[0]",
            validRaw.replace(
                "\"on_tap\":{\"action\":\"example.go\"}",
                "\"on_tap\":{\"action\":\"example.go\",\"builtin\":\"surface.open\"}",
            ) to "spec.on_tap",
        )
        for ((raw, expectedPath) in cases) {
            val invalid = assertThrows(ContentInvalid::class.java) { validate(raw) }
            assertEquals(expectedPath, invalid.path)
        }
    }

    @Test
    fun unadvertisedExtensionTypeDegradesWithoutTypedOrSemanticValidation() {
        var semanticCalls = 0
        val withSemantic = vocabulary(
            ExtensionSemanticValidator { _, _ -> semanticCalls++ },
        )
        val malformed = Json.parseToJsonElement(
            """{"t":"$nodeType","flag":"not-a-boolean"}""",
        ) as JsonObject
        SpecValidator.validateSurfaceSpec(
            malformed,
            advertisedTypes = setOf("text"),
            nodeVocabulary = withSemantic,
        )
        assertEquals(0, semanticCalls)
    }

    @Test
    fun semanticValidationRunsAfterStructureAndBeforePersistence() {
        var semanticCalls = 0
        val validator = ExtensionSemanticValidator { _, path ->
            semanticCalls++
            throw ContentInvalid("$path.mode", "semantic rejection")
        }
        val withSemantic = vocabulary(validator)
        val valid = Json.parseToJsonElement(validRaw) as JsonObject

        val structural = validRaw.replace("\"count\":2", "\"count\":99")
        assertThrows(ContentInvalid::class.java) {
            SpecValidator.validateSurfaceSpec(
                Json.parseToJsonElement(structural) as JsonObject,
                nodeVocabulary = withSemantic,
            )
        }
        assertEquals(0, semanticCalls)

        class CountingBacking : SurfaceBacking {
            var writes = 0
            override fun load() = SurfaceState(emptyList(), emptyList())
            override fun replaceRecords(records: List<PersistedRecord>) {
                writes++
            }
            override fun replaceDrafts(drafts: List<PersistedDraft>) = Unit
        }
        val backing = CountingBacking()
        val store = SurfaceStore(
            maxSurfaces = 4,
            maxSurfaceIds = 8,
            appNodeTypes = NODE_SCHEMA.keys + nodeType,
            nodeVocabulary = withSemantic,
            backing = backing,
        )
        val invalid = assertThrows(ContentInvalid::class.java) {
            store.update("app:typed", 1, valid, null, null, null)
        }
        assertEquals("spec.mode", invalid.path)
        assertEquals(1, semanticCalls)
        assertEquals(0, backing.writes)
    }

    @Test
    fun vocabularyRejectsDriftBetweenRowsOwnersAndTypedSchemas() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeVocabulary(
                schema = NODE_SCHEMA + (nodeType to row),
                extensionSchemas = mapOf(nodeType to typed),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NodeVocabulary(
                schema = NODE_SCHEMA + (nodeType to row),
                extensions = mapOf("example.typed" to setOf(nodeType)),
                extensionSchemas = mapOf(
                    nodeType to typed.copy(optional = mapOf("extra" to string)),
                ),
            )
        }
    }

    @Test
    fun longLivedStoreReadsTheCurrentAppAdmissionConfiguration() {
        val dynamicType = "example.dynamic"
        val dynamicRow = NodeRow(
            required = setOf("children"),
            optional = setOf("flag"),
        )
        val dynamicTyped = ExtensionNodeSchema(
            required = mapOf(
                "children" to ExtensionValueSchema.NodeArrayValue(0, 4),
            ),
            optional = mapOf("flag" to ExtensionValueSchema.BooleanValue),
        )
        var enabled = false
        var semanticCalls = 0
        val disabledVocabulary = NodeVocabulary(
            schema = NODE_SCHEMA + (dynamicType to dynamicRow),
        )
        val enabledVocabulary = NodeVocabulary(
            schema = NODE_SCHEMA + (dynamicType to dynamicRow),
            extensions = mapOf("example.dynamic" to setOf(dynamicType)),
            extensionSchemas = mapOf(dynamicType to dynamicTyped),
            extensionSemanticValidators = mapOf(
                "example.dynamic" to ExtensionSemanticValidator { _, _ ->
                    semanticCalls++
                },
            ),
        )
        val store = SurfaceStore(
            maxSurfaces = 4,
            maxSurfaceIds = 8,
            appAdmissionProvider = {
                AppSurfaceAdmission(
                    nodeTypes = NODE_SCHEMA.keys + if (enabled) {
                        setOf(dynamicType)
                    } else {
                        emptySet()
                    },
                    nodeVocabulary = if (enabled) {
                        enabledVocabulary
                    } else {
                        disabledVocabulary
                    },
                )
            },
        )
        val malformed = Json.parseToJsonElement(
            """{"t":"$dynamicType","children":[{"t":"text","text":"fallback"}],"flag":"true"}""",
        ) as JsonObject

        // Disabled: the known row degrades through children and neither the
        // typed schema nor semantic validator applies.
        assertEquals(
            "applied",
            store.update("app:dynamic", 1, malformed, null, null, null).status,
        )
        assertEquals(0, semanticCalls)

        // The same store immediately adopts the enabled typed/semantic gate.
        enabled = true
        val failure = assertThrows(ContentInvalid::class.java) {
            store.update("app:dynamic", 2, malformed, null, null, null)
        }
        assertEquals("spec.flag", failure.path)
        assertEquals(0, semanticCalls)

        val valid = Json.parseToJsonElement(
            """{"t":"$dynamicType","children":[{"t":"text","text":"active"}],"flag":true}""",
        ) as JsonObject
        assertEquals(
            "applied",
            store.update("app:dynamic", 2, valid, null, null, null).status,
        )
        assertEquals(1, semanticCalls)
    }
}
