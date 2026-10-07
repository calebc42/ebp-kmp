// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SPEC 16.1 presentation identity as one slash-delimited path, the grammar
 * the Companion's renderer reports as `sourcePath` when a hook fires and the
 * engine keys SPEC 13.8.5 template items on. One segment per node: the
 * authored `key`, else the `id`, else the JSON position of the node under
 * its parent node (`children[2]`, `variants[0].content`, the empty string
 * for a document root — every `views` entry is a document root of its own,
 * as the renderer presents it), each with the type discriminator;
 * atoms are hex-encoded so identifiers containing `/` or `:` stay one
 * closed segment. Opaque members (`args`, `meta`, `value`) are never
 * positions. Pure and total.
 */
object IdentityPath {
    private const val DIGITS = "0123456789abcdef"

    fun atom(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xff
            out[index * 2] = DIGITS[byte ushr 4]
            out[index * 2 + 1] = DIGITS[byte and 0x0f]
        }
        return String(out)
    }

    fun segment(kind: String, value: String, type: String): String =
        "$kind:${atom(value)}:t:${atom(type)}"

    /** The path of [node] found at JSON [position] under the node at [parent]. */
    fun child(parent: String, node: JsonObject, position: String): String {
        val key = node.stringOrNull("key").orEmpty()
        val id = node.stringOrNull("id").orEmpty()
        val type = node.stringOrNull("t").orEmpty()
        val segment = when {
            key.isNotEmpty() -> segment("k", key, type)
            id.isNotEmpty() -> segment("id", id, type)
            else -> segment("p", position, type)
        }
        return "$parent/$segment"
    }
}

