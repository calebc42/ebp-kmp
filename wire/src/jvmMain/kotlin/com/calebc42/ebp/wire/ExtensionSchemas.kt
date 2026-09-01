// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

/**
 * Closed, renderer-owned value grammar used to validate advertised extension
 * node members without teaching EBP any downstream names.
 *
 * Every collection and scalar carrying variable-sized input is explicitly
 * bounded. References are a manifest-authoring feature and are resolved by
 * the projection generator, so an installed runtime schema is finite and
 * contains no unresolved or cyclic graph edges.
 */
sealed interface ExtensionValueSchema {
    /** A JSON boolean. */
    data object BooleanValue : ExtensionValueSchema

    /** A bounded integral JSON number. */
    data class IntegerValue(
        val minimum: Long,
        val maximum: Long,
    ) : ExtensionValueSchema {
        init {
            require(minimum <= maximum) { "integer minimum must not exceed maximum" }
        }
    }

    /** A bounded finite JSON number. */
    data class NumberValue(
        val minimum: Double,
        val maximum: Double,
    ) : ExtensionValueSchema {
        init {
            require(minimum.isFinite() && maximum.isFinite()) {
                "number bounds must be finite"
            }
            require(minimum <= maximum) { "number minimum must not exceed maximum" }
        }
    }

    /** A string bounded by UTF-16 code-unit length at this JVM wire seam. */
    data class StringValue(
        val minimumLength: Int,
        val maximumLength: Int,
    ) : ExtensionValueSchema {
        init {
            require(minimumLength >= 0) { "string minimum length must be non-negative" }
            require(minimumLength <= maximumLength) {
                "string minimum length must not exceed maximum length"
            }
        }
    }

    /** One member of a finite, non-empty string set. */
    data class EnumValue(val values: Set<String>) : ExtensionValueSchema {
        init {
            require(values.isNotEmpty() && values.none(String::isEmpty)) {
                "enum values must be non-empty strings"
            }
        }
    }

    /** A closed JSON object with disjoint required and optional members. */
    data class ObjectValue(
        val required: Map<String, ExtensionValueSchema>,
        val optional: Map<String, ExtensionValueSchema>,
    ) : ExtensionValueSchema {
        init {
            require(required.keys.none(String::isEmpty) && optional.keys.none(String::isEmpty)) {
                "object member names must be non-empty"
            }
            require(required.keys.intersect(optional.keys).isEmpty()) {
                "object required and optional members must be disjoint"
            }
        }
    }

    /** A bounded JSON array whose values share one schema. */
    data class ArrayValue(
        val items: ExtensionValueSchema,
        val minimumItems: Int,
        val maximumItems: Int,
    ) : ExtensionValueSchema {
        init {
            requireCollectionBounds(minimumItems, maximumItems, "array")
        }
    }

    /** A bounded identifier-keyed JSON object with homogeneous values. */
    data class IdentifierMapValue(
        val values: ExtensionValueSchema,
        val minimumEntries: Int,
        val maximumEntries: Int,
    ) : ExtensionValueSchema {
        init {
            requireCollectionBounds(minimumEntries, maximumEntries, "identifier map")
        }
    }

    /** One ordinary EBP Node; its own structural walk remains authoritative. */
    data object NodeValue : ExtensionValueSchema

    /** A bounded array of ordinary EBP Nodes. */
    data class NodeArrayValue(
        val minimumItems: Int,
        val maximumItems: Int,
    ) : ExtensionValueSchema {
        init {
            requireCollectionBounds(minimumItems, maximumItems, "node array")
        }
    }

    /** One ordinary EBP ActionDescriptor. */
    data object ActionDescriptorValue : ExtensionValueSchema
}

/** Typed required and optional members for one renderer-owned node type. */
data class ExtensionNodeSchema(
    val required: Map<String, ExtensionValueSchema>,
    val optional: Map<String, ExtensionValueSchema>,
) {
    init {
        require(required.keys.none(String::isEmpty) && optional.keys.none(String::isEmpty)) {
            "extension node member names must be non-empty"
        }
        require(required.keys.intersect(optional.keys).isEmpty()) {
            "extension node required and optional members must be disjoint"
        }
    }
}

/**
 * A renderer extension's semantic admission pass.
 *
 * [validate] runs once per structurally valid document, after core and typed
 * validation and before the owning [SurfaceStore] can mutate or persist. The
 * supplied [path] is the document's existing dot/index path root; validators
 * must preserve that path convention in [ContentInvalid] diagnostics.
 */
fun interface ExtensionSemanticValidator {
    fun validate(document: JsonObject, path: String)
}

private fun requireCollectionBounds(minimum: Int, maximum: Int, label: String) {
    require(minimum >= 0) { "$label minimum must be non-negative" }
    require(minimum <= maximum) { "$label minimum must not exceed maximum" }
}
