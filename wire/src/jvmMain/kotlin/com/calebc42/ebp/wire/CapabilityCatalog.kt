// SPDX-License-Identifier: GPL-3.0-or-later
// The device-capability module (SPEC 20), library half. capability.invoke
// lets Emacs run finite Companion-advertised platform operations. The wire
// library owns the closed-contract parts — arg validation before any side
// effect (-32602), the cap-existence gate (1001), and the closed-result
// discipline — and delegates the actual platform work to a host-provided
// CapabilityHandler, whose typed refusal (1002/1003) it forwards verbatim.
package com.calebc42.ebp.wire

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SPEC 20.2: the host's outcome for one capability.invoke. */
sealed class CapabilityOutcome {
    /** Success: the exact closed Result object from the catalog row. */
    data class Ok(val result: JsonObject) : CapabilityOutcome()
    /** A typed refusal: 1002 (cap-permission) or 1003 (cap-failed). The reason
     * becomes error.data.reason. */
    data class Fail(
        val code: Int,
        val reason: String,
        /** Stable host permission identifier for a 1002 refusal. */
        val permission: String? = null,
    ) : CapabilityOutcome()
}

/** Negotiated facts needed to validate conditional capability bounds. */
data class CapabilityValidationContext(
    val stateTypes: Set<String> = emptySet(),
    val maxShortcutIconBytes: Long = Long.MAX_VALUE,
    val maxShortcuts: Long = Long.MAX_VALUE,
    val maxFieldBytes: Long = Long.MAX_VALUE,
    /** SPEC 14.1: whether this session granted the wake policy. */
    val offlineWakeGranted: Boolean = false,
)

/**
 * SPEC 20.2: the platform executor. The engine has already validated cap
 * existence and the closed Args schema; the handler re-checks authorization
 * at invocation time and performs the bounded operation, returning Ok with
 * the exact catalog Result or a typed Fail. It MUST NOT treat a string
 * argument as executable code.
 */
fun interface CapabilityHandler {
    fun invoke(cap: String, args: JsonObject): CapabilityOutcome
}

/**
 * SPEC 20.3: the closed Args schemas this build knows how to validate. A
 * Companion advertising a cap in `device.caps` outside this set is
 * misconfigured — an advertised row is normative, and the library validates
 * every argument before a side effect begins.
 */
object CapabilityCatalog {

    private val VOLUME_STREAMS = setOf(
        "music", "ring", "alarm", "notification", "call", "system")
    private val MEDIA_KEYS = setOf(
        "play_pause", "play", "pause", "next", "previous", "stop",
        "fast_forward", "rewind")
    private val RINGER_MODES = setOf("normal", "vibrate", "silent")
    private val DND_MODES = setOf("on", "off", "priority")
    private val INTENT_MODES = setOf("activity", "broadcast", "service")
    private val TRANSPORTS = setOf("wifi", "cellular", "ethernet", "vpn", "bluetooth")
    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

    /** Caps whose Args this build validates and can therefore accept. */
    val VALIDATED = setOf(
        "settings.open", "intent.start", "app.launch", "apps.list",
        "shortcut.pin", "shortcuts.set", "vibrate", "tts.speak",
        "volume.set", "ringer.mode", "flashlight", "media.key",
        "clipboard.read", "screen.keep_on", "brightness.set", "dnd.set",
        "state.get", "trigger.fire")

