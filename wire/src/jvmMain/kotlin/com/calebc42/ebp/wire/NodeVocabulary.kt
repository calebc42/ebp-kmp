// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

/**
 * Declarative contract for a controlled, single-select string-options node.
 *
 * The option array is non-empty and contains closed objects whose label and
 * value members are non-empty strings. [requiredOptionMembers] and
 * [optionalOptionMembers] add renderer-owned scalar fields to that closed
 * shape. Option values are unique, and an authored selection must equal one
 * of them. Keeping member names and types in this record lets renderer
 * extensions install the invariant without the wire layer knowing any
 * downstream node type.
 */
data class SelectionOptionsRule(
    val optionsMember: String,
    val selectionMember: String,
    val labelMember: String,
    val valueMember: String,
    val requiredOptionMembers: Map<String, String> = emptyMap(),
    val optionalOptionMembers: Map<String, String> = emptyMap(),
)

/** Scalar type spellings accepted in renderer-owned selection option rules. */
val SELECTION_OPTION_MEMBER_TYPES: Set<String> = setOf(
    "boolean",
    "finite-number",
    "integer",
    "integer-1-6",
    "non-empty-string",
    "non-negative-integer",
    "positive-integer",
    "string",
)

/**
 * Declarative contract for an optional boolean whose true state depends on
 * its direct presentation parent.
 *
 * The member is type-checked whenever authored. `false` and absence are
 * context-free; `true` is admitted only when the nearest enclosing Node has
 * one of [parentTypes]. Member and parent names remain renderer-owned data.
 */
data class TrueRequiresParentRule(
    val member: String,
    val parentTypes: Set<String>,
)

/**
 * Admission metadata installed at an endpoint's presentation boundary.
 *
 * [NODE_SCHEMA] and [STATEFUL_NODE_TYPES] are EBP's own vocabulary. Optional
 * renderers may add schemas and rules without adding their names, frameworks,
 * or design-system contracts to the wire module or the EBP contract. The
 * engine still validates one raw EBP document; this is not a second UI model.
 *
 * [extensions] maps each positively advertised extension identifier to the
 * node types its provider owns. Ownership is receiver configuration, never
 * inferred from a node-name prefix.
 */
data class NodeVocabulary(
    val schema: Map<String, NodeRow> = NODE_SCHEMA,
    val statefulTypes: Set<String> = STATEFUL_NODE_TYPES,
    val statefulWhenPresent: Map<String, String> = mapOf(
        "button" to "checked",
        "icon_button" to "checked",
    ),
    val localEditorTypes: Set<String> = setOf("editor"),
    val atLeastOneNonEmpty: Map<String, Set<String>> = emptyMap(),
    val selectionOptions: Map<String, SelectionOptionsRule> = emptyMap(),
    val trueRequiresParent: Map<String, TrueRequiresParentRule> = emptyMap(),
    val extensions: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require(extensions.keys.all { it.contains('.') }) {
            "Renderer extension identifiers must be namespaced"
        }
        val owners = mutableMapOf<String, String>()
        for ((extension, nodeTypes) in extensions) {
            for (nodeType in nodeTypes) {
                require(nodeType in schema) {
                    "Renderer extension $extension owns unknown node $nodeType"
                }
                require(owners.put(nodeType, extension) == null) {
                    "Renderer node $nodeType has more than one owner"
                }
            }
        }
        require((statefulTypes + statefulWhenPresent.keys + localEditorTypes)
            .all { it in schema }) {
            "Stateful rules must name installed node schemas"
        }
        require(atLeastOneNonEmpty.all { (nodeType, members) ->
            nodeType in schema && members.isNotEmpty() &&
                members.all { member ->
                    val row = schema.getValue(nodeType)
                    member in row.required || member in row.optional
                }
        }) {
            "Non-empty constraints must name installed schema members"
        }
        require(selectionOptions.all { (nodeType, rule) ->
            val row = schema[nodeType] ?: return@all false
            val typedMembers =
                rule.requiredOptionMembers + rule.optionalOptionMembers
            rule.optionsMember in row.required &&
                rule.selectionMember in row.required + row.optional &&
                rule.optionsMember != rule.selectionMember &&
                rule.labelMember.isNotEmpty() &&
                rule.valueMember.isNotEmpty() &&
                rule.labelMember != rule.valueMember &&
                rule.requiredOptionMembers.keys
                    .intersect(rule.optionalOptionMembers.keys).isEmpty() &&
                typedMembers.keys.all(String::isNotEmpty) &&
                typedMembers.keys.none {
                    it == rule.labelMember || it == rule.valueMember
                } &&
                typedMembers.values.all { it in SELECTION_OPTION_MEMBER_TYPES }
        }) {
            "Selection-option constraints must name installed schema members " +
                "and supported, disjoint scalar option members"
        }
        require(trueRequiresParent.all { (nodeType, rule) ->
            val row = schema[nodeType] ?: return@all false
            rule.member in row.optional &&
                rule.parentTypes.isNotEmpty() &&
                rule.parentTypes.all { it in schema }
        }) {
            "True-requires-parent constraints must name optional schema members " +
                "and installed parent types"
        }
    }

    /** Whether [node] contributes durable input state under its installed rule. */
    fun isStateful(nodeType: String, node: kotlinx.serialization.json.JsonObject): Boolean {
        statefulWhenPresent[nodeType]?.let { return it in node }
        if (nodeType in localEditorTypes) {
            return node.boolOr("publish_state") && "document" !in node
        }
        return nodeType in statefulTypes
    }
}

/** EBP's implementation-neutral vocabulary with no downstream extensions. */
val EBP_NODE_VOCABULARY = NodeVocabulary()
