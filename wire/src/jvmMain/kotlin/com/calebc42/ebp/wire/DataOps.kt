// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * SPEC 13.8.4: the DataOps of one `surface.data` request applied in order to
 * a copy of a document — `set`, `remove` and (amendment #196) `insert`.
 * Pure; every rejection is a [ContentInvalid] at `<prefix>[i].path`,
 * `<prefix>[i].op` or `<prefix>[i]`, the paths `goldens/data-ops.golden`
 * pins and the Companion reports.
 */
object DataOps {
    /** The members each op kind admits (`contract.json` `data_model_schema.ops`). */
    val SHAPES: Map<String, Set<String>> = mapOf(
        "set" to setOf("op", "path", "value"),
        "remove" to setOf("op", "path"),
        "insert" to setOf("op", "path", "value"),
    )

    fun applyAll(document: JsonObject, ops: JsonArray, prefix: String = "ops"): JsonObject {
        var current = document
        ops.forEachIndexed { i, op -> current = apply(current, op, "$prefix[$i]") }
        return current
    }

    fun apply(document: JsonObject, op: JsonElement, path: String): JsonObject {
        val obj = op as? JsonObject ?: throw ContentInvalid(path, "DataOp must be an object")
        val kind = obj.stringOrNull("op")
        val allowed = SHAPES[kind] ?: throw ContentInvalid("$path.op", "must be set, remove or insert")
        obj.keys.firstOrNull { it !in allowed }?.let {
            throw ContentInvalid("$path.$it", "unknown DataOp member")
        }
        if ("value" in allowed && "value" !in obj) throw ContentInvalid(path, "$kind requires value")
        val tokens = JsonPointer.parse(obj.stringOrNull("path"), "$path.path")
        return when (kind) {
            "set" -> JsonPointer.set(document, tokens, obj.getValue("value"), "$path.path")
            "insert" -> JsonPointer.insert(document, tokens, obj.getValue("value"), "$path.path")
            else -> JsonPointer.remove(document, tokens, "$path.path")
        }
    }
}