    /**
     * SPEC 20.2/20.3: validate a closed Args object against the catalog row
     * for `cap`. Throws ContentInvalid (which the engine maps to -32602)
     * before any side effect. A cap outside VALIDATED throws — the host must
     * not advertise what the library cannot validate.
     */
    fun validateArgs(
        cap: String,
        args: JsonObject,
        context: CapabilityValidationContext = CapabilityValidationContext(),
    ) {
        when (cap) {
            "settings.open" -> {
                closed(args, "panel")
                nonEmptyString(args, "panel")
            }
            "intent.start" -> validateIntent(args)
            "app.launch" -> {
                closed(args, "package")
                boundedString(args, "package", 512)
            }
            "apps.list" -> {
                closed(args, "cursor", "limit")
                if ("cursor" in args) boundedString(args, "cursor", 512)
                if ("limit" in args) intArg(args, "limit", 1, 256)
            }
            "shortcut.pin" -> validateShortcut(args, "args", context)
            "shortcuts.set" -> validateShortcuts(args, context)
            "vibrate" -> validateVibrate(args)
            "volume.set" -> {
                closed(args, "stream", "level")
                enumArg(args, "stream", VOLUME_STREAMS)
                intArg(args, "level", 0, Int.MAX_VALUE.toLong())
            }
            "tts.speak" -> {
                closed(args, "text", "pitch", "rate")
                strArg(args, "text")
                if ("pitch" in args) numArg(args, "pitch", 0.5, 2.0)
                if ("rate" in args) numArg(args, "rate", 0.5, 2.0)
            }
            "ringer.mode" -> { closed(args, "mode"); enumArg(args, "mode", RINGER_MODES) }
            "flashlight" -> { closed(args, "on"); boolArg(args, "on") }
            "media.key" -> { closed(args, "key"); enumArg(args, "key", MEDIA_KEYS) }
            "screen.keep_on" -> { closed(args, "on"); boolArg(args, "on") }
            "brightness.set" -> { closed(args, "level"); intArg(args, "level", 0, 255) }
            "dnd.set" -> { closed(args, "mode"); enumArg(args, "mode", DND_MODES) }
            "clipboard.read" -> closed(args) // no members
            "state.get" -> validateStateGet(args, context)
            "trigger.fire" -> {
                closed(args, "id")
                identifier(args, "id")
            }
            else -> throw ContentInvalid("cap", "capability not validated by this build")
        }
    }

    /** Validate the host result before it is placed on the wire. */
    fun validateResult(
        cap: String,
        result: JsonObject,
        context: CapabilityValidationContext = CapabilityValidationContext(),
    ) {
        when (cap) {
            "apps.list" -> validateAppsResult(result)
            "shortcut.pin" -> {
                closedResult(result, "updated")
                result.boolOrNull("updated")
                    ?: throw ContentInvalid("result.updated", "must be a boolean")
            }
            "shortcuts.set" -> {
                closedResult(result, "count")
                resultInteger(result, "count", 0, context.maxShortcuts)
            }
            "volume.set" -> {
                closedResult(result, "max")
                resultInteger(result, "max", 0, Int.MAX_VALUE.toLong())
            }
            "clipboard.read" -> {
                closedResult(result, "text")
                val text = result.stringOrNull("text")
                    ?: throw ContentInvalid("result.text", "must be a string")
                if (JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size >
                    context.maxFieldBytes) {
                    throw ContentInvalid("result.text", "exceeds max_field_bytes")
                }
            }
            "state.get" -> validateStateResult(result, context.stateTypes)
            "settings.open", "intent.start", "app.launch", "vibrate",
            "tts.speak", "ringer.mode", "flashlight", "media.key",
            "screen.keep_on", "brightness.set", "dnd.set", "trigger.fire" ->
                closedResult(result)
            else -> throw ContentInvalid("cap", "capability not validated by this build")
        }
    }

    private fun validateIntent(args: JsonObject) {
        closed(args, "action", "data", "package", "class_name", "mime", "extras", "mode")
        nonEmptyString(args, "action")
        val packageName = optionalBoundedString(args, "package", 512)
        val className = optionalNonEmptyString(args, "class_name")
        if (className != null && packageName == null)
            throw ContentInvalid("args.class_name", "requires package")
        optionalNonEmptyString(args, "mime")
        if ("mode" in args) enumArg(args, "mode", INTENT_MODES)
        optionalNonEmptyString(args, "data")?.let { raw ->
            val uri = try { URI(raw) } catch (_: Exception) {
                throw ContentInvalid("args.data", "must be an absolute URI")
            }
            if (!uri.isAbsolute || uri.scheme.isNullOrEmpty() || uri.rawUserInfo != null)
                throw ContentInvalid("args.data", "must be an absolute URI without user-information")
        }
        if ("extras" in args) {
            val extras = args.objOrNull("extras")
                ?: throw ContentInvalid("args.extras", "must be an object")
            for ((key, value) in extras) {
                if (key.isEmpty()) throw ContentInvalid("args.extras", "keys must be non-empty")
                validateIntentExtra(value, "args.extras.$key")
            }
        }
    }