/**
 * SPEC 13.8.3 materialization: every `bind` of a snapshot resolved against
 * the surface data document before the snapshot is validated, and (SPEC
 * 13.8.5) every `template` expanded once per element of its bound array.
 *
 * The output is an ordinary document — `bind` and `template` stay on each
 * node as the universal attributes they are — so SpecValidator, the stateful
 * extraction, draft reconciliation, `capture_fields`, `input_state`, widgets,
 * tiles and the renderers all read a resolved tree and none of them learns
 * about pointers or templates. Every rejection is a [ContentInvalid] at
 * `<node>.bind.<member>`, at the authored template's path for a structural
 * violation, or at the document's own path, raised before any state changes.
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

    // `contract.json` `data_model_schema.template` (DataModelContractTest pins these).
    const val MAX_TEMPLATE_NESTING = 4
    val TEMPLATE_ROOT_MUST_BIND: Set<String> = setOf("key")
    val TEMPLATE_ROOT_FORBIDDEN: Set<String> = setOf("key", "id")
    val TEMPLATE_FORBIDDEN_TYPES: Set<String> = setOf("editor", "variant_host")

    // SPEC 16.1/17.1: node members the structural walk never enters; a
    // binding is never resolved beneath them either.
    private val OPAQUE = setOf("args", "meta", "value")
    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

    /** The scope of the one check a template gets when its array is empty. */
    private val PROBE: JsonElement = JsonPrimitive("\u0000probe")

    /** A materialized snapshot and its SPEC 13.8.5 item roots: identity path to bound key. */
    class Materialized(val spec: JsonObject, val itemKeys: Map<String, String>)

    /** The path of the first node under [value] carrying the universal attribute [member], or null. */
    fun findMember(value: JsonElement?, path: String, member: String): String? = when (value) {
        is JsonArray -> value.withIndex().firstNotNullOfOrNull { (i, child) -> findMember(child, "$path[$i]", member) }
        is JsonObject -> {
            val isNode = "t" in value
            if (isNode && member in value) "$path.$member"
            else value.entries.firstNotNullOfOrNull { (key, child) ->
                if (isNode && key in OPAQUE) null else findMember(child, "$path.$key", member)
            }
        }
        else -> null
    }

    /** The path of the first node under [value] carrying `bind`, or null. */
    fun findBind(value: JsonElement?, path: String): String? = findMember(value, path, "bind")

    /** SPEC 13.8.1: the document's own bounds, measured on its JCS form. */
    fun checkDocument(data: JsonObject, path: String) {
        if (JsonPointer.depth(data) > WireLimits.MAX_DATA_DEPTH)
            throw ContentInvalid(path, "exceeds max_data_depth")
        if (jcsOctets(data) > WireLimits.MAX_DATA_OCTETS)
            throw ContentInvalid(path, "exceeds max_data_bytes")
    }

    /**
     * SPEC 13.8.3: the Bind object's shape; SpecValidator runs this on every
     * node of a materialized document as a backstop. A copy expanded from a
     * template keeps its relative pointers, so the grammar is the scoped one.
     */
    fun checkBindShape(node: JsonObject, path: String) {
        val bind = node["bind"] as? JsonObject
            ?: throw ContentInvalid("$path.bind", "bind must be an object")
        for ((member, pointer) in bind)
            JsonPointer.parseScoped(pointer.asStringOrNull(), "$path.bind.$member", inScope = true)
    }

    /** A copy of [spec] with every bound member resolved against [data] and every template expanded. */
    fun materialize(spec: JsonObject, data: JsonObject, path: String, vocabulary: NodeVocabulary): JsonObject =
        materializeWithItems(spec, data, path, vocabulary).spec

    /** [materialize] plus the identity path of every expanded item root. */
    fun materializeWithItems(
        spec: JsonObject,
        data: JsonObject,
        path: String,
        vocabulary: NodeVocabulary,
    ): Materialized {
        val walk = Walk(data, vocabulary)
        val out = walk.walk(spec, path, null, 0, false, "", "") as JsonObject
        return Materialized(out, walk.itemKeys)
    }

    private class Walk(val data: JsonObject, val vocabulary: NodeVocabulary) {
        var bindings = 0
        val itemKeys = LinkedHashMap<String, String>()

        /**
         * @param scope the item scope's element, [PROBE], or null outside a template
         * @param depth how many template scopes enclose this value
         * @param templateRoot whether [value] is the copy of a template node
         * @param identityParent the identity path of the nearest enclosing node
         * @param position the JSON route from that node to [value]
         */
        fun walk(
            value: JsonElement,
            path: String,
            scope: JsonElement?,
            depth: Int,
            templateRoot: Boolean,
            identityParent: String,
            position: String,
        ): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.mapIndexed { i, child ->
                walk(child, "$path[$i]", scope, depth, false, identityParent, "$position[$i]")
            })
            is JsonObject -> if ("t" in value) walkNode(value, path, scope, depth, templateRoot, identityParent, position)
            else {
                val out = LinkedHashMap<String, JsonElement>(value.size)
                // SPEC 13.4: a multi-view spec's `views` entries are each
                // presented as a document root, so their identity position
                // is the root's, not `views.<name>`.
                val viewTable = identityParent.isEmpty() && position == "views"
                for ((key, child) in value)
                    out[key] = walk(child, "$path.$key", scope, depth, false, identityParent,
                        if (viewTable) "" else route(position, key))
                JsonObject(out)
            }
            else -> value
        }

        private fun route(position: String, key: String) = if (position.isEmpty()) key else "$position.$key"

        private fun walkNode(
            node: JsonObject,
            path: String,
            scope: JsonElement?,
            depth: Int,
            templateRoot: Boolean,
            identityParent: String,
            position: String,
        ): JsonObject {
            val templated = "template" in node
            // SPEC 13.8.5: the structural rules once, at the authored path;
            // copies and nested templates were covered by that scan.
            if (templated && scope == null) {
                checkTemplatedNode(node, path)
                checkTemplate(node.getValue("template"), "$path.template", depth + 1)
            }
            var expansion: JsonArray? = null
            val resolved = if ("bind" in node) {
                resolveBindings(node, path, scope, templateRoot) { expansion = it }
            } else emptyMap()
            // The identity segment reads the materialized key, so a copy's
            // descendants hang from the path the renderer will report.
            val headline = LinkedHashMap<String, JsonElement>(3)
            node["t"]?.let { headline["t"] = it }
            (resolved["key"] ?: node["key"])?.let { headline["key"] = it }
            node["id"]?.let { headline["id"] = it }
            val identity = IdentityPath.child(identityParent, JsonObject(headline), position)
            val out = LinkedHashMap<String, JsonElement>(node.size + resolved.size + 1)
            for ((key, child) in node) {
                out[key] = if (key == "bind" || key == "template" || key in OPAQUE) child
                else walk(child, "$path.$key", scope, depth, false, identity, key)
            }
            out.putAll(resolved)
            if (templateRoot && scope !== PROBE)
                itemKeys[identity] = headline["key"]?.asStringOrNull() ?: ""
            expansion?.let { elements ->
                val template = node.getValue("template")
                if (elements.isEmpty()) {
                    walk(template, "$path.template", PROBE, depth + 1, true, identity, "template")
                    out["children"] = JsonArray(emptyList())
                } else {
                    out["children"] = JsonArray(elements.mapIndexed { i, element ->
                        walk(template, "$path.children[$i]", element, depth + 1, true, identity, "children[$i]")
                    })
                }
            }
            return JsonObject(out)
        }

        private fun resolveBindings(
            node: JsonObject,
            path: String,
            scope: JsonElement?,
            templateRoot: Boolean,
            onExpansion: (JsonArray) -> Unit,
        ): Map<String, JsonElement> {
            val bind = node["bind"] as? JsonObject
                ?: throw ContentInvalid("$path.bind", "bind must be an object")
            val type = node.stringOrNull("t")
            val row = type?.let { vocabulary.schema[it] }
            val besideTemplate = "template" in node
            val resolved = LinkedHashMap<String, JsonElement>()
            for ((member, pointer) in bind) {
                val bpath = "$path.bind.$member"
                if (++bindings > WireLimits.MAX_BINDINGS_PER_SNAPSHOT)
                    throw ContentInvalid(bpath, "exceeds max_bindings_per_snapshot")
                unbindableReason(type, row, member, templateRoot, besideTemplate)
                    ?.let { throw ContentInvalid(bpath, it) }
                if (member in node) throw ContentInvalid(bpath, "$member is both authored and bound")
                val parsed = JsonPointer.parseScoped(pointer.asStringOrNull(), bpath, scope != null)
                if (!parsed.absolute && scope === PROBE) continue // present-but-unknown
                var reached = JsonPointer.resolve(if (parsed.absolute) data else scope!!, parsed.tokens, bpath)
                if (member == "children" && besideTemplate) {
                    if (reached == null) reached = JsonArray(emptyList())
                    val elements = reached as? JsonArray
                        ?: throw ContentInvalid(bpath, "a template's children pointer must reach an array")
                    if (elements.size > WireLimits.MAX_TEMPLATE_ITEMS)
                        throw ContentInvalid(bpath, "exceeds max_template_items")
                    onExpansion(elements)
                    continue
                }
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

        private fun checkTemplatedNode(node: JsonObject, path: String) {
            if ((node["bind"] as? JsonObject)?.containsKey("children") != true)
                throw ContentInvalid("$path.template", "template requires bind.children")
            if ("children" in node)
                throw ContentInvalid("$path.children", "children is both authored and templated")
        }

        /** SPEC 13.8.5: the structural rules of one authored template, at its own path. */
        private fun checkTemplate(template: JsonElement, path: String, depth: Int) {
            val root = template as? JsonObject
            if (root == null || "t" !in root) throw ContentInvalid(path, "must be a Node")
            if (depth > MAX_TEMPLATE_NESTING)
                throw ContentInvalid(path, "template scopes nest deeper than $MAX_TEMPLATE_NESTING")
            for (member in TEMPLATE_ROOT_FORBIDDEN) if (member in root)
                throw ContentInvalid("$path.$member", "a template node MUST NOT author $member")
            for (member in TEMPLATE_ROOT_MUST_BIND)
                if ((root["bind"] as? JsonObject)?.containsKey(member) != true)
                    throw ContentInvalid("$path.$member", "a template node MUST bind $member")
            scan(root, path, depth)
        }

        private fun scan(value: JsonElement, path: String, depth: Int) {
            when (value) {
                is JsonArray -> value.forEachIndexed { i, child -> scan(child, "$path[$i]", depth) }
                is JsonObject -> {
                    val type = if ("t" in value) value.stringOrNull("t") else null
                    val isNode = "t" in value
                    if (isNode) {
                        if ("id" in value) throw ContentInvalid("$path.id", "id is prohibited inside a template")
                        if (type != null && (type in TEMPLATE_FORBIDDEN_TYPES || vocabulary.isStateful(type, value)))
                            throw ContentInvalid(path, "$type is prohibited inside a template")
                        for (hook in ACTION_HOOK_KEYS)
                            value[hook]?.let { checkTemplateDescriptor(it, "$path.$hook") }
                        for (side in listOf("swipe_start", "swipe_end")) {
                            val swipe = value[side] as? JsonObject ?: continue
                            swipe["on_trigger"]?.let { checkTemplateDescriptor(it, "$path.$side.on_trigger") }
                            (swipe["actions"] as? JsonArray)?.forEachIndexed { i, action ->
                                (action as? JsonObject)?.get("on_trigger")
                                    ?.let { checkTemplateDescriptor(it, "$path.$side.actions[$i].on_trigger") }
                            }
                        }
                        if ("template" in value) {
                            checkTemplatedNode(value, path)
                            checkTemplate(value.getValue("template"), "$path.template", depth + 1)
                        }
                    }
                    for ((key, child) in value) {
                        if (isNode && (key == "bind" || key == "template" || key in OPAQUE)) continue
                        scan(child, "$path.$key", depth)
                    }
                }
                else -> Unit
            }
        }

        private fun checkTemplateDescriptor(descriptor: JsonElement, path: String) {
            val obj = descriptor as? JsonObject ?: return
            if ("capture_fields" in obj)
                throw ContentInvalid("$path.capture_fields", "capture_fields is prohibited inside a template")
            if ((obj["args"] as? JsonObject)?.containsKey("item") == true)
                throw ContentInvalid("$path.args.item", "conflicts with the item injection")
        }
    }

    /** SPEC 13.8.3/13.8.5: why [member] may not be bound on a node of [type], or null. */
    private fun unbindableReason(
        type: String?,
        row: NodeRow?,
        member: String,
        templateRoot: Boolean,
        besideTemplate: Boolean,
    ): String? {
        if ((member == "key" && templateRoot) || (member == "children" && besideTemplate)) return null
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
