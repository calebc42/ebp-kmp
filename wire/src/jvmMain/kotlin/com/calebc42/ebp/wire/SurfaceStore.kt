// SPDX-License-Identifier: GPL-3.0-or-later
// The Companion's surface state: revisioned snapshots, tombstones that
// survive until pairing revocation, the surface-count limits, and the
// SPEC 13.6 input-draft reconciliation. In-memory at this rung; the
// persistence layer arrives with W6 durability.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val SURFACE_ID = Regex("(app|notification|widget|tile):[A-Za-z0-9][A-Za-z0-9._:/-]*")

data class SurfaceResult(val status: String, val revision: Long, val present: Boolean)

/** A validated surface mutation could not be committed durably. The engine
 * maps this to JSON-RPC internal-error after [SurfaceStore] restores its prior
 * in-memory state. */
class SurfacePersistenceFailed(cause: Exception) : Exception(cause)

/** T3/LD-2: the value a stateful node's widget should display, and the
 * generation that value belongs to. See [SurfaceStore.inputDisplays].
 *
 * R6: `value` follows the draft seam — Kotlin null is the JSON null, not an
 * absent member (see [PersistedDraft]). */
data class InputDisplay(val epoch: Long, val value: JsonElement?)

/**
 * Current app-target admission configuration for a long-lived surface store.
 *
 * Most hosts use the constructor's static values. A receiver-local feature
 * toggle can instead supply this snapshot per admission without replacing
 * persisted surface state or teaching the wire layer any feature name.
 */
data class AppSurfaceAdmission(
    val nodeTypes: Set<String>? = null,
    val builtins: Set<String>? = null,
    val features: Set<String>? = null,
    val nodeVocabulary: NodeVocabulary = EBP_NODE_VOCABULARY,
)

