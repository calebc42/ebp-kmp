// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * SPEC 13.8.2: an RFC 6901 JSON Pointer within the fixed limits — absolute
 * outside a template item scope, absolute or relative inside one (13.8.5).
 *
 * Evaluation is one container step per reference token over the document
 * the caller holds: no I/O, no interpretation of a token or of the value it
 * reaches, cost bounded by the token count (SPEC 23.2, 23.5). Every failure
 * is a [ContentInvalid] at the path the caller names, so the request that
 * carried the pointer is rejected whole (SPEC 13.2).
 */
object JsonPointer {
    private val INDEX = Regex("^(0|[1-9][0-9]*)$")
    private val BAD_ESCAPE = Regex("~(?![01])")

    /** A parsed pointer: resolved from the document when [absolute], else from the item scope's element. */
    class Parsed(val absolute: Boolean, val tokens: List<String>)

    /** The reference tokens of an absolute [text], unescaped; the empty pointer is the empty list. */
    fun parse(text: String?, path: String): List<String> = parseScoped(text, path, inScope = false).tokens

    /**
     * SPEC 13.8.2/13.8.5: [text] parsed within the fixed limits. A pointer
     * without a leading `/` — the empty pointer included — is relative, and
     * admitted only [inScope]; the empty pointer outside a scope is the
     * absolute root.
     */
    fun parseScoped(text: String?, path: String, inScope: Boolean): Parsed {
        if (text == null) throw ContentInvalid(path, "pointer must be a string")
        if (text.toByteArray(Charsets.UTF_8).size > WireLimits.MAX_POINTER_OCTETS)
            throw ContentInvalid(path, "pointer exceeds max_pointer_bytes")
        if (text.isEmpty()) return Parsed(absolute = !inScope, tokens = emptyList())
        val absolute: Boolean
        val body: String
        when {
            text.startsWith("/") -> { absolute = true; body = text.substring(1) }
            inScope -> { absolute = false; body = text }
            else -> throw ContentInvalid(path, "pointer must be absolute outside a template")
        }
        val tokens = body.split("/")
        if (tokens.size > WireLimits.MAX_POINTER_SEGMENTS)
            throw ContentInvalid(path, "pointer exceeds max_pointer_segments")
        return Parsed(absolute, tokens.map { token ->
            if (BAD_ESCAPE.containsMatchIn(token))
                throw ContentInvalid(path, "invalid ~ escape in pointer")
            token.replace("~1", "/").replace("~0", "~")
        })
    }

    /**
     * The value [tokens] reach in [document], or Kotlin null when they reach
     * nothing. A JSON null that is reached comes back as [kotlinx.serialization.json.JsonNull],
     * so absence and null stay distinct.
     */
    fun resolve(document: JsonElement, tokens: List<String>, path: String): JsonElement? {
        var current: JsonElement = document
        for (token in tokens) {
            current = when (current) {
                is JsonArray -> {
                    val index = arrayIndex(token, path) ?: return null
                    if (index >= current.size) return null
                    current[index]
                }
                is JsonObject -> current[token] ?: return null
                else -> return null
            }
        }
        return current
    }

    /** SPEC 13.8.4 `set`: a copy of [document] carrying [value] at [tokens]. */
    fun set(document: JsonObject, tokens: List<String>, value: JsonElement, path: String): JsonObject {
        if (tokens.isEmpty())
            return value as? JsonObject
                ?: throw ContentInvalid(path, "a whole-document set must carry an object")
        return setIn(document, tokens, 0, value, path) as JsonObject
    }

    private fun setIn(
        container: JsonElement,
        tokens: List<String>,
        depth: Int,
        value: JsonElement,
        path: String,
    ): JsonElement {
        val token = tokens[depth]
        val last = depth == tokens.lastIndex
        return when (container) {
            is JsonObject -> {
                val next = if (last) value else setIn(
                    container[token] ?: throw ContentInvalid(path, "parent container does not exist"),
                    tokens, depth + 1, value, path,
                )
                JsonObject(container + (token to next))
            }
            is JsonArray -> {
                val index = arrayIndex(token, path)
                    ?: throw ContentInvalid(path, "array index exceeds the length")
                when {
                    index < container.size -> {
                        val next = if (last) value
                        else setIn(container[index], tokens, depth + 1, value, path)
                        JsonArray(container.mapIndexed { i, e -> if (i == index) next else e })
                    }
                    index == container.size && last -> JsonArray(container + value)
                    last -> throw ContentInvalid(path, "array index exceeds the length")
                    else -> throw ContentInvalid(path, "parent container does not exist")
                }
            }
            else -> throw ContentInvalid(path, "parent container does not exist")
        }
    }

    /** SPEC 13.8.4 `remove`: a copy of [document] without the value at [tokens], which must exist. */
    fun remove(document: JsonObject, tokens: List<String>, path: String): JsonObject {
        if (tokens.isEmpty()) throw ContentInvalid(path, "the whole document cannot be removed")
        return removeIn(document, tokens, 0, path) as JsonObject
    }

    private fun removeIn(container: JsonElement, tokens: List<String>, depth: Int, path: String): JsonElement {
        val token = tokens[depth]
        val last = depth == tokens.lastIndex
        return when (container) {
            is JsonObject -> {
                val child = container[token]
                    ?: throw ContentInvalid(path, "nothing to remove at the pointer")
                if (last) JsonObject(container - token)
                else JsonObject(container + (token to removeIn(child, tokens, depth + 1, path)))
            }
            is JsonArray -> {
                val index = arrayIndex(token, path)
                if (index == null || index >= container.size)
                    throw ContentInvalid(path, "nothing to remove at the pointer")
                if (last) JsonArray(container.filterIndexed { i, _ -> i != index })
                else JsonArray(container.mapIndexed { i, e ->
                    if (i == index) removeIn(e, tokens, depth + 1, path) else e
                })
            }
            else -> throw ContentInvalid(path, "nothing to remove at the pointer")
        }
    }

    /** SPEC 13.8.2: a decimal without leading zeros; `-` is not admitted against an array. */
    private fun arrayIndex(token: String, path: String): Int? {
        if (!INDEX.matches(token))
            throw ContentInvalid(path, "array index must be a decimal without leading zeros")
        return token.toIntOrNull()
    }

    /** Containers nested in [value], for the max_data_depth bound. */
    fun depth(value: JsonElement): Int = when (value) {
        is JsonObject -> 1 + (value.values.maxOfOrNull { depth(it) } ?: 0)
        is JsonArray -> 1 + (value.maxOfOrNull { depth(it) } ?: 0)
        else -> 0
    }
}
