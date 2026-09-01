// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single SPEC 21.7 state-predicate evaluator used by trigger gates,
 * `state.edge`, and `state.get.holds`.
 *
 * [predicateProvider] is the bounded host seam for predicates that need more
 * detail than their public sample object carries. In this version that is
 * `calendar.event`: its public state sample is only `{ongoing}`, while a
 * predicate may additionally select a calendar or title substring. Returning
 * null delegates to the ordinary sample evaluator; a filtered calendar
 * predicate without a host answer fails closed.
 */
class StatePredicateEvaluator(
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val stateProvider: (String) -> JsonObject?,
    private val predicateProvider: (JsonObject) -> Boolean? = { null },
) {
    private val week = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )
    private val dayKey = mapOf(
        DayOfWeek.MONDAY to "mon",
        DayOfWeek.TUESDAY to "tue",
        DayOfWeek.WEDNESDAY to "wed",
        DayOfWeek.THURSDAY to "thu",
        DayOfWeek.FRIDAY to "fri",
        DayOfWeek.SATURDAY to "sat",
        DayOfWeek.SUNDAY to "sun",
    )

    /** Return true only when every normalized predicate can be evaluated and holds. */
    fun allHold(predicates: JsonArray): Boolean = predicates.all { element ->
        val predicate = element as? JsonObject ?: return false
        holds(predicate)
    }

    /** Evaluate one normalized predicate; unavailable data fails closed. */
    fun holds(predicate: JsonObject): Boolean {
        predicateProvider(predicate)?.let { return it }
        val type = predicate.reqString("type")
        if (type == "time.window") return timeWindowHolds(predicate)
        val sample = stateProvider(type) ?: return false
        return when (type) {
            "power" -> sample.stringOr("state") == predicate.stringOr("state", "connected")
            "battery.level" ->
                if ("above" in predicate) {
                    sample.longOr("level", -1) > predicate.reqLong("above")
                } else {
                    sample.longOr("level", -1) < predicate.reqLong("below")
                }
            "screen" -> {
                val wanted = predicate.stringOr("state", "on")
                val current = sample.stringOr("state")
                if (wanted == "on") current == "on" || current == "unlocked"
                else current == wanted
            }
            "airplane" -> sample.stringOr("state") == predicate.stringOr("state", "on")
            "network" -> sample.boolOr("connected") &&
                ("transport" !in predicate || transportsHave(
                    sample,
                    predicate.reqString("transport"),
                ))
            "headset" -> sample.stringOr("state") == predicate.stringOr("state", "plugged")
            "wifi.enabled" ->
                sample.boolOr("enabled") == predicate.boolOr("enabled", true)
            "bluetooth.enabled" ->
                "enabled" in sample &&
                    sample.boolOr("enabled") == predicate.boolOr("enabled", true)
            "calendar.event" ->
                "calendar" !in predicate && "title_contains" !in predicate &&
                    sample.boolOr("ongoing")
            "call.state" ->
                sample.stringOr("state") == predicate.stringOr("state", "offhook")
            else -> false
        }
    }

    private fun transportsHave(sample: JsonObject, transport: String): Boolean {
        val transports = sample.arrOrNull("transports") ?: return false
        return transports.any { it == JsonPrimitive(transport) }
    }

    // SPEC 21.7: local civil time in a half-open window. For a wrapped
    // interval, the after-portion's civil day owns the whole interval.
    private fun timeWindowHolds(predicate: JsonObject): Boolean {
        val zoned = Instant.ofEpochMilli(now()).atZone(zone())
        val current = zoned.toLocalTime()
        val after = predicate.stringOr("after")
            .takeIf(String::isNotEmpty)
            ?.let(LocalTime::parse)
        val before = predicate.stringOr("before")
            .takeIf(String::isNotEmpty)
            ?.let(LocalTime::parse)
        val days = predicate.arrOrNull("days")?.map { element ->
            element.asStringOrNull() ?: return false
        }?.toSet() ?: week.map(dayKey::getValue).toSet()
        val wraps = after != null && before != null && after > before
        val inTime = when {
            after == null && before == null -> true
            after == null -> current < before
            before == null -> current >= after
            !wraps -> current >= after && current < before
            else -> current >= after || current < before
        }
        if (!inTime) return false
        val day = if (wraps && current < before) {
            zoned.minusDays(1).dayOfWeek
        } else {
            zoned.dayOfWeek
        }
        return dayKey.getValue(day) in days
    }
}