    private fun validateIntentExtra(value: JsonElement, path: String) {
        when (value) {
            is JsonPrimitive -> {
                if (!value.isString && value.content.toBooleanStrictOrNull() == null) {
                    val number = value.content.toDoubleOrNull()
                    if (number == null || !number.isFinite())
                        throw ContentInvalid(path, "must be a finite scalar")
                }
            }
            is JsonArray -> value.forEachIndexed { index, child ->
                if (child !is JsonPrimitive)
                    throw ContentInvalid("$path[$index]", "must be a scalar")
                validateIntentExtra(child, "$path[$index]")
            }
            else -> throw ContentInvalid(path, "must be a scalar or scalar array")
        }
    }

    private fun validateShortcuts(args: JsonObject, context: CapabilityValidationContext) {
        closed(args, "shortcuts")
        val rows = args.arrOrNull("shortcuts")
            ?: throw ContentInvalid("args.shortcuts", "must be an array")
        if (rows.size.toLong() > context.maxShortcuts)
            throw ContentInvalid("args.shortcuts", "exceeds max_shortcuts")
        val ids = HashSet<String>()
        rows.forEachIndexed { index, row ->
            val shortcut = row as? JsonObject
                ?: throw ContentInvalid("args.shortcuts[$index]", "must be an object")
            validateShortcut(shortcut, "args.shortcuts[$index]", context)
            val id = shortcut.reqString("id")
            if (!ids.add(id))
                throw ContentInvalid("args.shortcuts[$index].id", "duplicate shortcut id")
        }
    }

