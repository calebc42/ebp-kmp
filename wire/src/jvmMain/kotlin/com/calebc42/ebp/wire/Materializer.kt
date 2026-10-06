// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SPEC 13.8.3 materialization: every `bind` of a snapshot resolved against
 * the surface data document before the snapshot is validated.
 *
 * The output is an ordinary document — `bind` stays on each node as the
 * universal attribute it is — so SpecValidator, the stateful extraction,
 * draft reconciliation, `capture_fields`, `input_state`, widgets, tiles and
 * the renderers all read a resolved tree and none of them learns about
 * pointers. Every rejection is a [ContentInvalid] at `<node>.bind.<member>`
 * (or at the document's own path), raised before any state changes.
 */
object Materializer {
    /** `contract.json` `data_model_schema.unbindable_members` (DataModelContractTest pins it). */
    val UNBINDABLE_MEMBERS: Set<String> = setOf(
        "t", "id", "key", "children", "bind", "template", "args", "meta",
        "semantics", "variants", "swipe_start", "swipe_end",
    )

    /** `contract.json` `data_model_schema.unbindable_tags`: types that may carry a Node or a descriptor. */
    val UNBINDABLE_TAGS: Set<String> = setOf(
        "action-descriptor", "node", "node-array", "variant-array", "swipe-object",
        "semantics-object", "menu-item-array", "menu-group-array", "tab-item-array",
        "rail-item-array", "button-group-item-array", "label-on-tap-object",
        "toolbar-id-or-item-array", "canvas-op-array", "table-row-array", "rich-span-array",
    )

    // SPEC 16.1/17.1: node members the structural walk never enters; a
    // binding is never resolved beneath them either.
    private val OPAQUE = setOf("args", "meta", "value")
    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

    /** The path of the first node under [value] carrying `bind`, or null. */
    fun findBind(value: JsonElement?, path: String): String? = when (value) {
        is JsonArray -> value.withIndex().firstNotNullOfOrNull { (i, child) -> findBind(child, "$path[$i]") }
        is JsonObject -> {
            val isNode = "t" in value
            if (isNode && "bind" in value) "$path.bind"
            else value.entries.firstNotNullOfOrNull { (key, child) ->
                if (isNode && key in OPAQUE) null else findBind(child, "$path.$key")
            }
        }
        else -> null
    }

    /** SPEC 13.8.1: the document's own bounds, measured on its JCS form. */
    fun checkDocument(data: JsonObject, path: String) {
        if (JsonPointer.depth(data) > WireLimits.MAX_DATA_DEPTH)
            throw ContentInvalid(path, "exceeds max_data_depth")
        if (jcsOctets(data) > WireLimits.MAX_DATA_OCTETS)
            throw ContentInvalid(path, "exceeds max_data_bytes")
    }

    /** SPEC 13.8.3: the Bind object's shape; SpecValidator runs this on every node as a backstop. */
    fun checkBindShape(node: JsonObject, path: String) {
        val bind = node["bind"] as? JsonObject
            ?: throw ContentInvalid("$path.bind", "bind must be an object")
        for ((member, pointer) in bind) JsonPointer.parse(pointer.asStringOrNull(), "$path.bind.$member")
    }

    /** A copy of [spec] with every bound member resolved against [data]. */
    fun materialize(spec: JsonObject, data: JsonObject, path: String, vocabulary: NodeVocabulary): JsonObject {
        val counter = IntArray(1)
        return walk(spec, path, data, vocabulary, counter) as JsonObject
    }

    private fun walk(
        value: JsonElement,
        path: String,
        data: JsonObject,
        vocabulary: NodeVocabulary,
        counter: IntArray,
    ): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.mapIndexed { i, child -> walk(child, "$path[$i]", data, vocabulary, counter) })
        is JsonObject -> {
            val isNode = "t" in value
            val resolved = if (isNode && "bind" in value) {
                resolveBindings(value, path, data, vocabulary, counter)
            } else emptyMap()
            val out = LinkedHashMap<String, JsonElement>(value.size + resolved.size)
            for ((key, child) in value) {
                out[key] = if (isNode && (key == "bind" || key in OPAQUE)) child
                else walk(child, "$path.$key", data, vocabulary, counter)
            }
            out.putAll(resolved)
            JsonObject(out)
        }
        else -> value
    }

    private fun resolveBindings(
        node: JsonObject,
        path: String,
        data: JsonObject,
        vocabulary: NodeVocabulary,
        counter: IntArray,
    ): Map<String, JsonElement> {
        val bind = node["bind"] as? JsonObject
            ?: throw ContentInvalid("$path.bind", "bind must be an object")
        val type = node.stringOrNull("t")
        val row = type?.let { vocabulary.schema[it] }
        val resolved = LinkedHashMap<String, JsonElement>()
        for ((member, pointer) in bind) {
            val bpath = "$path.bind.$member"
            if (++counter[0] > WireLimits.MAX_BINDINGS_PER_SNAPSHOT)
                throw ContentInvalid(bpath, "exceeds max_bindings_per_snapshot")
            unbindableReason(type, row, member)?.let { throw ContentInvalid(bpath, it) }
            if (member in node) throw ContentInvalid(bpath, "$member is both authored and bound")
            val tokens = JsonPointer.parse(pointer.asStringOrNull(), bpath)
            val reached = JsonPointer.resolve(data, tokens, bpath)
            if (reached != null) {
                val tag = memberTag(type, row, member)
                if (!matchesTag(tag, reached, type, member))
                    throw ContentInvalid(bpath, "resolved value is not ${tag ?: "typed"}")
                resolved[member] = reached
            } else if (row != null && member in row.required) {
                throw ContentInvalid(bpath, "required $member reaches nothing")
            }
        }
        return resolved
    }

    /** SPEC 13.8.3: why [member] may not be bound on a node of [type], or null. */
    private fun unbindableReason(type: String?, row: NodeRow?, member: String): String? {
        if (member in UNBINDABLE_MEMBERS) return "$member MUST NOT be bound"
        if (member.startsWith("on_")) return "$member is an ActionDescriptor and MUST NOT be bound"
        if (row != null && member !in row.required && member !in row.optional &&
            member !in UNIVERSAL_NODE_ATTRIBUTES)
            return "$member is not a member of $type"
        val tag = memberTag(type, row, member)
        if (tag in UNBINDABLE_TAGS) return "$member ($tag) MUST NOT be bound"
        return null
    }

    /** SPEC 17.1 resolution order for the member's field type (amendment #192). */
    private fun memberTag(type: String?, row: NodeRow?, member: String): String? {
        if (row == null || type == null)
            return if (member in UNIVERSAL_NODE_ATTRIBUTES) FIELD_TYPES[member] else null
        FIELD_TYPES["$type.$member"]?.let { return it }
        if ("$type.$member" in ENUMS || member in ENUMS) return "enum"
        FIELD_TYPES[member]?.takeIf { it != "varies-per-node" }?.let { return it }
        if (member.startsWith("on_")) return "action-descriptor"
        if (member == "children") return "node-array"
        return null
    }

    private fun matchesTag(tag: String?, value: JsonElement, type: String?, member: String): Boolean {
        val primitive = value as? JsonPrimitive
        val string = primitive?.takeIf { it.isString }?.content
        val boolean = primitive?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        val number = value.asDoubleOrNull()?.takeIf { it.isFinite() }
        val integral = integralLongOrNull(value)
        return when (tag) {
            null -> true
            "string", "yyyy-mm", "yyyy-mm-dd", "hh-mm", "color" -> string != null
            "identifier" -> string != null && IDENTIFIER.matches(string) &&
                string.toByteArray(Charsets.UTF_8).size <= WireLimits.MAX_IDENTIFIER_OCTETS
            "boolean" -> boolean != null
            "boolean-or-auto" -> boolean != null || string == "auto"
            "number" -> number != null
            "dp" -> number != null && number >= 0
            "positive-integer" -> integral != null && integral > 0
            "non-negative-integer" -> integral != null && integral >= 0
            "integer-1-12" -> integral != null && integral in 1..12
            "integer-1-31" -> integral != null && integral in 1..31
            "enum" -> string != null && string in (ENUMS["$type.$member"] ?: ENUMS[member] ?: emptySet())
            "font-weight" -> string == "normal" || string == "bold" ||
                (integral != null && integral in 100..900 && integral % 100 == 0L)
            "string-or-number", "option-value" -> string != null || number != null
            "option-value-or-array" -> string != null || number != null || value is JsonArray
            "number-or-corner-object" -> number != null || value is JsonObject
            "two-number-array" -> value is JsonArray && value.size == 2 &&
                value.all { it.asDoubleOrNull()?.isFinite() == true }
            "number-array" -> value is JsonArray && value.all { it.asDoubleOrNull()?.isFinite() == true }
            else -> when {
                tag.endsWith("-object") -> value is JsonObject
                tag.endsWith("-array") -> value is JsonArray
                else -> true
            }
        }
    }

    /** UTF-8 octets of [value] in its RFC 8785 form: sorted members, no whitespace. */
    fun jcsOctets(value: JsonElement): Int {
        val out = StringBuilder()
        fun emit(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    out.append('{')
                    e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                        if (i > 0) out.append(',')
                        out.append(JsonPrimitive(k).toString()).append(':')
                        emit(v)
                    }
                    out.append('}')
                }
                is JsonArray -> {
                    out.append('[')
                    e.forEachIndexed { i, v -> if (i > 0) out.append(','); emit(v) }
                    out.append(']')
                }
                else -> out.append(e.toString())
            }
        }
        emit(value)
        return out.toString().toByteArray(Charsets.UTF_8).size
    }
}
