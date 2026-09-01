// SPDX-License-Identifier: GPL-3.0-or-later
// The device-trigger firing runtime (SPEC 21.2/21.3/21.5/21.6). Device
// sources feed observations in; this decides which are *admitted* occurrences
// and drives the SPEC 21.2 order for each: evaluate the state gate and
// throttle, then (on success) commit throttle state and emit trigger.fired
// through the durable event pipeline. Level types are silently baselined so a
// sticky report never fires — only a later crossing/transition/edge does.
// Pure logic: the clock, current state, and event emission are injected, so
// the semantics are testable without any Android source (those arrive in a
// later atom and simply call onSample/onExternal/armBaselines).
package com.calebc42.ebp.wire

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TriggerRuntime(
    private val store: TriggerStore,
    /** Milliseconds; rollback-resistant wall time (queue.effectiveNow). */
    private val now: () -> Long,
    /** Civil-time zone for time.window predicates — a supplier read fresh at
     * each evaluation, so a device timezone change takes effect immediately
     * (the systemDefault() default) rather than being frozen at construction. */
    private val zone: () -> ZoneId,
    /** Current sample object for a state type, or null if unavailable. */
    private val stateProvider: (String) -> JsonObject?,
    /**
     * SPEC 21.2: perform the durable commit for an admitted occurrence and,
     * only if it succeeds, invoke `commit` (which consumes throttle + runs
     * on_fire) BEFORE making the remote trigger.fired eligible. For queue/wake
     * this means committing the event record first and skipping `commit`
     * entirely on QueueFull/StorageFailed; for drop it means committing
     * throttle+on_fire even with no live session, delivering live only in READY.
     */
    private val emit: (TriggerStore.Registration, JsonObject, commit: () -> Unit) -> Unit,
    /** SPEC 21.4: execute one substituted on_fire entry ({cap,args?}|{notify}).
     * Called in authored order at admission; a throw is isolated per entry. */
    private val onFire: (JsonObject) -> Unit = {},
    /** SPEC 21.2 step 3: durably commit the throttle floor / one-shot / boot
     * records BEFORE on_fire runs. A no-op with the default in-memory store. */
    private val persistRecords: () -> Unit = {},
    /** SPEC 21.5: the current device boot generation (Settings.Global.BOOT_COUNT
     * on Android), or null if unknown. A `boot` registration is admitted at most
     * once per generation; the JVM default leaves boot triggers ungated. */
    private val bootGeneration: () -> String? = { null },
    /** Host evaluation for predicates whose public sample omits filter detail. */
    private val predicateProvider: (JsonObject) -> Boolean? = { null },
) {

    private val predicates = StatePredicateEvaluator(
        now = now,
        zone = zone,
        stateProvider = stateProvider,
        predicateProvider = predicateProvider,
    )

    // Enum state types whose primary field is a single value; a present filter
    // fires on transition INTO that value, an absent filter on any change.
    private val ENUM_FIELD = mapOf(
        "screen" to "state", "power" to "state", "headset" to "state",
        "airplane" to "state", "call.state" to "state",
        "wifi.enabled" to "enabled", "bluetooth.enabled" to "enabled")

    // ------------------------------------------------------- arming/baseline

    /**
     * SPEC 21.5/21.6: establish the silent baseline for every level and edge
     * registration from the current state, so arming or restoring never fires;
     * a later observed transition is required for the first occurrence. SPEC
     * 21.1: a carried-forward (unchanged) registration already holds its
     * baseline — this only fills a fresh one, never re-baselines it.
     */
    fun armBaselines(identity: String) {
        // SPEC 21.1: the every_s anchor and boot generation recorded below are
        // durable records (unlike the silent level/edge baselines, which are
        // re-established from live state) — a fresh recording must be persisted
        // so it survives restart.
        var recorded = false
        for (reg in store.registrations(identity)) {
            val type = reg.entry.reqString("type")
            when {
                type == "state.edge" ->
                    if (!reg.baselines.containsKey("edge"))
                        reg.baselines["edge"] = edgeHolds(reg)
                type == "battery.level" ->
                    if (!reg.baselines.containsKey("side")) stateProvider(type)?.let {
                        reg.baselines["side"] = batterySide(reg.entry, it) }
                type in ENUM_FIELD -> {
                    val field = ENUM_FIELD.getValue(type)
                    val params = reg.entry.reqObj("params")
                    val filtered = field in params ||
                        (type == "call.state" && "number" in params)
                    if (filtered) {
                        if (!reg.baselines.containsKey("side")) stateProvider(type)?.let {
                            reg.baselines["side"] = levelMatch(reg.entry, field, it) }
                    } else if (!reg.baselines.containsKey("value")) stateProvider(type)?.let {
                        // C3: the baseline holds a JsonElement? — absent reads as
                        // Kotlin null and JSON null as JsonNull, the same two-way
                        // split org.json's opt() gave (null vs JSONObject.NULL),
                        // so the containsKey-vs-value distinction below is intact.
                        reg.baselines["value"] = it[field] }
                }
                // SPEC 21.5: a repeating time.every_s registration anchors its
                // cadence at the acceptance that first introduced it. A fresh
                // Registration has no anchor; a carried-forward one keeps the
                // anchor it was accepted with, so arming never resets the phase.
                type == "time" && "every_s" in reg.entry.reqObj("params") ->
                    if (reg.scheduleAnchorMs == null) { reg.scheduleAnchorMs = now(); recorded = true }
                // SPEC 21.5: installing a new/changed boot registration silently
                // records the CURRENT generation and arms for the NEXT boot — it
                // must not fire for the boot it was installed during. A carried-
                // forward or persisted receipt keeps its generation, so a process
                // restart in the same boot creates no occurrence. Recorded only
                // when the platform generation is known; null stays ungated.
                type == "boot" ->
                    if (reg.bootGeneration == null) bootGeneration()?.let {
                        reg.bootGeneration = it; recorded = true }
            }
        }
        // Best-effort (SPEC 21.5 re-establishes silently on the next arm if this
        // is lost): never fail a triggers.set because a baseline persist hiccups.
        if (recorded) runCatching { persistRecords() }
    }

    // ------------------------------------------------------ observation feed

    /**
     * SPEC 21.5: a level-type observation. Level registrations of this type
     * evaluate a crossing/transition against their baseline; every state.edge
     * re-evaluates because any tracked level may have flipped its conjunction.
     */
    fun onSample(identity: String, type: String, sample: JsonObject) {
        for (reg in store.registrations(identity)) {
            if (reg.entry.reqString("type") != type) continue
            when {
                type == "battery.level" -> crossing(reg, batterySide(reg.entry, sample), sample)
                type in ENUM_FIELD -> {
                    val field = ENUM_FIELD.getValue(type)
                    val params = reg.entry.reqObj("params")
                    val data = if (type == "call.state") {
                        projectCall(params, sample)
                    } else {
                        sample
                    }
                    if (field in params ||
                        (type == "call.state" && "number" in params)) {
                        crossing(reg, levelMatch(reg.entry, field, sample), data)
                    } else {
                        valueChange(reg, sample[field], data)
                    }
                }
            }
        }
        reEvaluateEdges(identity)
    }

    /**
     * SPEC 21.5: an external occurrence (package, sms.received, boot, time,
     * timezone.changed, manual). These have no sticky baseline — an admitted
     * occurrence is the event itself, subject to the gate and throttle.
     */
    fun onExternal(identity: String, type: String, data: JsonObject) {
        for (reg in store.registrations(identity)) {
            if (reg.entry.reqString("type") != type) continue
            projectExternal(reg.entry.reqObj("params"), type, data)?.let {
                tryAdmit(reg, it)
            }
        }
    }

    /**
     * SPEC 21.5 (manual): fire exactly the named `manual` registration (via the
     * builtin or trigger.fire), NOT every manual trigger. Unknown or non-manual
     * ids are a no-op.
     */
    fun fireManual(identity: String, triggerId: String, data: JsonObject) {
        val reg = store.registration(identity, triggerId) ?: return
        if (reg.entry.reqString("type") == "manual") tryAdmit(reg, data)
    }

    /**
     * SPEC 21.5 (time): admit exactly the named time.* registration whose host
     * alarm just elapsed — NOT every time trigger, because each has its own due
     * time (unlike a fan-out `boot`). The one-shot/gate/throttle eligibility in
     * tryAdmit still applies. Unknown or non-time ids are a no-op.
     */
    fun fireScheduled(identity: String, triggerId: String, data: JsonObject) {
        val reg = store.registration(identity, triggerId) ?: return
        if (reg.entry.reqString("type") != "time") return
        tryAdmit(reg, data)
        // SPEC 21.5: a repeating every_s advances its schedule cursor to the
        // current boundary on EVERY elapsed alarm, even when the gate/throttle/
        // queue blocked admission — otherwise the host recomputes the SAME
        // past-due boundary and re-arms it, and the alarm spins (an RTC_WAKEUP
        // storm) until the blocking condition clears. On a successful admit the
        // commit already floored lastFireFloorMs to now, so this is a no-op.
        val params = reg.entry.objOrNull("params")
        if (params != null && "every_s" in params &&
            (reg.lastFireFloorMs ?: Long.MIN_VALUE) < now()) {
            reg.lastFireFloorMs = now()
            runCatching { persistRecords() }
        }
    }

    // -------------------------------------------------------- crossing logic

    // SPEC 21.5: a filtered level fires only when entering the configured side.
    private fun crossing(
        reg: TriggerStore.Registration,
        side: Boolean,
        data: JsonObject?,
    ) {
        val prev = reg.baselines["side"] as Boolean?
        if (prev == null) { reg.baselines["side"] = side; return } // silent baseline
        if (!prev && side && data != null) tryAdmit(reg, data)
        reg.baselines["side"] = side
    }

    // SPEC 21.5: an unfiltered level fires on any change of its primary value.
    // C3: `value` is a JsonElement? (absent = Kotlin null, JSON null =
    // JsonNull) and the comparison stays the host language's `!=` — a
    // JsonPrimitive is a data class over (isString, content), so the type
    // strictness of the old boxed-value compare survives: the string "on" is
    // still not the number 1, and a respelled number is still a change.
    private fun valueChange(
        reg: TriggerStore.Registration,
        value: JsonElement?,
        data: JsonObject?,
    ) {
        val prev = reg.baselines["value"]
        if (!reg.baselines.containsKey("value")) { reg.baselines["value"] = value; return }
        if (prev != value && data != null) tryAdmit(reg, data)
        reg.baselines["value"] = value
    }

    private fun batterySide(entry: JsonObject, sample: JsonObject): Boolean {
        val p = entry.reqObj("params")
        // C3: the level is read as an INTEGER member (TriggerValidator stores
        // above/below as integers and the device source reports whole
        // percents). A sample spelling it `19.0` no longer coerces — it reads
        // as the -1 "unavailable" sentinel, exactly as a missing member does.
        val level = sample.longOr("level", -1)
        return if ("above" in p) level > p.reqLong("above") else level < p.reqLong("below")
    }

    /** A call number is part of its filtered side, not merely output data. */
    private fun levelMatch(entry: JsonObject, field: String, sample: JsonObject): Boolean {
        val params = entry.reqObj("params")
        if (field in params && sample[field] != params[field]) return false
        return params.stringOrNull("number")?.let { it == sample.stringOrNull("number") }
            ?: true
    }

    /**
     * Match an external occurrence against one registration and return its
     * exact public fire-data projection. Device adapters may carry internal
     * selection fields (for example `calendar`) into this boundary; those
     * fields are consumed here and never escape onto the wire.
     */
    private fun projectExternal(
        params: JsonObject,
        type: String,
        data: JsonObject,
    ): JsonObject? = when (type) {
        "boot" -> JsonObject(emptyMap())
        "time" -> data.stringOrNull("precision")
            ?.takeIf { it == "exact" || it == "inexact" }
            ?.let { precision -> buildJsonObject { put("precision", precision) } }
        "timezone.changed" -> data.stringOrNull("tz")
            ?.let { timezone -> buildJsonObject { put("tz", timezone) } }
        "package" -> {
            val event = data.stringOrNull("event")
                ?.takeIf { it == "added" || it == "removed" }
                ?: return null
            val packageName = data.stringOrNull("package") ?: return null
            if (params.stringOrNull("event")?.let { it != event } == true ||
                params.stringOrNull("package")?.let { it != packageName } == true) {
                null
            } else {
                buildJsonObject {
                    put("event", event)
                    put("package", packageName)
                }
            }
        }
        "network" -> {
            val event = data.stringOrNull("event")
                ?.takeIf { it == "available" || it == "lost" }
                ?: return null
            val transport = data.stringOrNull("transport")
            if (params.stringOrNull("event")?.let { it != event } == true ||
                params.stringOrNull("transport")?.let { it != transport } == true) {
                null
            } else {
                buildJsonObject {
                    put("event", event)
                    transport?.let { put("transport", it) }
                }
            }
        }
        "calendar.event" -> {
            val event = data.stringOrNull("event")
                ?.takeIf { it == "started" || it == "ended" }
                ?: return null
            val calendar = data.stringOrNull("calendar")
            val title = data.stringOrNull("title")
            if (params.stringOrNull("event")?.let { it != event } == true ||
                params.stringOrNull("calendar")?.let { it != calendar } == true ||
                params.stringOrNull("title_contains")?.let { needle ->
                    title?.contains(needle) != true
                } == true) {
                null
            } else {
                buildJsonObject {
                    put("event", event)
                    title?.let { put("title", it) }
                    data["begin_ms"]?.let { put("begin_ms", it) }
                    data["end_ms"]?.let { put("end_ms", it) }
                }
            }
        }
        "sms.received" -> {
            val sender = data.stringOrNull("from") ?: return null
            val body = data.stringOrNull("body")
            if (params.stringOrNull("from")?.let { it != sender } == true ||
                params.stringOrNull("contains")?.let { needle ->
                    body?.contains(needle) != true
                } == true) {
                null
            } else {
                buildJsonObject {
                    put("from", sender)
                    if (params.boolOr("include_body")) body?.let { put("body", it) }
                }
            }
        }
        "manual" -> data.stringOrNull("source")
            ?.takeIf { it == "tap" || it == "emacs" }
            ?.let { source -> buildJsonObject { put("source", source) } }
        else -> data
    }

    private fun projectCall(params: JsonObject, sample: JsonObject): JsonObject? {
        val state = sample.stringOrNull("state")
            ?.takeIf { it == "ringing" || it == "offhook" || it == "idle" }
            ?: return null
        val number = sample.stringOrNull("number")
        if (params.stringOrNull("number")?.let { it != number } == true) return null
        return buildJsonObject {
            put("state", state)
            if (params.boolOr("include_number")) number?.let { put("number", it) }
        }
    }

    // ------------------------------------------------------------- edges

    private fun reEvaluateEdges(identity: String) {
        for (reg in store.registrations(identity)) {
            if (reg.entry.reqString("type") != "state.edge") continue
            val holds = edgeHolds(reg)
            val prev = reg.baselines["edge"] as Boolean?
            if (prev == null) { reg.baselines["edge"] = holds; continue } // silent
            val edge = reg.entry.reqObj("params").reqString("edge")
            val rise = !prev && holds && (edge == "rise" || edge == "both")
            val fall = prev && !holds && (edge == "fall" || edge == "both")
            reg.baselines["edge"] = holds
            if (rise || fall)
                tryAdmit(reg, buildJsonObject {
                    put("holds", holds)
                    put("edge", if (holds) "rise" else "fall")
                })
        }
    }

    // The tracked conjunction (SPEC 21.6 params.when), distinct from the
    // row-level `when` gate evaluated at admission.
    private fun edgeHolds(reg: TriggerStore.Registration): Boolean =
        allHold(reg.entry.reqObj("params").reqArr("when"))

    // ----------------------------------------------- admission (SPEC 21.2)

    private fun tryAdmit(reg: TriggerStore.Registration, data: JsonObject) {
        // SPEC 21.2 step 1: state gate + throttle eligibility. A failed check
        // consumes NOTHING — no throttle, no local response, no event.
        if (!allHold(reg.entry.reqArr("when"))) return
        val throttle = reg.entry.longOr("throttle_s", 0)
        if (throttle > 0) {
            val floor = reg.throttleFloorMs
            if (floor != null && now() - floor < throttle * 1000) return
        }
        // SPEC 21.5 eligibility (a skip consumes nothing): a completed one-shot
        // time.at_ms never fires again, and a boot registration fires at most
        // once per known device boot generation. An unknown generation (null)
        // leaves boot ungated — the receiver already feeds it once per boot.
        if (reg.oneShotCompleted) return
        if (reg.entry.reqString("type") == "boot") {
            val gen = bootGeneration()
            if (gen != null && gen == reg.bootGeneration) return
        }
        // SPEC 21.2 steps 2-5, ordered by emit: the durable commit happens
        // FIRST; only if it succeeds does emit invoke this `commit` closure,
        // which consumes the throttle floor (step 3) and runs on_fire in
        // authored order (step 4). Remote FIFO eligibility / live delivery
        // (step 5) follows inside emit. A failed durable transaction
        // (QueueFull/StorageFailed) never calls commit — so no throttle is
        // consumed and no local response runs (SPEC 21.2).
        emit(reg, data) {
            // Step 3: consume the throttle floor and advance the time/boot
            // records (SPEC 21.5), then durably persist them BEFORE any local
            // response: a one-shot time.at_ms is done for good, a repeating
            // time.every_s floors its last fire so missed intervals coalesce and
            // the cadence resumes, and a boot occurrence records its generation.
            val priorThrottle = reg.throttleFloorMs
            val priorOneShot = reg.oneShotCompleted
            val priorLastFire = reg.lastFireFloorMs
            val priorBoot = reg.bootGeneration
            reg.throttleFloorMs = now()
            val type = reg.entry.reqString("type")
            val params = reg.entry.objOrNull("params")
            when {
                type == "time" && params != null && "at_ms" in params ->
                    reg.oneShotCompleted = true
                type == "time" && params != null && "every_s" in params ->
                    reg.lastFireFloorMs = now()
                type == "boot" -> reg.bootGeneration = bootGeneration()
            }
            // SPEC 21.2: if the record write (transaction B) fails, RESTORE the
            // in-memory records and rethrow so `emit` rolls back the event
            // (transaction A) — a failed transaction consumes nothing.
            try {
                persistRecords()
            } catch (e: Exception) {
                reg.throttleFloorMs = priorThrottle
                reg.oneShotCompleted = priorOneShot
                reg.lastFireFloorMs = priorLastFire
                reg.bootGeneration = priorBoot
                throw e
            }
            // Step 4: on_fire in authored order, substituted, failure-isolated.
            val id = reg.entry.reqString("id")
            val list = reg.entry.reqArr("on_fire")
            for (item in list) {
                try {
                    onFire(Substitution.apply(item, id, type, data) as JsonObject)
                } catch (_: Exception) { /* isolated per entry; SPEC 21.4 */ }
            }
        }
    }

    // ---------------------------------------------- gate predicate eval

    private fun allHold(preds: JsonArray): Boolean = predicates.allHold(preds)

    companion object {
        /**
         * SPEC 21.5: the next scheduled occurrence of a repeating time.every_s
         * registration — the first `anchor + k·interval` boundary (k ≥ 1)
         * strictly after its last-fire floor, or the acceptance anchor itself if
         * it has never fired. A returned value in the past means the interval(s)
         * elapsed while the Companion was dead: the host arms the alarm for it,
         * it fires once, and the commit floors the last fire past `now` — so the
         * NEXT call resumes the cadence with no catch-up burst (missed intervals
         * coalesce into that single occurrence). Null for a non-repeating or
         * not-yet-anchored entry (nothing to schedule).
         */
        fun nextRepeatDueMs(reg: TriggerStore.Registration): Long? {
            val params = reg.entry.objOrNull("params") ?: return null
            if ("every_s" !in params) return null
            val interval = params.reqLong("every_s") * 1000
            if (interval <= 0) return null
            val anchor = reg.scheduleAnchorMs ?: return null
            val elapsed = (reg.lastFireFloorMs ?: anchor) - anchor
            val k = (if (elapsed < 0) 0L else elapsed / interval) + 1
            return anchor + k * interval
        }
    }
}