    private fun validateShortcut(
        shortcut: JsonObject,
        path: String,
        context: CapabilityValidationContext,
    ) {
        closedAt(shortcut, path, "id", "label", "action", "icon_png", "long_label")
        identifierAt(shortcut, path, "id")
        stringAt(shortcut, path, "label")
        if ("long_label" in shortcut) stringAt(shortcut, path, "long_label")
        val action = shortcut.objOrNull("action")
            ?: throw ContentInvalid("$path.action", "must be a remote ActionDescriptor")
        SpecValidator.validateContextlessRemoteAction(
            action,
            "$path.action",
            injectedMembers = setOf("shortcut_id"),
            offlineWakeGranted = context.offlineWakeGranted,
        )
        if ("icon_png" in shortcut) {
            val encoded = shortcut.stringOrNull("icon_png")
                ?: throw ContentInvalid("$path.icon_png", "must be base64 PNG")
            val decoded = try {
                if (encoded.length % 4 != 0) throw IllegalArgumentException()
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                throw ContentInvalid("$path.icon_png", "must be standard padded base64")
            }
            if (Base64.getEncoder().encodeToString(decoded) != encoded)
                throw ContentInvalid("$path.icon_png", "must be standard padded base64")
            val png = byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            )
            if (decoded.size < png.size || !decoded.copyOfRange(0, png.size).contentEquals(png))
                throw ContentInvalid("$path.icon_png", "must decode to PNG")
            if (decoded.size.toLong() > context.maxShortcutIconBytes)
                throw ContentInvalid("$path.icon_png", "exceeds max_shortcut_icon_bytes")
        }
    }

    private fun validateStateGet(args: JsonObject, context: CapabilityValidationContext) {
        closed(args, "types", "when")
        if ("types" in args) {
            val types = args.arrOrNull("types")
                ?: throw ContentInvalid("args.types", "must be an array")
            val seen = HashSet<String>()
            types.forEachIndexed { index, value ->
                val type = value.asStringOrNull()
                    ?: throw ContentInvalid("args.types[$index]", "must be a string")
                if (!seen.add(type))
                    throw ContentInvalid("args.types[$index]", "duplicate state type")
                if (type !in context.stateTypes)
                    throw ContentInvalid("args.types[$index]", "state type not advertised")
            }
        }
        if ("when" in args) {
            val predicates = args.arrOrNull("when")
                ?: throw ContentInvalid("args.when", "must be an array")
            TriggerValidator.validateStatePredicates(
                predicates,
                context.stateTypes,
                "args.when",
            )
        }
    }

    private fun validateAppsResult(result: JsonObject) {
        closedResult(result, "apps", "next_cursor")
        val apps = result.arrOrNull("apps")
            ?: throw ContentInvalid("result.apps", "must be an array")
        var previous: String? = null
        apps.forEachIndexed { index, value ->
            val app = value as? JsonObject
                ?: throw ContentInvalid("result.apps[$index]", "must be an object")
            closedAt(app, "result.apps[$index]", "label", "package")
            boundedStringAt(app, "result.apps[$index]", "label", 1024)
            val packageName = boundedStringAt(app, "result.apps[$index]", "package", 512)
            previous?.let { prior ->
                if (compareUnicodeCodePoints(packageName, prior) <= 0)
                    throw ContentInvalid(
                        "result.apps[$index].package",
                        "packages must be strictly sorted",
                    )
            }
            previous = packageName
        }
        if ("next_cursor" in result) {
            val cursor = result.stringOrNull("next_cursor")
                ?: throw ContentInvalid("result.next_cursor", "must be a string")
            if (apps.isEmpty() || cursor != previous)
                throw ContentInvalid("result.next_cursor", "must equal the final returned package")
        }
    }

    /** SPEC 20.3 orders package strings by Unicode code point, not UTF-16 unit. */
    private fun compareUnicodeCodePoints(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftPoint = Character.codePointAt(left, leftIndex)
            val rightPoint = Character.codePointAt(right, rightIndex)
            if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
            leftIndex += Character.charCount(leftPoint)
            rightIndex += Character.charCount(rightPoint)
        }
        return when {
            leftIndex < left.length -> 1
            rightIndex < right.length -> -1
            else -> 0
        }
    }

    private fun validateStateResult(result: JsonObject, advertised: Set<String>) {
        closedResult(result, "states", "unavailable", "holds")
        val states = result.objOrNull("states")
            ?: throw ContentInvalid("result.states", "must be an object")
        for ((type, sample) in states) {
            if (type !in advertised)
                throw ContentInvalid("result.states.$type", "state type not advertised")
            validateStateSample(type, sample as? JsonObject
                ?: throw ContentInvalid("result.states.$type", "must be an object"))
        }
        if ("unavailable" in result) {
            val unavailable = result.objOrNull("unavailable")
                ?: throw ContentInvalid("result.unavailable", "must be an object")
            for ((type, reason) in unavailable) {
                if (type !in advertised || reason.asStringOrNull()?.let(IDENTIFIER::matches) != true)
                    throw ContentInvalid("result.unavailable.$type", "must map an advertised type to an identifier")
            }
        }
        if ("holds" in result && result.boolOrNull("holds") == null)
            throw ContentInvalid("result.holds", "must be a boolean")
    }

    private fun validateStateSample(type: String, sample: JsonObject) {
        when (type) {
            "power" -> { closedAt(sample, "sample", "state", "plug")
                enumAt(sample, "sample", "state", setOf("connected", "disconnected"))
                if ("plug" in sample) enumAt(sample, "sample", "plug", setOf("ac", "usb", "wireless")) }
            "battery.level" -> { closedAt(sample, "sample", "level")
                integerAt(sample, "sample", "level", 0, 100) }
            "screen" -> { closedAt(sample, "sample", "state")
                enumAt(sample, "sample", "state", setOf("on", "off", "unlocked")) }
            "airplane" -> { closedAt(sample, "sample", "state")
                enumAt(sample, "sample", "state", setOf("on", "off")) }
            "network" -> {
                closedAt(sample, "sample", "connected", "transports")
                sample.boolOrNull("connected")
                    ?: throw ContentInvalid("sample.connected", "must be a boolean")
                val transports = sample.arrOrNull("transports")
                    ?: throw ContentInvalid("sample.transports", "must be an array")
                val values = transports.mapIndexed { index, value ->
                    value.asStringOrNull()?.takeIf { it in TRANSPORTS }
                        ?: throw ContentInvalid("sample.transports[$index]", "invalid transport")
                }
                if (values.distinct().size != values.size)
                    throw ContentInvalid("sample.transports", "must be distinct")
            }
            "headset" -> { closedAt(sample, "sample", "state", "name")
                enumAt(sample, "sample", "state", setOf("plugged", "unplugged"))
                if ("name" in sample) stringAt(sample, "sample", "name") }
            "wifi.enabled", "bluetooth.enabled" -> { closedAt(sample, "sample", "enabled")
                sample.boolOrNull("enabled")
                    ?: throw ContentInvalid("sample.enabled", "must be a boolean") }
            "calendar.event" -> { closedAt(sample, "sample", "ongoing")
                sample.boolOrNull("ongoing")
                    ?: throw ContentInvalid("sample.ongoing", "must be a boolean") }
            "call.state" -> { closedAt(sample, "sample", "state", "number")
                enumAt(sample, "sample", "state", setOf("ringing", "offhook", "idle"))
                if ("number" in sample) stringAt(sample, "sample", "number") }
            else -> throw ContentInvalid("sample", "unknown state type")
        }
    }

    // SPEC 20.3: `vibrate` takes exactly one of {ms} or {pattern}.
    private fun validateVibrate(args: JsonObject) {
        val hasMs = "ms" in args
        val hasPattern = "pattern" in args
        if (hasMs == hasPattern)
            throw ContentInvalid("args", "exactly one of ms or pattern")
        if (hasMs) {
            closed(args, "ms")
            intArg(args, "ms", 1, 60_000)
        } else {
            closed(args, "pattern")
            val p = args.arrOrNull("pattern")
                ?: throw ContentInvalid("args.pattern", "must be an array")
            if (p.size < 1 || p.size > 64)
                throw ContentInvalid("args.pattern", "1..64 entries")
            var total = 0L
            for (i in p.indices)
                total += integer(p[i], "args.pattern[$i]", 0, 60_000)
            if (total > 60_000)
                throw ContentInvalid("args.pattern", "total exceeds 60000 ms")
        }
    }

    // ------------------------------------------------------------ primitives

    private fun closed(o: JsonObject, vararg allowed: String) {
        for (k in o.keys) if (k !in allowed)
            throw ContentInvalid("args.$k", "unknown member")
    }

    private fun strArg(o: JsonObject, key: String): String =
        o.stringOrNull(key) ?: throw ContentInvalid("args.$key", "must be a string")

    private fun nonEmptyString(o: JsonObject, key: String): String =
        nonEmptyStringAt(o, "args", key)

    private fun nonEmptyStringAt(o: JsonObject, path: String, key: String): String {
        val value = stringAt(o, path, key)
        if (value.isEmpty()) throw ContentInvalid("$path.$key", "must be non-empty")
        return value
    }

    private fun stringAt(o: JsonObject, path: String, key: String): String =
        o.stringOrNull(key) ?: throw ContentInvalid("$path.$key", "must be a string")

    private fun optionalNonEmptyString(o: JsonObject, key: String): String? =
        if (key in o) nonEmptyString(o, key) else null

    private fun boundedString(o: JsonObject, key: String, maxBytes: Int): String =
        boundedStringAt(o, "args", key, maxBytes)

    private fun optionalBoundedString(o: JsonObject, key: String, maxBytes: Int): String? =
        if (key in o) boundedString(o, key, maxBytes) else null

    private fun boundedStringAt(o: JsonObject, path: String, key: String, maxBytes: Int): String {
        val value = nonEmptyStringAt(o, path, key)
        if (value.toByteArray(Charsets.UTF_8).size > maxBytes)
            throw ContentInvalid("$path.$key", "exceeds $maxBytes encoded bytes")
        return value
    }

    private fun identifier(o: JsonObject, key: String): String = identifierAt(o, "args", key)

    private fun identifierAt(o: JsonObject, path: String, key: String): String {
        val value = o.stringOrNull(key)
            ?: throw ContentInvalid("$path.$key", "must be an identifier")
        if (!IDENTIFIER.matches(value))
            throw ContentInvalid("$path.$key", "must be an identifier")
        return value
    }

    private fun boolArg(o: JsonObject, key: String): Boolean =
        o.boolOrNull(key) ?: throw ContentInvalid("args.$key", "must be a boolean")

    private fun enumArg(o: JsonObject, key: String, allowed: Set<String>): String {
        val v = o.stringOrNull(key) ?: throw ContentInvalid("args.$key", "must be a string")
        if (v !in allowed) throw ContentInvalid("args.$key", "not a permitted value")
        return v
    }

    private fun intArg(o: JsonObject, key: String, min: Long, max: Long): Long =
        integer(o[key], "args.$key", min, max)

    /** An integral JSON number in [min, max]; rejects fractions and non-finite.
     * The value is normalized at accept time, so an older peer's `{"ms": 100.0}`
     * is the same 100 ms it was under org.json (whose getLong truncated a
     * Double) — pinned by PreSwapNumberTest. */
    private fun integer(v: JsonElement?, path: String, min: Long, max: Long): Long {
        val n = integralLongOrNull(v) ?: throw ContentInvalid(path, "must be an integer")
        if (n < min || n > max) throw ContentInvalid(path, "out of range $min..$max")
        return n
    }

    private fun numArg(o: JsonObject, key: String, min: Double, max: Double): Double {
        // A binary64 arg (tts pitch/rate): any JSON number, integral or not.
        // The reader's isString guard is what keeps the string "1.5" out —
        // org.json's `as? Number` cast refused it the same way. A non-finite
        // value cannot reach here from the wire (EbpJson refuses it), but a
        // host-built tree can carry one, and it stays an out-of-range refusal
        // rather than a "must be a number" one, exactly as before.
        val d = o[key]?.asDoubleOrNull()
            ?: throw ContentInvalid("args.$key", "must be a number")
        if (d.isNaN() || d.isInfinite() || d < min || d > max)
            throw ContentInvalid("args.$key", "out of range $min..$max")
        return d
    }

    private fun closedAt(o: JsonObject, path: String, vararg allowed: String) {
        for (key in o.keys) if (key !in allowed)
            throw ContentInvalid("$path.$key", "unknown member")
    }

    private fun closedResult(o: JsonObject, vararg allowed: String) =
        closedAt(o, "result", *allowed)

    private fun enumAt(o: JsonObject, path: String, key: String, allowed: Set<String>): String {
        val value = o.stringOrNull(key)
            ?: throw ContentInvalid("$path.$key", "must be a string")
        if (value !in allowed) throw ContentInvalid("$path.$key", "not a permitted value")
        return value
    }

    private fun integerAt(
        o: JsonObject,
        path: String,
        key: String,
        min: Long,
        max: Long,
    ): Long {
        val value = integralLongOrNull(o[key])
            ?: throw ContentInvalid("$path.$key", "must be an integer")
        if (value < min || value > max)
            throw ContentInvalid("$path.$key", "out of range $min..$max")
        return value
    }

    private fun resultInteger(o: JsonObject, key: String, min: Long, max: Long): Long =
        integerAt(o, "result", key, min, max)
}
