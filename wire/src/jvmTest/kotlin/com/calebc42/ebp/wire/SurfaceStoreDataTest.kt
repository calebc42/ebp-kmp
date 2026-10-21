// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 13.8 (amendment #194): the store materializes before it validates,
// patches re-materialize at a new revision atomically, a patch that writes
// a published value back acknowledges the draft under §13.6, and a restored
// record re-materializes its authored tree — including a record written
// before the amendment, whose absent members are the prior format.
package com.calebc42.ebp.wire

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceStoreDataTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun ops(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    private val journal = json(
        """{"t":"column","children":[
            {"t":"text","bind":{"text":"/journal/title"}},
            {"t":"text_input","id":"draft","bind":{"value":"/journal/draft"}}]}""",
    )
    private val document = json("""{"journal":{"title":"Morning","draft":"first"}}""")

    private fun textOf(spec: JsonObject?): String =
        spec!!.reqArr("children")[0].let { (it as JsonObject).reqString("text") }

    @Test
    fun aTreeWithoutBindingsIsStoredOnceAndReloadsClean() {
        class RecordingBacking : IncrementalSurfaceBacking {
            var state = SurfaceState(emptyList(), emptyList())
            var wholeWrites = 0
            val puts = mutableListOf<PersistedRecord>()
            override fun load() = state
            override fun replaceRecords(records: List<PersistedRecord>) {
                wholeWrites++
                state = state.copy(records = records)
            }
            override fun replaceDrafts(drafts: List<PersistedDraft>) {
                state = state.copy(drafts = drafts)
            }
            override fun replaceState(records: List<PersistedRecord>, drafts: List<PersistedDraft>) {
                replaceRecords(records)
                replaceDrafts(drafts)
            }
            override fun putRecord(record: PersistedRecord) {
                puts += record
                state = state.copy(records = state.records.filter { it.surface != record.surface } + record)
            }
            override fun putRecordWithDrafts(record: PersistedRecord, drafts: List<PersistedDraft>) {
                putRecord(record)
                replaceDrafts(drafts)
            }
        }
        val backing = RecordingBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        val literal = json("""{"t":"text","text":"literal"}""")
        // The ordinary documentless push under the capability: nothing to
        // resolve, so the record holds its one tree once.
        store.update("app:p", 1, literal, null, null, null, dataModelGranted = true)
        assertNull("an unbound tree is stored once", backing.puts.single().authoredSpec)
        assertEquals(literal, backing.puts.single().spec)
        // Without the capability likewise.
        store.update("app:q", 1, literal, null, null, null, dataModelGranted = false)
        assertNull(backing.puts.last().authoredSpec)
        // A bound tree stores both.
        store.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        assertEquals(journal, backing.puts.last().authoredSpec)
        assertEquals("Morning", textOf(backing.puts.last().spec))

        // Reloaded, the stored shape is exactly what this store would persist:
        // nothing is dirty, and the next mutation is still one record.
        val restored = SurfaceStore(16, 1024, backing = backing)
        assertEquals(literal, restored.authoredSpec("app:p"))
        assertEquals(literal, restored.spec("app:p"))
        assertEquals(journal, restored.authoredSpec("app:j"))
        val before = backing.puts.size
        restored.update("app:p", 2, json("""{"t":"text","text":"again"}"""), null, null, null,
            dataModelGranted = true)
        assertEquals(before + 1, backing.puts.size)
        assertEquals(0, backing.wholeWrites)
        // A patch re-materializes the unbound tree it holds, unchanged.
        assertEquals("applied",
            restored.patch("app:p", 3, ops("""[{"op":"set","path":"/x","value":1}]""")).status)
        assertEquals("again", restored.spec("app:p")!!.reqString("text"))
        assertEquals(json("""{"x":1}"""), restored.data("app:p"))
    }

    @Test
    fun snapshotsMaterializeAndPatchesReMaterializeAtANewRevision() {
        val store = SurfaceStore(16, 1024)
        val applied = store.update("app:j", 1, journal, null, null, null,
            data = document, dataModelGranted = true)
        assertEquals("applied", applied.status)
        assertEquals("Morning", textOf(store.spec("app:j")))
        assertEquals(journal, store.authoredSpec("app:j"))
        assertEquals(document, store.data("app:j"))
        // The materialized draft field seeds from the document.
        assertEquals(JsonPrimitive("first"), store.currentValue("app:j", "draft"))

        val patched = store.patch("app:j", 2, ops(
            """[{"op":"set","path":"/journal/title","value":"Evening"},{"op":"remove","path":"/journal/draft"}]"""))
        assertEquals(SurfaceResult("applied", 2, true), patched)
        assertEquals("Evening", textOf(store.spec("app:j")))
        assertEquals(json("""{"journal":{"title":"Evening"}}"""), store.data("app:j"))
        assertEquals(2L, store.revisionOf("app:j"))
        // A stale revision leaves everything as it is.
        assertEquals(SurfaceResult("stale", 2, true),
            store.patch("app:j", 2, ops("""[{"op":"set","path":"/journal/title","value":"x"}]""")))
        assertEquals("Evening", textOf(store.spec("app:j")))
    }

    @Test
    fun aStalePatchIsAnsweredBeforeAnyWork() {
        // PLAN-responsiveness C6a: at or below the floor the reducer answers
        // `stale` first — no apply, materialize or admit runs for it, and ops
        // that would be content-invalid at a newer revision are never looked
        // at, because validation guards a state change and a stale request
        // makes none (SPEC 13.2/13.8.4).
        val store = SurfaceStore(16, 1024)
        store.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        assertEquals(SurfaceResult("applied", 2, true), store.patch("app:j", 2, ops(
            """[{"op":"set","path":"/journal/title","value":"Evening"}]""")))
        val stages = mutableListOf<String>()
        store.stageSink = { stage, _ -> stages += stage }
        val invalidAtANewerRevision = ops("""[{"op":"remove","path":"/journal/missing"}]""")
        assertEquals(SurfaceResult("stale", 2, true), store.patch("app:j", 2, invalidAtANewerRevision))
        assertEquals(SurfaceResult("stale", 2, true), store.patch("app:j", 1, invalidAtANewerRevision))
        assertEquals(emptyList<String>(), stages)
        assertEquals("Evening", textOf(store.spec("app:j")))
        assertEquals(2L, store.revisionOf("app:j"))
        // The same ops above the floor are still validated and refused.
        store.stageSink = null
        assertThrows(ContentInvalid::class.java) { store.patch("app:j", 3, invalidAtANewerRevision) }
        assertEquals(2L, store.revisionOf("app:j"))
    }

    @Test
    fun aPatchAppliesCompletelyOrNotAtAll() {
        val store = SurfaceStore(16, 1024)
        store.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        // The second op removes nothing: the first op must not have landed.
        val invalid = assertThrows(ContentInvalid::class.java) {
            store.patch("app:j", 2, ops(
                """[{"op":"set","path":"/journal/title","value":"Evening"},{"op":"remove","path":"/journal/missing"}]"""))
        }
        assertEquals("ops[1].path", invalid.path)
        assertEquals("Morning", textOf(store.spec("app:j")))
        assertEquals(1L, store.revisionOf("app:j"))
        // A resolved value of the wrong type rejects at the binding.
        assertEquals("spec.children[0].bind.text", assertThrows(ContentInvalid::class.java) {
            store.patch("app:j", 2, ops("""[{"op":"set","path":"/journal/title","value":5}]"""))
        }.path)
        // Nothing to patch on an unknown surface or a tombstone.
        assertEquals("surface", assertThrows(ContentInvalid::class.java) {
            store.patch("app:none", 1, ops("""[{"op":"set","path":"/a","value":1}]"""))
        }.path)
        store.remove("app:j", 3)
        assertEquals("surface", assertThrows(ContentInvalid::class.java) {
            store.patch("app:j", 4, ops("""[{"op":"set","path":"/a","value":1}]"""))
        }.path)
    }

    @Test
    fun aPatchThatWritesThePublishedValueAcknowledgesTheDraft() {
        val store = SurfaceStore(16, 1024)
        store.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        store.putDraft("app:j", "draft", JsonPrimitive("typed"))
        assertTrue(store.hasDraft("app:j", "draft"))
        // A different value leaves the draft standing (SPEC 13.6).
        store.patch("app:j", 2, ops("""[{"op":"set","path":"/journal/draft","value":"other"}]"""))
        assertTrue(store.hasDraft("app:j", "draft"))
        assertEquals(JsonPrimitive("typed"), store.currentValue("app:j", "draft"))
        // The published value written back acknowledges it.
        store.patch("app:j", 3, ops("""[{"op":"set","path":"/journal/draft","value":"typed"}]"""))
        assertFalse(store.hasDraft("app:j", "draft"))
        assertEquals(JsonPrimitive("typed"), store.currentValue("app:j", "draft"))
    }

    @Test
    fun theDocumentAndBindingsRequireTheCapability() {
        val store = SurfaceStore(16, 1024)
        assertEquals("data", assertThrows(ContentInvalid::class.java) {
            store.update("app:j", 1, json("""{"t":"text","text":"x"}"""), null, null, null,
                data = document, dataModelGranted = false)
        }.path)
        assertEquals("spec.children[0].bind", assertThrows(ContentInvalid::class.java) {
            store.update("app:j", 1, journal, null, null, null, dataModelGranted = false)
        }.path)
        // A stale spec never carries a binding.
        assertEquals("stale_spec.bind", assertThrows(ContentInvalid::class.java) {
            store.update("app:j", 1, journal, json("""{"t":"text","bind":{"text":"/journal/title"}}"""),
                null, null, data = document, dataModelGranted = true)
        }.path)
        assertNull(store.spec("app:j"))
    }

    @Test
    fun restoreReMaterializesTheAuthoredTreeAndMigratesAPriorRecord() {
        val dir = Files.createTempDirectory("ebp-data").toFile()
        val file = dir.resolve("surfaces.json")
        val first = SurfaceStore(16, 1024, backing = FileSurfaceBacking(file))
        first.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        first.patch("app:j", 2, ops("""[{"op":"set","path":"/journal/title","value":"Evening"}]"""))

        val second = SurfaceStore(16, 1024, backing = FileSurfaceBacking(file))
        assertEquals("Evening", textOf(second.spec("app:j")))
        assertEquals(journal, second.authoredSpec("app:j"))
        assertEquals(json("""{"journal":{"title":"Evening","draft":"first"}}"""), second.data("app:j"))
        // The restored store patches on from where the first one stopped.
        assertEquals("applied",
            second.patch("app:j", 3, ops("""[{"op":"set","path":"/journal/title","value":"Night"}]""")).status)
        assertEquals("Night", textOf(second.spec("app:j")))

        // A record written before the amendment carries neither member: it
        // loads as its own authored tree over an empty document (SPEC 3.3).
        val legacy = MemorySurfaceBacking().apply {
            replaceRecords(listOf(PersistedRecord("app:old", 7, true,
                json("""{"t":"text","text":"literal"}"""), null)))
        }
        val restored = SurfaceStore(16, 1024, backing = legacy)
        assertEquals("literal", restored.spec("app:old")!!.reqString("text"))
        assertEquals(json("""{"t":"text","text":"literal"}"""), restored.authoredSpec("app:old"))
        assertNull(restored.data("app:old"))
        assertEquals("applied",
            restored.patch("app:old", 8, ops("""[{"op":"set","path":"/unused","value":true}]""")).status)
        assertEquals(buildJsonObject { put("unused", true) }, restored.data("app:old"))
        dir.deleteRecursively()
    }

    @Test
    fun pointerOperationsHonourTheirGrammar() {
        val doc = json("""{"items":["a","b"],"obj":{"k":1}}""")
        fun set(path: String, value: String) =
            JsonPointer.set(doc, JsonPointer.parse(path, "p"), Json.parseToJsonElement(value), "p")
        assertEquals(json("""{"items":["a","b","c"],"obj":{"k":1}}"""), set("/items/2", "\"c\""))
        assertEquals(json("""{"items":["a","z"],"obj":{"k":1}}"""), set("/items/1", "\"z\""))
        assertEquals(json("""{"items":["a","b"],"obj":{"k":1,"n":2}}"""), set("/obj/n", "2"))
        assertEquals(json("""{"fresh":true}"""), set("", """{"fresh":true}"""))
        for (bad in listOf("/items/3", "/items/01", "/items/-", "/missing/k", "/obj/k/deeper")) {
            assertThrows("$bad must be rejected", ContentInvalid::class.java) { set(bad, "1") }
        }
        assertThrows(ContentInvalid::class.java) { set("", "1") }
        assertEquals(json("""{"items":["b"],"obj":{"k":1}}"""),
            JsonPointer.remove(doc, JsonPointer.parse("/items/0", "p"), "p"))
        assertEquals(json("""{"items":["a","b"],"obj":{}}"""),
            JsonPointer.remove(doc, JsonPointer.parse("/obj/k", "p"), "p"))
        assertThrows(ContentInvalid::class.java) {
            JsonPointer.remove(doc, JsonPointer.parse("/obj/x", "p"), "p")
        }
        assertEquals(listOf("a/b", "m~n"), JsonPointer.parse("/a~1b/m~0n", "p"))
        assertThrows(ContentInvalid::class.java) { JsonPointer.parse("/a~2b", "p") }
        assertThrows(ContentInvalid::class.java) {
            JsonPointer.parse("/" + "x/".repeat(WireLimits.MAX_POINTER_SEGMENTS) + "y", "p")
        }
    }

    @Test
    fun bindingsAndOperationsStayWithinTheFixedLimits() {
        val store = SurfaceStore(16, 1024)
        val tooMany = buildJsonArray {
            repeat(WireLimits.MAX_DATA_OPS + 1) {
                add(buildJsonObject { put("op", "set"); put("path", "/n"); put("value", it) })
            }
        }
        store.update("app:j", 1, journal, null, null, null, data = document, dataModelGranted = true)
        assertEquals("ops", assertThrows(ContentInvalid::class.java) {
            store.patch("app:j", 2, tooMany)
        }.path)
        val deep = buildJsonObject {
            var level: JsonObject = buildJsonObject { put("leaf", 1) }
            repeat(WireLimits.MAX_DATA_DEPTH) { level = buildJsonObject { put("d", level) } }
            put("root", level)
        }
        assertEquals("data", assertThrows(ContentInvalid::class.java) {
            store.update("app:k", 1, json("""{"t":"text","text":"x"}"""), null, null, null,
                data = deep, dataModelGranted = true)
        }.path)
    }
}