class SurfaceStore(
    private val maxSurfaces: Long,
    private val maxSurfaceIds: Long,
    private val maxCaptureFields: Long = 64,
    // SPEC 4.5: chart/canvas/span/cell count caps enforced at validation when
    // the corresponding node type is advertised. Spans and cells are AGGREGATE
    // counts across one SurfaceSpec, like chart points across all series.
    private val maxChartPoints: Long = Long.MAX_VALUE,
    private val maxCanvasOps: Long = Long.MAX_VALUE,
    private val maxRichSpans: Long = Long.MAX_VALUE,
    private val maxTableCells: Long = Long.MAX_VALUE,
    // SPEC 17.1: the app / notification profiles' advertised node_types, so an
    // unadvertised-but-known type degrades. null = allow all (in-memory tests).
    private val appNodeTypes: Set<String>? = null,
    private val notificationNodeTypes: Set<String>? = null,
    private val widgetNodeTypes: Set<String>? = null,
    // SPEC 14.2 (LD-17): the advertised builtins per target, for the
    // invalid-context gate. null = allow all.
    private val appBuiltins: Set<String>? = null,
    private val notificationBuiltins: Set<String>? = null,
    private val widgetBuiltins: Set<String>? = null,
    // SPEC 22.4: target-scoped constraining/member-gating features.
    private val appFeatures: Set<String>? = null,
    private val notificationFeatures: Set<String>? = null,
    private val widgetFeatures: Set<String>? = null,
    private val widgetMembers: ProfileMemberSupport? = null,
    private val maxWidgetNodes: Int = MAX_WIDGET_NODES,
    private val maxWidgetLazyItems: Int = MAX_WIDGET_LAZY_ITEMS,
    private val maxWidgetNodeDepth: Int = MAX_WIDGET_NODE_DEPTH,
    private val maxWidgetSizeVariants: Int = MAX_WIDGET_SIZE_VARIANTS,
    /** SPEC 13.4: a tile has no node vocabulary, but its context-less
     * on_tap descriptor remains gated by the tile profile. */
    private val tileBuiltins: Set<String>? = null,
    private val tileFeatures: Set<String>? = null,
    /** Optional host inventory of manifest-declared fixed tile slots. */
    private val tileSurfaceIds: Set<String>? = null,
    /** EBP schemas plus receiver-selected renderer extension schemas. */
    private val nodeVocabulary: NodeVocabulary = EBP_NODE_VOCABULARY,
    /** SPEC 13.1/15.1: durable surface histories + input_state. In-memory
     * by default; DeviceBridge wires a file so both survive process death. */
    private val backing: SurfaceBacking = MemorySurfaceBacking(),
    /** Whether restored descriptors may retain a previously accepted `wake`
     * policy. Session updates still pass the actual negotiated grant. */
    private val offlineWakeConfigured: Boolean = false,
    /** Optional current app-target configuration for receiver-local toggles. */
    private val appAdmissionProvider: (() -> AppSurfaceAdmission)? = null,
) {

    private fun appAdmission(): AppSurfaceAdmission =
        appAdmissionProvider?.invoke() ?: AppSurfaceAdmission(
            nodeTypes = appNodeTypes,
            builtins = appBuiltins,
            features = appFeatures,
            nodeVocabulary = nodeVocabulary,
        )

    private class Record(
        var revision: Long,
        var present: Boolean,
        /** The materialized snapshot every reader presents (SPEC 13.8.3). */
        var spec: JsonObject?,
        var currentView: String?,
        var statefuls: Map<String, JsonObject>,
        var staleSpec: JsonObject?,
        var staleAfterSeconds: Long?,
        /** SPEC 13.8: the authored tree a patch re-materializes, and the document. */
        var authoredSpec: JsonObject?,
        var data: JsonObject?,
        /** SPEC 13.8.5: identity path of every expanded item root to its key; derived, never persisted. */
        var itemKeys: Map<String, String> = emptyMap(),
    )

    /** What the namespace validators admitted for one snapshot. */
    private class Admitted(
        val statefuls: Map<String, JsonObject>,
        val reset: Set<String>,
        val isMultiView: Boolean,
    )

    private val records = LinkedHashMap<String, Record>()
    // Loaded records start durable. Every accepted record mutation marks this
    // dirty until replaceRecords succeeds; strict low-frequency state can then
    // repair a prior best-effort surface write without serializing the entire
    // surface catalog on the ordinary successful path.
    private var recordsDirty = false

    /** Diagnostic: when set, `update` and `patch` report how long each of
     * their stages took — `apply` (ops and document bounds), `materialize`
     * (bindings, templates, sharing), `admit` (the namespace validators) and
     * `commit` (draft reconciliation and the durable write) — in nanoseconds.
     * Null in production; the engine installs a sink only while a host
     * observer is listening, so the measured path pays nothing otherwise. */
    var stageSink: ((stage: String, nanos: Long) -> Unit)? = null

    private inline fun <T> stage(name: String, block: () -> T): T {
        val sink = stageSink ?: return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            sink(name, System.nanoTime() - t0)
        }
    }
    // R6: a present key IS the draft; its value follows the draft seam, where
    // Kotlin null is the JSON null (see [PersistedDraft]).
    private val drafts = HashMap<Pair<String, String>, JsonElement?>()
    // T3/LD-2: display generations — see [inputEpoch]. Deliberately NOT
    // persisted: a process restart rebuilds every widget from the durable
    // draft or authored value anyway, so there is nothing stale to supersede.
    private val epochs = HashMap<Pair<String, String>, Long>()
    private var epochClock = 0L

    init {
        // SPEC 10.4/13.1: the store outlives connections AND process death.
        // Statefuls are recomputed from the durable spec; a record whose
        // spec no longer validates is dropped rather than crashing startup.
        val state = backing.load()
        for (r in state.records) {
            var present = r.present
            var spec = r.spec
            var currentView = r.currentView
            var staleSpec = r.staleSpec
            var staleAfterSeconds = r.staleAfterSeconds
            // SPEC 3.3/13.8: a record from before amendment #194 carries
            // neither member — its spec is its authored tree and its
            // document is empty.
            var authoredSpec = r.authoredSpec ?: r.spec
            var data = r.data
            var itemKeys: Map<String, String> = emptyMap()
            val statefuls = if (r.present && r.spec != null) {
                // SPEC 13.4: the namespace decides the variant, on RELOAD too.
                // Re-validating a notification spec ({body, meta?}) with the
                // app-surface validator failed every time, so every present
                // notification was dropped on every restart.
                val revalidated = runCatching {
                    // SPEC 13.8.3/13.1: re-materialize the authored tree
                    // against the document before re-validating.
                    if (data != null) {
                        Materializer.checkDocument(data, "data")
                        val materialized = Materializer.materializeWithItems(authoredSpec!!, data, "spec", nodeVocabulary)
                        spec = materialized.spec
                        itemKeys = materialized.itemKeys
                        // Nothing resolved: one instance serves as both trees,
                        // exactly as `update` holds it (persistedAuthoredSpec).
                        if (materialized.unbound) authoredSpec = spec
                    }
                    if (namespace(r.surface) == "notification") {
                        SpecValidator.validateNotificationSpec(spec!!,
                            maxCaptureFields = maxCaptureFields,
                            advertisedTypes = notificationNodeTypes,
                            advertisedBuiltins = notificationBuiltins,
                            advertisedFeatures = notificationFeatures,
                            maxChartPoints = maxChartPoints, maxCanvasOps = maxCanvasOps,
                            maxRichSpans = maxRichSpans, maxTableCells = maxTableCells,
                            nodeVocabulary = nodeVocabulary,
                            offlineWakeGranted = offlineWakeConfigured,
                        )
                        r.staleSpec?.let { stale ->
                            SpecValidator.validateNotificationSpec(
                                stale,
                                "stale_spec",
                                maxCaptureFields = maxCaptureFields,
                                advertisedTypes = notificationNodeTypes,
                                advertisedBuiltins = notificationBuiltins,
                                advertisedFeatures = notificationFeatures,
                                maxChartPoints = maxChartPoints,
                                maxCanvasOps = maxCanvasOps,
                                maxRichSpans = maxRichSpans,
                                maxTableCells = maxTableCells,
                                nodeVocabulary = nodeVocabulary,
                                offlineWakeGranted = offlineWakeConfigured,
                            )
                        }
                        emptyMap()
                    } else if (namespace(r.surface) == "widget") {
                        SpecValidator.validateWidgetSpec(
                            spec!!,
                            advertisedTypes = widgetNodeTypes,
                            advertisedBuiltins = widgetBuiltins,
                            advertisedFeatures = widgetFeatures,
                            advertisedMembers = widgetMembers,
                            nodeVocabulary = nodeVocabulary,
                            maxNodes = maxWidgetNodes,
                            maxLazyItems = maxWidgetLazyItems,
                            maxNodeDepth = maxWidgetNodeDepth,
                            maxSizeVariants = maxWidgetSizeVariants,
                            offlineWakeGranted = offlineWakeConfigured,
                        )
                        r.staleSpec?.let { stale ->
                            SpecValidator.validateWidgetSpec(
                                stale,
                                "stale_spec",
                                advertisedTypes = widgetNodeTypes,
                                advertisedBuiltins = widgetBuiltins,
                                advertisedFeatures = widgetFeatures,
                                advertisedMembers = widgetMembers,
                                nodeVocabulary = nodeVocabulary,
                                maxNodes = maxWidgetNodes,
                                maxLazyItems = maxWidgetLazyItems,
                                maxNodeDepth = maxWidgetNodeDepth,
                                maxSizeVariants = maxWidgetSizeVariants,
                                offlineWakeGranted = offlineWakeConfigured,
                            )
                        }
                        emptyMap()
                    } else if (namespace(r.surface) == "tile") {
                        SpecValidator.validateTileSpec(
                            spec!!,
                            advertisedBuiltins = tileBuiltins,
                            advertisedFeatures = tileFeatures,
                            offlineWakeGranted = offlineWakeConfigured,
                        )
                        r.staleSpec?.let { stale ->
                            SpecValidator.validateTileSpec(
                                stale,
                                path = "stale_spec",
                                advertisedBuiltins = tileBuiltins,
                                advertisedFeatures = tileFeatures,
                                offlineWakeGranted = offlineWakeConfigured,
                            )
                        }
                        emptyMap()
                    } else {
                        val admission = appAdmission()
                        SpecValidator.validateSurfaceSpec(spec!!,
                            maxCaptureFields = maxCaptureFields,
                            maxChartPoints = maxChartPoints, maxCanvasOps = maxCanvasOps,
                            maxRichSpans = maxRichSpans, maxTableCells = maxTableCells,
                            advertisedTypes = admission.nodeTypes,
                            advertisedBuiltins = admission.builtins,
                            advertisedFeatures = admission.features,
                            nodeVocabulary = admission.nodeVocabulary,
                            offlineWakeGranted = offlineWakeConfigured,
                        )
                            .also {
                                r.staleSpec?.let { stale ->
                                    SpecValidator.validateStaleSpec(
                                        stale,
                                        "views" in spec!!,
                                        maxCaptureFields,
                                        maxChartPoints,
                                        maxCanvasOps,
                                        maxRichSpans,
                                        maxTableCells,
                                        admission.nodeTypes,
                                        admission.builtins,
                                        admission.features,
                                        admission.nodeVocabulary,
                                        offlineWakeGranted = offlineWakeConfigured,
                                    )
                                }
                            }
                    }
                }.getOrNull()
                if (revalidated == null) {
                    // SPEC 13.1: "MUST retain each tombstone revision floor
                    // until pairing revocation and MUST NOT reclaim it." A
                    // spec this build can no longer validate — because the
                    // validator legitimately tightened between versions —
                    // MUST NOT be rendered or dispatched, but DROPPING the
                    // record reclaimed its floor, so a delayed older
                    // `surface.update` would then be answered `applied`
                    // instead of `stale`. Retain the history as a tombstone:
                    // not present, floor intact, and Emacs re-pushes.
                    present = false
                    spec = null
                    currentView = null
                    staleSpec = null
                    staleAfterSeconds = null
                    authoredSpec = null
                    data = null
                    itemKeys = emptyMap()
                    emptyMap()
                } else revalidated
            } else emptyMap()
            records[r.surface] = Record(
                r.revision,
                present,
                spec,
                currentView,
                statefuls,
                staleSpec,
                staleAfterSeconds,
                authoredSpec,
                data,
                itemKeys,
            )
            // Dirty when what this store would persist differs from what it
            // loaded. The authored tree compares by what it means — an absent
            // member IS the spec (SPEC 3.3) — so a record holding one instance
            // for both trees reloads clean, and so does one written before
            // that with the same tree stored twice: its next mutation stores
            // it once, no repair needed.
            if (present != r.present || spec != r.spec || currentView != r.currentView ||
                staleSpec != r.staleSpec || staleAfterSeconds != r.staleAfterSeconds ||
                (persistedAuthoredSpec(authoredSpec, spec) ?: spec) != (r.authoredSpec ?: r.spec) ||
                data != r.data)
                recordsDirty = true
        }
        // SPEC 10.2/13.6: a draft belongs to a stateful node of a PRESENT
        // surface. The two files persist independently, so a reload can carry
        // a draft whose node the loaded spec does not have; keeping it would
        // publish a phantom in the welcome `input_state`.
        for (d in state.drafts) {
            val rec = records[d.surface] ?: continue
            val node = rec.statefuls[d.id]
            // Equality with the loaded authored value is NOT an
            // acknowledgement. The user may have changed away and then back
            // while offline; only acceptance of a later snapshot can clear
            // that latest dirty value under §13.6.
            if (!rec.present || node == null || !compatible(node, d.value)) continue
            drafts[d.surface to d.id] = d.value
        }
    }

    /** Best-effort record persistence for receiver-local presentation state.
     * RPC surface mutations use [persistSurfaceMutation] instead: they may not
     * return `applied` until their accepted state is durable. */
    private fun tryPersistRecords(): Boolean =
        try {
            backing.replaceRecords(persistedRecords())
            recordsDirty = false
            true
        } catch (_: Exception) {
            recordsDirty = true
            false
        }

    /** Best-effort persistence of [surface]'s record alone when the backing
     * can write one row and nothing else is dirty; otherwise the whole
     * catalog, which also repairs whatever an earlier write left behind.
     * [wasDirty] is the dirty state BEFORE this mutation marked it. */
    private fun tryPersistRecord(surface: String, wasDirty: Boolean): Boolean {
        val incremental = backing as? IncrementalSurfaceBacking
        if (incremental == null || wasDirty) return tryPersistRecords()
        return try {
            incremental.putRecord(persistedRecord(surface, records.getValue(surface)))
            recordsDirty = false
            true
        } catch (_: Exception) {
            recordsDirty = true
            false
        }
    }

    private fun persistedRecords(): List<PersistedRecord> =
        records.map { (surface, r) -> persistedRecord(surface, r) }

    private fun persistedRecord(surface: String, r: Record): PersistedRecord =
        PersistedRecord(
            surface,
            r.revision,
            r.present,
            r.spec,
            r.currentView,
            r.staleSpec,
            r.staleAfterSeconds,
            persistedAuthoredSpec(r.authoredSpec, r.spec),
            r.data,
        )

    /** The authored tree as the record stores it. A snapshot in which nothing
     * was bound, expanded or hidden — every push without `data.model`, and
     * the ordinary documentless push under it — has an authored tree that IS
     * its materialized one, and the record holds that single instance for
     * both roles. Storing it once halves the bytes every such push writes;
     * the absent member is the documented pre-#194 shape, which loads as the
     * spec itself (SPEC 3.3). A record whose two trees differ stores both. */
    private fun persistedAuthoredSpec(authoredSpec: JsonObject?, spec: JsonObject?): JsonObject? =
        authoredSpec?.takeUnless { it === spec }

    private fun persistDrafts() {
        runCatching {
            backing.replaceDrafts(persistedDrafts())
        }
    }

    private fun persistedDrafts(): List<PersistedDraft> =
        drafts.map { (k, v) -> PersistedDraft(k.first, k.second, v) }

    /**
     * SPEC 13.2-13.3 + LD-14: commit a validated update/remove before claiming
     * it. When reconciliation removed drafts, write that complete candidate
     * first and records second. A reader that sees the new revision can then
     * never see a draft it cleared. If the record replace fails after the
     * draft replace, compensate with the preceding draft snapshot before
     * reporting failure; the caller restores the in-memory candidate.
     *
     * Empty [removedDrafts] is the ordinary refresh hot path: one record
     * write and no draft serialization or fsync.
     *
     * [surface] is the one record this mutation changed. An
     * [IncrementalSurfaceBacking] receives that record alone — the catalog's
     * other rows are untouched, so a patch to one screen no longer re-encodes
     * every present surface — unless an earlier best-effort write left the
     * catalog dirty ([wasRecordsDirty]), in which case the whole replace
     * below repairs it as before.
     */
    private fun persistSurfaceMutation(surface: String, removedDrafts: List<PersistedDraft>,
                                       wasRecordsDirty: Boolean) {
        val nextDrafts = removedDrafts.takeIf { it.isNotEmpty() }?.let {
            persistedDrafts()
        }
        val incremental = backing as? IncrementalSurfaceBacking
        if (incremental != null && !wasRecordsDirty) {
            try {
                val record = persistedRecord(surface, records.getValue(surface))
                if (nextDrafts != null) incremental.putRecordWithDrafts(record, nextDrafts)
                else incremental.putRecord(record)
                recordsDirty = false
            } catch (failure: Exception) {
                // One atomic write: nothing durable changed. The caller
                // restores the in-memory candidate and the prior dirty state.
                throw SurfacePersistenceFailed(failure)
            }
            return
        }
        var draftsReplaced = false
        try {
            if (nextDrafts != null && backing is AtomicSurfaceBacking) {
                backing.replaceState(persistedRecords(), nextDrafts)
                recordsDirty = false
                return
            }
            if (nextDrafts != null) {
                backing.replaceDrafts(nextDrafts)
                draftsReplaced = true
            }
            backing.replaceRecords(persistedRecords())
            recordsDirty = false
        } catch (failure: Exception) {
            recordsDirty = true
            if (draftsReplaced) {
                try {
                    // Removed keys are absent from nextDrafts, so concatenation
                    // reconstructs the complete preceding draft snapshot.
                    backing.replaceDrafts(nextDrafts!! + removedDrafts)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw SurfacePersistenceFailed(failure)
        }
    }

    fun isValidSurfaceId(id: String): Boolean =
        SURFACE_ID.matches(id) && id.toByteArray(Charsets.UTF_8).size <= 128 &&
            (namespace(id) != "tile" || tileSurfaceIds == null || id in tileSurfaceIds)

    /** The accepted snapshot for a present surface, for rendering. */
    fun spec(surface: String): JsonObject? =
        records[surface]?.takeIf { it.present }?.spec

    /** SPEC 13.8: the data document of a present surface; null without one. */
    fun data(surface: String): JsonObject? =
        records[surface]?.takeIf { it.present }?.data

    /** SPEC 13.8: the authored tree (with `bind`) of a present surface. */
    fun authoredSpec(surface: String): JsonObject? =
        records[surface]?.takeIf { it.present }?.authoredSpec

    /**
     * SPEC 13.8.5/14.3: the key of the innermost template item containing the
     * node at [sourcePath] (an [IdentityPath] the renderer reported), or null
     * when the node sits in no expanded item.
     */
    fun itemKey(surface: String, sourcePath: String?): String? {
        if (sourcePath == null) return null
        val items = records[surface]?.takeIf { it.present }?.itemKeys ?: return null
        var best: String? = null
        var bestLength = -1
        for ((root, key) in items) {
            if ((sourcePath == root || sourcePath.startsWith("$root/")) && root.length > bestLength) {
                best = key
                bestLength = root.length
            }
        }
        return best
    }

    /** SPEC 14.4: the accepted revision the user is looking at. */
    fun revisionOf(surface: String): Long? =
        records[surface]?.takeIf { it.present }?.revision

    /** SPEC 13.4/14.2: the view a present multi-view surface shows. */
    fun currentView(surface: String): String? =
        records[surface]?.takeIf { it.present }?.currentView

    /** SPEC 14.2 view.switch: valid only inside a present multi-view surface
     * whose snapshot names `view`; the local choice persists like any other
     * presentation state. Returns whether the switch happened. */
    fun switchView(surface: String, view: String): Boolean {
        val r = records[surface]?.takeIf { it.present } ?: return false
        val views = r.spec?.objOrNull("views") ?: return false
        if (view !in views) return false
        val wasRecordsDirty = recordsDirty
        r.currentView = view
        recordsDirty = true
        // View choice is record state, no draft touched — and one record's
        // state: the Back gesture must not rewrite the whole catalog.
        tryPersistRecord(surface, wasRecordsDirty)
        return true
    }

    /** SPEC 14.1: the occurrence-time logical value of a stateful node —
     * the dirty draft when one exists, else the authored value/default. */
    fun currentValue(surface: String, id: String): JsonElement? {
        if (surface to id in drafts) return drafts[surface to id]
        return records[surface]?.statefuls?.get(id)?.let { authoredValue(it) }
    }

    /** SPEC 14.6: password nodes never emit state or retain drafts. */
    fun isPasswordNode(surface: String, id: String): Boolean =
        records[surface]?.statefuls?.get(id)?.boolOr("password") == true

    /** Whether ID is a stateful node in SURFACE's accepted snapshot. */
    fun isStatefulNode(surface: String, id: String): Boolean =
        records[surface]?.statefuls?.containsKey(id) == true

    /** Resolve `variant.switch` against the accepted authored branch order.
     * No mutation occurs here; [CompanionEngine.publishState] remains the one
     * durable draft/state.changed funnel. A missing requested value advances
     * cyclically, while an explicit current value is a clean no-op. */
    fun resolveVariantSwitch(surface: String, id: String,
                             requested: String?): String? {
        val node = records[surface]?.statefuls?.get(id)
            ?.takeIf { it.stringOr("t") == "variant_host" } ?: return null
        val values = node.arrOrNull("variants")?.mapNotNull {
            (it as? JsonObject)?.stringOrNull("value")
        }.orEmpty()
        if (values.size < 2) return null // accepted specs cannot reach this
        val current = currentValue(surface, id)?.asStringOrNull()
            ?.takeIf { it in values } ?: node.stringOr("value")
        val next = if (requested != null) {
            requested.takeIf { it in values }
        } else {
            values[(values.indexOf(current).coerceAtLeast(0) + 1) % values.size]
        }
        return next?.takeUnless { it == current }
    }

    /** The current selection for every retained host of every present
     * surface. Kept separate from [inputDisplays] by the Android bridge so a
     * local presentation switch does not invalidate unrelated widgets. */
    fun variantSelections(): Map<Pair<String, String>, String> {
        val out = HashMap<Pair<String, String>, String>()
        for ((surface, record) in records) {
            if (!record.present) continue
            for ((id, node) in record.statefuls) {
                if (node.stringOr("t") != "variant_host") continue
                currentValue(surface, id)?.asStringOrNull()?.let {
                    out[surface to id] = it
                }
            }
        }
        return out
    }

    fun namespace(id: String): String = id.substringBefore(':')

    /** SPEC 13.8.5: namespaces whose snapshots MUST NOT carry `template`. */
    private val TEMPLATE_FREE_NAMESPACES = setOf("notification", "widget", "tile")

    // ------------------------------------------------------ update (13.2)

    fun update(surface: String, revision: Long, authoredSpec: JsonObject,
               staleSpec: JsonObject?, currentView: String?,
               resetIds: JsonArray?,
               staleAfterSeconds: Long? = null,
               offlineWakeGranted: Boolean = offlineWakeConfigured,
               data: JsonObject? = null,
               dataModelGranted: Boolean = false): SurfaceResult {
        if (staleAfterSeconds != null && staleAfterSeconds <= 0)
            throw ContentInvalid("stale_after_s", "must be a positive integer")
        // SPEC 13.8: bindings resolve before anything is validated; without
        // the capability neither the document nor a binding is admitted.
        val document: JsonObject?
        val spec: JsonObject
        var itemKeys: Map<String, String> = emptyMap()
        // Whether materialization left the tree member-for-member the
        // authored one: no binding, template or conditional. Such a record
        // holds one instance for both trees and stores it once.
        var unbound = false
        // The prior revision's tree: every untouched subtree of this one is
        // shared with it by reference (Materializer.share).
        val prevSpec = records[surface]?.takeIf { it.present }?.spec
        if (!dataModelGranted) {
            if (data != null) throw ContentInvalid("data", "requires the data.model capability")
            // The template is named before the bindings it carries.
            Materializer.findMember(authoredSpec, "spec", "template")?.let {
                throw ContentInvalid(it, "template requires the data.model capability")
            }
            Materializer.findMember(authoredSpec, "spec", "visible_when")?.let {
                throw ContentInvalid(it, "visible_when requires the data.model capability")
            }
            Materializer.findBind(authoredSpec, "spec")?.let {
                throw ContentInvalid(it, "bind requires the data.model capability")
            }
            document = null
            spec = stage("materialize") {
                prevSpec?.let { Materializer.share(authoredSpec, it) as JsonObject } ?: authoredSpec
            }
            unbound = true
        } else {
            // SPEC 13.8.5: a template is admitted in app snapshots only.
            if (namespace(surface) in TEMPLATE_FREE_NAMESPACES) {
                Materializer.findMember(authoredSpec, "spec", "template")?.let {
                    throw ContentInvalid(it, "template is admitted in app snapshots only")
                }
                // SPEC 13.8.6: conditional nodes share the template's admission.
                Materializer.findMember(authoredSpec, "spec", "visible_when")?.let {
                    throw ContentInvalid(it, "visible_when is admitted in app snapshots only")
                }
            }
            document = data ?: JsonObject(emptyMap())
            stage("apply") { Materializer.checkDocument(document, "data") }
            val materialized = stage("materialize") {
                Materializer.materializeWithItems(authoredSpec, document, "spec", nodeVocabulary,
                    previous = prevSpec)
            }
            spec = materialized.spec
            itemKeys = materialized.itemKeys
            unbound = materialized.unbound
        }
        staleSpec?.let { stale ->
            Materializer.findMember(stale, "stale_spec", "template")?.let {
                throw ContentInvalid(it, "stale_spec MUST NOT carry template")
            }
            Materializer.findMember(stale, "stale_spec", "visible_when")?.let {
                throw ContentInvalid(it, "stale_spec MUST NOT carry visible_when")
            }
            Materializer.findBind(stale, "stale_spec")?.let {
                throw ContentInvalid(it, "stale_spec MUST NOT carry bind")
            }
        }
        val admitted = stage("admit") {
            admit(surface, spec, staleSpec, currentView, resetIds, offlineWakeGranted)
        }
        val statefuls = admitted.statefuls
        val reset = admitted.reset
        val isMultiView = admitted.isMultiView
        val record = records[surface]
        val floor = record?.revision ?: -1L
        if (revision <= floor)
            return SurfaceResult("stale", floor, record?.present ?: false)
        // SPEC 13.1: limits gate anything that would grow a saturated count.
        if (record == null) {
            if (records.size.toLong() >= maxSurfaceIds)
                throw ContentInvalid("surface", "surface-limit")
            if (presentCount() >= maxSurfaces)
                throw ContentInvalid("surface", "surface-limit")
        } else if (!record.present && presentCount() >= maxSurfaces) {
            // Reactivating a tombstone is invalid at max_surfaces.
            throw ContentInvalid("surface", "surface-limit")
        }
        // SPEC 13.4: preserve the user's view unless it vanished, the
        // surface is new, or the request names one.
        val nextCurrentView = when {
            !isMultiView -> null
            currentView != null -> currentView
            record?.present == true && record.currentView != null &&
                record.currentView!! in spec.reqObj("views") -> record.currentView
            else -> spec.reqString("initial_view")
        }
        val next = Record(
            revision,
            true,
            spec,
            nextCurrentView,
            statefuls,
            staleSpec,
            staleAfterSeconds,
            // A tree with no binding, template or conditional — every push
            // without `data.model`, and the ordinary documentless push with it
            // — materializes to itself: hold the single shared instance, which
            // the record then stores once (persistedAuthoredSpec).
            if (unbound) spec else authoredSpec,
            document,
            itemKeys,
        )
        return stage("commit") { commitSnapshot(surface, record, next, reset) }
    }

    /**
     * SPEC 13.8.4: apply [ops] to a present surface's document at [revision]
     * and re-materialize its authored tree. The whole request is validated
     * first; the patch applies completely or not at all.
     */
    fun patch(surface: String, revision: Long, ops: JsonArray,
              offlineWakeGranted: Boolean = offlineWakeConfigured): SurfaceResult {
        val record = records[surface]
        if (record == null || !record.present)
            throw ContentInvalid("surface", "no present snapshot to patch")
        if (ops.isEmpty()) throw ContentInvalid("ops", "must be a non-empty array")
        if (ops.size > WireLimits.MAX_DATA_OPS) throw ContentInvalid("ops", "exceeds max_data_ops")
        var document = record.data ?: JsonObject(emptyMap())
        stage("apply") {
            document = DataOps.applyAll(document, ops)
            Materializer.checkDocument(document, "data")
        }
        val authored = record.authoredSpec ?: record.spec!!
        val materialized = stage("materialize") {
            Materializer.materializeWithItems(authored, document, "spec", nodeVocabulary,
                previous = record.spec)
        }
        val spec = materialized.spec
        val admitted = stage("admit") {
            admit(surface, spec, record.staleSpec, null, null, offlineWakeGranted)
        }
        if (revision <= record.revision)
            return SurfaceResult("stale", record.revision, true)
        val next = Record(
            revision,
            true,
            spec,
            record.currentView,
            admitted.statefuls,
            record.staleSpec,
            record.staleAfterSeconds,
            authored,
            document,
            materialized.itemKeys,
        )
        return stage("commit") { commitSnapshot(surface, record, next, emptySet()) }
    }

    /** SPEC 13.2/13.4: the namespace's validators, over the materialized [spec]. */
    private fun admit(surface: String, spec: JsonObject, staleSpec: JsonObject?,
                      currentView: String?, resetIds: JsonArray?,
                      offlineWakeGranted: Boolean): Admitted {
        // SPEC 13.2: validate the entire request before changing state.
        // SPEC 13.4: the namespace decides the SurfaceSpec variant.
        val statefuls: Map<String, JsonObject>
        val reset: Set<String>
        val isMultiView: Boolean
        if (namespace(surface) == "notification") {
            // SPEC 13.4/18.5: {body: Node, meta?}, no views, no drafts.
            SpecValidator.validateNotificationSpec(spec, maxCaptureFields = maxCaptureFields,
                advertisedTypes = notificationNodeTypes,
                advertisedBuiltins = notificationBuiltins,
                advertisedFeatures = notificationFeatures,
                maxChartPoints = maxChartPoints, maxCanvasOps = maxCanvasOps,
                maxRichSpans = maxRichSpans, maxTableCells = maxTableCells,
                nodeVocabulary = nodeVocabulary,
                offlineWakeGranted = offlineWakeGranted)
            if (currentView != null)
                throw ContentInvalid("current_view", "not valid for a notification surface")
            if (resetIds != null && resetIds.size > 0)
                throw ContentInvalid("reset_input_ids", "a notification surface has no drafts")
            staleSpec?.let {
                SpecValidator.validateNotificationSpec(it, "stale_spec", maxCaptureFields,
                    advertisedTypes = notificationNodeTypes,
                    advertisedBuiltins = notificationBuiltins,
                    advertisedFeatures = notificationFeatures,
                    maxChartPoints = maxChartPoints, maxCanvasOps = maxCanvasOps,
                    maxRichSpans = maxRichSpans, maxTableCells = maxTableCells,
                    nodeVocabulary = nodeVocabulary,
                    offlineWakeGranted = offlineWakeGranted)
            }
            statefuls = emptyMap()
            reset = emptySet()
            isMultiView = false
        } else if (namespace(surface) == "tile") {
            // SPEC 13.4: a tile is a closed host-slot projection, not a Node
            // tree. It has no current view, drafts, or resettable inputs.
            SpecValidator.validateTileSpec(
                spec,
                advertisedBuiltins = tileBuiltins,
                advertisedFeatures = tileFeatures,
                offlineWakeGranted = offlineWakeGranted,
            )
            if (currentView != null)
                throw ContentInvalid("current_view", "not valid for a tile surface")
            if (resetIds != null && resetIds.isNotEmpty())
                throw ContentInvalid("reset_input_ids", "a tile surface has no drafts")
            staleSpec?.let {
                SpecValidator.validateTileSpec(
                    it,
                    path = "stale_spec",
                    advertisedBuiltins = tileBuiltins,
                    advertisedFeatures = tileFeatures,
                    offlineWakeGranted = offlineWakeGranted,
                )
            }
            statefuls = emptyMap()
            reset = emptySet()
            isMultiView = false
        } else if (namespace(surface) == "widget") {
            SpecValidator.validateWidgetSpec(
                spec,
                maxCaptureFields = maxCaptureFields,
                advertisedTypes = widgetNodeTypes,
                advertisedBuiltins = widgetBuiltins,
                advertisedFeatures = widgetFeatures,
                advertisedMembers = widgetMembers,
                nodeVocabulary = nodeVocabulary,
                maxNodes = maxWidgetNodes,
                maxLazyItems = maxWidgetLazyItems,
                maxNodeDepth = maxWidgetNodeDepth,
                maxSizeVariants = maxWidgetSizeVariants,
            )
            if (currentView != null)
                throw ContentInvalid("current_view", "not valid for a widget surface")
            if (resetIds != null && resetIds.isNotEmpty())
                throw ContentInvalid("reset_input_ids", "a widget surface has no drafts")
            staleSpec?.let {
                SpecValidator.validateWidgetSpec(
                    it,
                    "stale_spec",
                    maxCaptureFields = maxCaptureFields,
                    advertisedTypes = widgetNodeTypes,
                    advertisedBuiltins = widgetBuiltins,
                    advertisedFeatures = widgetFeatures,
                    advertisedMembers = widgetMembers,
                    nodeVocabulary = nodeVocabulary,
                    maxNodes = maxWidgetNodes,
                    maxLazyItems = maxWidgetLazyItems,
                    maxNodeDepth = maxWidgetNodeDepth,
                    maxSizeVariants = maxWidgetSizeVariants,
                )
            }
            statefuls = emptyMap()
            reset = emptySet()
            isMultiView = false
        } else {
            val admission = appAdmission()
            statefuls = SpecValidator.validateSurfaceSpec(
                spec, maxCaptureFields = maxCaptureFields,
                maxChartPoints = maxChartPoints, maxCanvasOps = maxCanvasOps,
                maxRichSpans = maxRichSpans, maxTableCells = maxTableCells,
                advertisedTypes = admission.nodeTypes,
                advertisedBuiltins = admission.builtins,
                advertisedFeatures = admission.features,
                nodeVocabulary = admission.nodeVocabulary,
                offlineWakeGranted = offlineWakeGranted)
            reset = resetIds?.let { SpecValidator.validateResetIds(it, statefuls) }
                ?: emptySet()
            // SPEC 13.5/14.2/17.1: a stale_spec is RENDERED while
            // disconnected, so it is held to the same per-target gates as the
            // live spec — an unadvertised builtin inside it is the same
            // invalid context, and an unadvertised node type must degrade
            // rather than be held to its per-type schema.
            staleSpec?.let { SpecValidator.validateStaleSpec(it, "views" in spec,
                maxCaptureFields, maxChartPoints, maxCanvasOps,
                maxRichSpans, maxTableCells, admission.nodeTypes, admission.builtins,
                admission.features, admission.nodeVocabulary,
                offlineWakeGranted = offlineWakeGranted) }
            isMultiView = "views" in spec
            if (currentView != null) {
                if (!isMultiView || currentView !in spec.reqObj("views"))
                    throw ContentInvalid("current_view",
                        "valid only for a multi-view app spec naming an existing view")
            }
        }
        return Admitted(statefuls, reset, isMultiView)
    }

    /**
     * SPEC 13.2/13.6/LD-2: install [next] over [record], reconcile drafts,
     * commit durably (rolling back on failure) and stamp display epochs.
     */
    private fun commitSnapshot(surface: String, record: Record?, next: Record,
                               reset: Set<String>): SurfaceResult {
        val revision = next.revision
        val statefuls = next.statefuls
        // SPEC 13.6: the pre-update node types decide draft compatibility.
        val oldStatefuls = record?.statefuls ?: emptyMap()
        // T3/LD-2: what the display is showing right now, before this
        // snapshot is applied — the draft when the user has one, else the
        // authored value.
        val shownBefore = (oldStatefuls.keys + statefuls.keys)
            .associateWith { currentValue(surface, it) }
        // Install a fresh candidate rather than mutating RECORD in place. It
        // remains the complete rollback value until the durable commit wins.
        val wasRecordsDirty = recordsDirty
        records[surface] = next
        recordsDirty = true
        val removedDrafts = reconcileDrafts(surface, oldStatefuls, statefuls, reset)
        // T3/LD-2: THE stamping point. A node whose displayed value is not
        // what it was, when the user did not change it, has been decided by
        // this snapshot — its widget must reseed. This catches both shapes at
        // once: an erased draft (the authored value now governs) and a moved
        // authored value with no draft standing. A surviving draft compares
        // equal to itself, so a user's in-progress edit never reseeds.
        val changedDisplays = shownBefore.filter { (id, before) ->
            !jsonValueEquals(before, currentValue(surface, id))
        }.keys
        try {
            // Most surface refreshes (including Org visibility) remove no
            // input draft. persistSurfaceMutation keeps those on the
            // record-only path.
            persistSurfaceMutation(surface, removedDrafts, wasRecordsDirty)
        } catch (failure: SurfacePersistenceFailed) {
            if (record == null) records.remove(surface) else records[surface] = record
            removedDrafts.forEach { d -> drafts[d.surface to d.id] = d.value }
            recordsDirty = wasRecordsDirty
            throw failure
        }
        // Stamp only after the durable candidate commits. A failed update did
        // not decide a displayed value and must not advance its generation.
        for (id in changedDisplays) epochs[surface to id] = ++epochClock
        return SurfaceResult("applied", revision, true)
    }

    // ------------------------------------------------------ remove (13.3)

    fun remove(surface: String, revision: Long): SurfaceResult {
        val record = records[surface]
        val floor = record?.revision ?: -1L
        if (revision <= floor)
            return SurfaceResult("stale", floor, record?.present ?: false)
        if (record == null && records.size.toLong() >= maxSurfaceIds)
            throw ContentInvalid("surface", "surface-limit")
        val next = Record(
            revision,
            false,
            null,
            null,
            emptyMap(),
            null,
            null,
            null,
            null,
        )
        val wasRecordsDirty = recordsDirty
        records[surface] = next
        recordsDirty = true
        // SPEC 13.3: tombstoning erases every draft belonging to the surface.
        val removedDrafts = drafts.keys.filter { it.first == surface }.map { key ->
            PersistedDraft(key.first, key.second, drafts[key])
        }
        removedDrafts.forEach { drafts.remove(it.surface to it.id) }
        try {
            persistSurfaceMutation(surface, removedDrafts, wasRecordsDirty)
        } catch (failure: SurfacePersistenceFailed) {
            if (record == null) records.remove(surface) else records[surface] = record
            removedDrafts.forEach { d -> drafts[d.surface to d.id] = d.value }
            recordsDirty = wasRecordsDirty
            throw failure
        }
        return SurfaceResult("applied", revision, false)
    }

    private fun presentCount(): Long = records.values.count { it.present }.toLong()

    /** SPEC 13.5/19: the present surfaces, in acceptance order. The store
     * outlives a connection, so this is what a fresh session must reconcile
     * against — a reconnect that receives no `surface.update` still has
     * present snapshots, and their synchronized editors still need sessions. */
    fun presentSurfaces(): List<String> =
        records.entries.filter { it.value.present }.map { it.key }

    // -------------------------------------------------- welcome reporting

    /** SPEC 10.2: both present snapshots and tombstones are reported. */
    fun snapshot(): JsonObject = buildJsonObject {
        for ((id, record) in records) {
            put(id, buildJsonObject {
                put("revision", record.revision)
                put("present", record.present)
                // SPEC 10.2 (amendment #129): a present multi-view app surface
                // also reports the view the user is actually on.
                // `view.switched` is when_offline "drop", so a navigation
                // performed while Emacs was disconnected is otherwise
                // unrecoverable at the 10.3 barrier. currentView is null for a
                // single-root snapshot (#128 clears it), so this is present
                // exactly when the spec requires it.
                if (record.present && id.startsWith("app:"))
                    record.currentView?.let { put("current_view", it) }
            })
        }
    }

    /** SPEC 10.2 input_state: latest non-password values, present surfaces. */
    fun inputState(): JsonObject {
        // Immutable trees: accumulate per surface and build once. The old
        // `out.getJSONObject(surface).put(...)` reached into a nested object
        // and mutated it in place — an operation that no longer exists.
        val out = LinkedHashMap<String, MutableMap<String, JsonElement>>()
        for ((key, value) in drafts) {
            val (surface, id) = key
            if (records[surface]?.present != true) continue
            // R6: a draft's Kotlin null IS the JSON null, and input_state
            // reports it as one (the old `?: JSONObject.NULL`).
            out.getOrPut(surface) { LinkedHashMap() }[id] = value ?: JsonNull
        }
        return JsonObject(out.mapValues { (_, members) -> JsonObject(members) })
    }

    // ------------------------------------------------------ drafts (13.6)

    fun putDraft(surface: String, id: String, value: JsonElement?) {
        val node = records[surface]?.statefuls?.get(id) ?: return
        if (node.boolOr("password")) return // SPEC 14.6: never retained
        drafts[surface to id] = value
        // SPEC 15.1: the input_state snapshot is durable no later than any
        // event created from this interaction. LD-14: only drafts.json is
        // rewritten — a keystroke no longer re-serializes every spec and
        // every tombstone.
        persistDrafts()
    }

    /**
     * Admission path for receiver-local state whose presentation must not
     * move unless reconnect can recover the same value. The candidate first
     * has to fit the complete serialized `input_state`, then the backing must
     * confirm both the accepted surface namespace and the atomic draft-file
     * replacement. Either failure rolls the in-memory map back and returns
     * false; ordinary high-frequency typing retains the historical
     * best-effort [putDraft] path.
     */
    fun tryPutDraft(surface: String, id: String, value: JsonElement?,
                    maxInputStateBytes: Long): Boolean {
        val node = records[surface]?.statefuls?.get(id) ?: return false
        if (node.boolOr("password") || !compatible(node, value)) return false
        val key = surface to id
        val existed = key in drafts
        val old = drafts[key]
        drafts[key] = value
        fun rollback() {
            if (existed) drafts[key] = old else drafts.remove(key)
        }
        val bytes = inputState().toString().toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > maxInputStateBytes) {
            rollback()
            return false
        }
        return try {
            // A prior accepted surface update may have hit the historical
            // best-effort record persistence path. Confirm the stateful
            // namespace itself is durable before claiming this strict draft
            // commit; otherwise process recreation would discard an orphaned
            // draft even though the visible branch had moved.
            if (recordsDirty && !tryPersistRecords()) {
                rollback()
                return false
            }
            backing.replaceDrafts(persistedDrafts())
            true
        } catch (_: Exception) {
            rollback()
            false
        }
    }

    fun draft(surface: String, id: String): JsonElement? = drafts[surface to id]

    fun hasDraft(surface: String, id: String): Boolean = (surface to id) in drafts

    /**
     * T3/LD-2: the generation of the value a stateful node's DISPLAY should be
     * showing. It changes only when a snapshot — not the user — decided that
     * value: a draft the user typed was erased under §13.6 (`reset_input_ids`,
     * an incompatible or acknowledged value, a reused ID), or the authored
     * value moved while no draft stood.
     *
     * This exists because the store is authoritative for `capture_fields`
     * (§14.1) and the welcome `input_state` (§15.1) while the editing widget
     * keeps its own copy. Keyed on `(surface, id)` alone, that copy is
     * invariant across revisions: its seeding lambda runs once and no later
     * snapshot ever reseeds it. So Emacs could push `value: "Untitled"` with
     * `reset_input_ids: ["title"]`, the store would erase the draft, and the
     * field would still read what the user typed — while a `capture_fields`
     * button submitted "Untitled". The value on screen and the value on the
     * wire were two different values, with nothing to reconcile them.
     *
     * The epoch is that reconciliation point, and like Emacs's `make_current`
     * it is stamped in exactly ONE place: [update], below.
     */
    fun inputEpoch(surface: String, id: String): Long = epochs[surface to id] ?: 0L

    /** Every `(surface, id)` whose epoch is non-zero, for the host's snapshot
     * of display generations. */
    fun inputEpochs(): Map<Pair<String, String>, Long> = epochs.toMap()

    /**
     * T3/LD-2: what every stateful node of every PRESENT surface should be
     * displaying, and which generation that is.
     *
     * The epoch alone is not enough. A widget that seeds from the node's
     * AUTHORED value shows the wrong thing whenever it is disposed and
     * recomposed while a draft stands — a view switch, a `collapsible`
     * folding, a `lazy_column` recycling the row — because no snapshot
     * changed, so no epoch moves, and the widget silently reverts to the
     * authored value while the store (and therefore `capture_fields` and
     * `input_state`) still holds the user's. Publishing the value makes the
     * store the seed authority in every case, and the epoch says only WHEN
     * to re-seed.
     */
    fun inputDisplays(): Map<Pair<String, String>, InputDisplay> {
        val out = HashMap<Pair<String, String>, InputDisplay>()
        for ((surface, r) in records) {
            if (!r.present) continue
            for (id in r.statefuls.keys) {
                val key = surface to id
                out[key] = InputDisplay(epochs[key] ?: 0L, currentValue(surface, id))
            }
        }
        return out
    }

    private fun reconcileDrafts(surface: String, oldStatefuls: Map<String, JsonObject>,
                                newStatefuls: Map<String, JsonObject>,
                                reset: Set<String>): List<PersistedDraft> {
        val stale = drafts.keys.filter { (s, id) ->
            s == surface && run {
                val node = newStatefuls[id]
                val value = drafts[s to id]
                val oldNode = oldStatefuls[id]
                node == null ||                      // ID disappeared
                    id in reset ||                   // explicitly replaced
                    // SPEC 13.6/16.1: reusing an ID for a different node type
                    // is a new identity — erase even when the value schema is
                    // still compatible (checkbox<->switch, text_input->editor).
                    (oldNode != null && oldNode.reqString("t") != node.reqString("t")) ||
                    !compatible(node, value) ||      // schema incompatible
                    jsonValueEquals(authoredValue(node), value) // acknowledged
            }
        }
        val removed = stale.map { key ->
            PersistedDraft(key.first, key.second, drafts[key])
        }
        stale.forEach(drafts::remove)
        return removed
    }

    /** SPEC 13.6: value-schema compatibility is exact. */
    private fun compatible(node: JsonObject, value: JsonElement?): Boolean =
        when (node.reqString("t")) {
            "text_input" -> value is JsonPrimitive && value.isString &&
                !node.boolOr("password") &&
                (!node.boolOr("single_line") || '\n' !in value.content) &&
                integralLongOrNull(node["max_length"])?.let { maximum ->
                    textInputScalarLength(value.content) <= maximum
                } != false &&
                textInputFilterMatches(value.content, node.stringOrNull("filter"))
            // §13.6: `state` present makes the value schema the enum STRING,
            // not a boolean — a retained boolean draft is incompatible and is
            // erased, which is why tri-state is a distinct member rather than
            // a widened `checked`.
            "checkbox" ->
                if ("state" in node)
                    value is JsonPrimitive && value.isString &&
                        value.content in TRI_STATES
                else isJsonBoolean(value)
            "switch" -> isJsonBoolean(value)
            "button", "icon_button" -> "checked" in node && isJsonBoolean(value)
            "search_bar" -> value is JsonPrimitive && value.isString
            // §17.4 dropdown: the editable form's value is the TEXT (any
            // string, like text_input); the plain form's is an option value,
            // like enum_list single-select.
            "dropdown" ->
                if (node.boolOr("editable"))
                    value is JsonPrimitive && value.isString
                else {
                    val options = node.reqArr("options")
                    value !is JsonArray &&
                        options.any { jsonValueEquals(it.jsonObject["value"], value) }
                }
            // §17.4: segmented_button's value schema mirrors enum_list exactly
            // (one option value, or an array of them under multi_select),
            // minus allow_add, which it does not carry.
            "enum_list", "segmented_button" -> {
                val options = node.reqArr("options")
                val legal = { v: JsonElement? ->
                    options.any { jsonValueEquals(it.jsonObject["value"], v) } ||
                        (node.boolOr("allow_add") &&
                            v is JsonPrimitive && v.isString && v.content.isNotEmpty())
                }
                if (node.boolOr("multi_select"))
                    value is JsonArray && value.all { legal(it) }
                else value !is JsonArray && legal(value)
            }
            // §13.6: `value_end` present makes the value schema a TWO-NUMBER
            // array (both thumbs), so a scalar draft from before the member
            // was authored is incompatible and erased — and vice versa.
            "slider" ->
                if ("value_end" in node) {
                    val min = jsonNumberOrNull(node["min"]) ?: 0.0
                    val max = jsonNumberOrNull(node["max"]) ?: 1.0
                    val pair = (value as? JsonArray)?.takeIf { it.size == 2 }
                        ?.mapNotNull { jsonNumberOrNull(it) }
                    pair != null && pair.size == 2 && pair[0] <= pair[1] &&
                        pair.all { it in min..max }
                } else jsonNumberOrNull(value)?.let { num ->
                    val values = node.arrOrNull("values")
                    if (values != null)
                        values.any { jsonValueEquals(it, value) }
                    else {
                        val min = jsonNumberOrNull(node["min"]) ?: 0.0
                        val max = jsonNumberOrNull(node["max"]) ?: 1.0
                        num in min..max
                    }
                } ?: false
            "editor" -> value is JsonPrimitive && value.isString &&
                node.boolOr("publish_state") && "document" !in node &&
                (!node.boolOr("single_line") || '\n' !in value.content)
            "variant_host" -> value is JsonPrimitive && value.isString &&
                node.reqArr("variants").any {
                    (it as? JsonObject)?.stringOrNull("value") == value.content
                }
            else -> false
        }

    companion object {
        /** SPEC 14.1: the authored/default logical value of a stateful node —
         * the value in force before the user touches it. Shared with the
         * dialog defaults layer (T3/LD-3), which has no draft store of its
         * own, so both layers resolve a default the same way.
         *
         * The elvises test for an ABSENT member, exactly as `opt` did: an
         * authored `"value": null` reads back as JsonNull (non-null, like
         * org.json's NULL), so it still overrides the default rather than
         * being replaced by it. */
        fun authoredValueOf(node: JsonObject): JsonElement? = when (node.reqString("t")) {
            "text_input", "editor" -> node["value"] ?: JsonPrimitive("")
            "checkbox" ->
                if ("state" in node) node["state"] ?: JsonPrimitive("off")
                else node["checked"] ?: JsonPrimitive(false)
            "switch" -> node["checked"] ?: JsonPrimitive(false)
            // null when absent: a plain button has no authored value at all.
            "button", "icon_button" -> node["checked"]
            "search_bar" -> node["value"] ?: JsonPrimitive("")
            "dropdown" ->
                if (node.boolOr("editable")) node["value"] ?: JsonPrimitive("")
                else node["value"]
            "enum_list", "segmented_button" -> node["value"]
                ?: if (node.boolOr("multi_select")) JsonArray(emptyList()) else null
            "slider" ->
                if ("value_end" in node) {
                    val floor = node["value"] ?: node["min"] ?: JsonPrimitive(0)
                    JsonArray(listOf(floor, node["value_end"] ?: floor))
                } else node["value"]
                    ?: node.arrOrNull("values")?.get(0) ?: node["min"] ?: JsonPrimitive(0)
            "variant_host" -> node["value"]
            else -> null
        }

        /** The legal checkbox `state` strings (§17.4 tri-state). */
        val TRI_STATES: Set<String> = setOf("off", "on", "indeterminate")

        /** The two SPEC 4.2 kind tests org.json used to get from Kotlin's
         * `is Boolean` / `is Number`. Both refuse a JSON STRING that merely
         * spells one (`"true"`, `"5"`) — the coercion that dies with the swap.
         * Local to this file on purpose: JsonAccess has no boolean/binary64
         * PREDICATE (its `boolOr`/`wireIntOrNull` are keyed readers), and a
         * private member cannot collide with one being added there. */
        private fun isJsonBoolean(v: JsonElement?): Boolean =
            v is JsonPrimitive && !v.isString && v.content.toBooleanStrictOrNull() != null

        private fun jsonNumberOrNull(v: JsonElement?): Double? =
            (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    }

    private fun authoredValue(node: JsonObject): JsonElement? = authoredValueOf(node)
}
